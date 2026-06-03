package ru.ashesha.buildBattleAI.game.feedback;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.config.api.Lang;
import ru.ashesha.buildBattleAI.game.ArenaState;
import ru.ashesha.buildBattleAI.game.GamePlayer;
import ru.ashesha.buildBattleAI.game.GameSession;
import ru.ashesha.buildBattleAI.message.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.message.micro.BoardMicroService;
import ru.ashesha.buildBattleAI.ml.api.TopKEntry;
import ru.ashesha.buildBattleAI.util.SoundPalette;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * In-game presentation layer for the AI's "thinking out loud" persona.
 * Owns per-session scoreboard objects, tab list strings, randomised thought
 * phrases, AI sound effects, the triumph title on a correct guess, and the
 * skip-theme feather distribution.
 * <p>
 * Hooked into the game flow at the following points:
 * <ul>
 *   <li>{@link #onPlayerJoinedWaiting} — when a player joins a lobby</li>
 *   <li>{@link #onCountdownTick} — every second during COUNTDOWN</li>
 *   <li>{@link #onCountdownCancelled} — when COUNTDOWN aborts back to WAITING</li>
 *   <li>{@link #startPlayingPhase} — on {@code GameManager.startGame}</li>
 *   <li>{@link #endSession} — on game end / forced shutdown</li>
 *   <li>{@link #playerLeft} — on a player leaving mid-game</li>
 *   <li>{@link #onTick} — once per second from the game tick</li>
 *   <li>{@link #onEvaluated} — on every ML evaluation</li>
 *   <li>{@link #onThemeOrScoreChanged} — after a score / build-time-expired / skip</li>
 * </ul>
 * All public methods run on the Bukkit main thread.
 */
@RequiredArgsConstructor
public final class FeedbackController {

    /** Title display timing (ticks) for the triumph overlay on a correct guess. */
    private static final int TITLE_FADE_IN = 5;
    private static final int TITLE_STAY = 40;
    private static final int TITLE_FADE_OUT = 10;

    /**
     * Scoreboard line indices (0 = bottom, MAX = top). The three layouts —
     * WAITING / COUNTDOWN / PLAYING — share the title + arena lines and
     * differ in the middle block. Indices are picked to leave a vertical
     * spacer pattern that reads cleanly in-game.
     */
    private static final int LINE_SEP_TOP = 11;
    private static final int LINE_ARENA = 10;
    private static final int LINE_SPACE1 = 9;
    // WAITING-mode lines (reused indices in the middle block).
    private static final int LINE_STATUS_LABEL = 8;
    private static final int LINE_STATUS_VALUE = 7;
    private static final int LINE_SPACE2 = 6;
    private static final int LINE_W_PLAYERS = 5;
    private static final int LINE_W_NEED_OR_COUNTDOWN = 4;
    // PLAYING-mode lines.
    private static final int LINE_P_THEME_LABEL = 8;
    private static final int LINE_P_THEME_VALUE = 7;
    // LINE_SPACE2 reused
    private static final int LINE_P_SCORE = 5;
    private static final int LINE_P_BUILD_TIME = 4;
    private static final int LINE_P_GAME_TIME = 3;
    private static final int LINE_P_SPACE3 = 2;
    private static final int LINE_P_AI_LABEL = 1;
    private static final int LINE_P_AI_VALUE = 0;

    @NonNull
    private final BuildBattleAI plugin;

    /** Per-arena live state. Keyed by arena name to match GameManager's sessions map. */
    private final Map<String, SessionFeedback> sessions = new HashMap<>();

    /** Shared across sessions — randomises message variants without immediate repeats. */
    private final ThoughtBank thoughts = new ThoughtBank();

    /** RNG for the "should I send a chat thought this tick?" coin flip. */
    private final Random random = new Random();

    // ── lifecycle hooks ───────────────────────────────────────────────

    /**
     * Called when a player joins a lobby (state WAITING). Lazily creates the
     * per-session feedback bundle (capturing config + lang) and paints the
     * waiting scoreboard + tab list for this player.
     */
    public void onPlayerJoinedWaiting(@NonNull GameSession session, @NonNull UUID playerId) {
        SessionFeedback sf = ensureSession(session);
        if (sf == null)
            return;
        Player player = Bukkit.getPlayer(playerId);
        if (player == null)
            return;
        GamePlayer gp = session.players().get(playerId);
        if (gp == null)
            return;
        paintWaitingFor(sf, player, gp);
    }

    /**
     * Repaints every player's scoreboard + tab list to reflect the current
     * countdown second. Called from {@code GameManager.startCountdown}'s
     * 1 Hz timer.
     */
    public void onCountdownTick(@NonNull GameSession session, int secondsLeft) {
        SessionFeedback sf = sessions.get(session.arena().name());
        if (sf == null)
            return;
        for (GamePlayer gp : session.players().values()) {
            Player player = Bukkit.getPlayer(gp.playerId());
            if (player == null)
                continue;
            paintCountdownFor(sf, player, gp, secondsLeft);
        }
    }

    /**
     * Called when COUNTDOWN aborts (not enough players). Repaints every
     * scoreboard back to the WAITING layout.
     */
    public void onCountdownCancelled(@NonNull GameSession session) {
        SessionFeedback sf = sessions.get(session.arena().name());
        if (sf == null)
            return;
        for (GamePlayer gp : session.players().values()) {
            Player player = Bukkit.getPlayer(gp.playerId());
            if (player == null)
                continue;
            paintWaitingFor(sf, player, gp);
        }
    }

    /**
     * Transitions every player's scoreboard from the WAITING/COUNTDOWN
     * layout to the PLAYING layout. Called from
     * {@code GameManager.startGame} right after theme assignment.
     */
    public void startPlayingPhase(@NonNull GameSession session) {
        SessionFeedback sf = ensureSession(session);
        if (sf == null)
            return;
        for (GamePlayer gp : session.players().values()) {
            Player player = Bukkit.getPlayer(gp.playerId());
            if (player == null)
                continue;
            paintPlayingFor(sf, player, gp);
        }
    }

    /**
     * Tears down all per-player visual elements for the given arena. Called
     * on game end and on forced shutdown — idempotent.
     */
    public void endSession(@NonNull String arenaName) {
        SessionFeedback sf = sessions.remove(arenaName);
        if (sf == null)
            return;
        for (Map.Entry<UUID, BoardMicroService.Board> e : sf.boards.entrySet()) {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player != null) {
                e.getValue().remove(player);
                plugin.getContext().getMessageService().sendTab(player, "", "");
            }
            thoughts.forgetPlayer(e.getKey());
        }
        sf.boards.clear();
    }

    /**
     * Removes per-player feedback when a player leaves mid-game. The rest of
     * the session keeps running.
     */
    public void playerLeft(@NonNull String arenaName, @NonNull UUID playerId) {
        SessionFeedback sf = sessions.get(arenaName);
        if (sf == null)
            return;
        BoardMicroService.Board board = sf.boards.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (board != null && player != null) {
            board.remove(player);
            plugin.getContext().getMessageService().sendTab(player, "", "");
        }
        sf.lastGuess.remove(playerId);
        sf.lastConfidence.remove(playerId);
        thoughts.forgetPlayer(playerId);
        // Other players' scoreboards show "Players: %count%/%max%" — repaint
        // theirs so the count is in sync even before the next per-second tick.
        repaintPlayersLineForOthers(sf);
    }

    /**
     * Per-second refresh hook. Updates the time fields on every player's
     * scoreboard + tab footer so the build/game timers tick visibly without
     * waiting for the next ML evaluation.
     */
    public void onTick(@NonNull GameSession session) {
        SessionFeedback sf = sessions.get(session.arena().name());
        if (sf == null)
            return;
        for (GamePlayer gp : session.players().values()) {
            Player player = Bukkit.getPlayer(gp.playerId());
            if (player == null)
                continue;
            BoardMicroService.Board board = sf.boards.get(gp.playerId());
            if (board != null && sf.cfg.scoreboardEnabled()) {
                board.setLine(player, LINE_P_BUILD_TIME,
                        sf.lang.get("game.ai.scoreboard.build-time",
                                "%time%", formatTime(gp.buildTimeRemaining())));
                board.setLine(player, LINE_P_GAME_TIME,
                        sf.lang.get("game.ai.scoreboard.game-time",
                                "%time%", formatTime(session.gameTimeRemaining())));
            }
            // Tab footer carries the same time string — refresh it too so the
            // tab list doesn't freeze for the entire game.
            if (sf.cfg.tabEnabled())
                refreshTabPlayingFor(sf, player, gp);
        }
    }

    /**
     * Repaints the theme + score lines for a single player. Called by
     * {@code GameManager} immediately after assigning a new theme (game start,
     * after a score, after a build-time timeout, or after a skip-theme feather
     * use) so the scoreboard reflects the change without waiting for the next
     * ML tick.
     */
    public void onThemeOrScoreChanged(@NonNull GameSession session, @NonNull UUID playerId) {
        SessionFeedback sf = sessions.get(session.arena().name());
        if (sf == null)
            return;
        GamePlayer gp = session.players().get(playerId);
        if (gp == null)
            return;
        Player player = Bukkit.getPlayer(playerId);
        if (player == null)
            return;
        BoardMicroService.Board board = sf.boards.get(playerId);
        if (board == null)
            return;
        String theme = ThemeFormatter.format(sf.lang, session.getTheme(gp.themeIndex()));
        board.setLine(player, LINE_P_THEME_VALUE,
                sf.lang.get("game.ai.scoreboard.theme-value", "%theme%", theme));
        board.setLine(player, LINE_P_SCORE,
                sf.lang.get("game.ai.scoreboard.score",
                        "%score%", String.valueOf(gp.score())));
        // The current AI guess is stale after a theme switch — reset it so
        // the scoreboard doesn't keep showing the prior theme's guess until
        // the next ML tick arrives.
        sf.lastGuess.remove(playerId);
        sf.lastConfidence.remove(playerId);
        board.setLine(player, LINE_P_AI_VALUE,
                sf.lang.get("game.ai.scoreboard.ai-thinking"));
    }

    /**
     * Main evaluation hook — invoked on the Bukkit main thread for every
     * completed ML inference of a session player. Routes the result through
     * the visible channels (scoreboard guess line, action bar, occasional
     * chat thought, hmm sound), or the triumph branch on a match.
     */
    public void onEvaluated(@NonNull String arenaName,
                            @NonNull UUID playerId,
                            int themeIndex,
                            @NonNull List<TopKEntry> topK,
                            boolean matched) {
        SessionFeedback sf = sessions.get(arenaName);
        if (sf == null || topK.isEmpty())
            return;

        Player player = Bukkit.getPlayer(playerId);
        if (player == null)
            return;
        GamePlayer gp = sf.session.players().get(playerId);
        if (gp == null)
            return;

        BBAIMessageService msg = plugin.getContext().getMessageService();
        TopKEntry top1 = topK.get(0);
        String guess1Raw = top1.className();
        String guess1 = ThemeFormatter.format(sf.lang, guess1Raw);
        int confidencePct = clampPercent(top1.score());

        // Update the AI guess line on the scoreboard regardless of match —
        // showing the AI's wrong guess is half the fun.
        if (sf.cfg.scoreboardEnabled()) {
            BoardMicroService.Board board = sf.boards.get(playerId);
            if (board != null) {
                String guessLine = sf.cfg.showConfidence()
                        ? sf.lang.get("game.ai.scoreboard.ai-value",
                                "%guess%", guess1,
                                "%confidence%", String.valueOf(confidencePct))
                        : sf.lang.get("game.ai.scoreboard.ai-value",
                                "%guess%", guess1,
                                "%confidence%", String.valueOf(confidencePct))
                                .replace(" (" + confidencePct + "%)", "");
                board.setLine(player, LINE_P_AI_VALUE, guessLine);
            }
            sf.lastGuess.put(playerId, guess1);
            sf.lastConfidence.put(playerId, confidencePct);
        }

        // Score event takes precedence over thinking.
        if (matched) {
            handleCorrect(sf, player, gp, topK);
            return;
        }

        // Action bar: thinking or confused, depending on top1/top2 gap.
        if (sf.cfg.actionBarEnabled()) {
            String message;
            if (topK.size() >= 2
                    && sf.cfg.confusedThreshold() > 0
                    && Math.abs(top1.score() - topK.get(1).score()) < sf.cfg.confusedThreshold()) {
                String guess2 = ThemeFormatter.format(sf.lang, topK.get(1).className());
                message = thoughts.pick(sf.lang, "game.ai.confused", playerId);
                if (message != null)
                    message = message
                            .replace("%guess1%", guess1)
                            .replace("%guess2%", guess2);
            } else {
                message = thoughts.pick(sf.lang, "game.ai.thinking", playerId);
                if (message != null)
                    message = message
                            .replace("%guess%", guess1)
                            .replace("%confidence%", String.valueOf(confidencePct));
            }
            if (message != null)
                msg.sendActionBar(player, message);

            // Hmm sound — paired with the thinking action bar but gated by
            // chance so it isn't oppressive at default 5-second cadence.
            if (sf.cfg.soundOnThinking()
                    && sf.cfg.soundOnThinkingChance() > 0
                    && random.nextDouble() < sf.cfg.soundOnThinkingChance())
                SoundPalette.AI_THINKING.play(player);
        }

        // Occasional chat thought.
        if (sf.cfg.chatThoughtsEnabled()
                && sf.cfg.chatThoughtsChance() > 0
                && random.nextDouble() < sf.cfg.chatThoughtsChance()) {
            String thought = thoughts.pick(sf.lang, "game.ai.thinking-chat", playerId);
            if (thought != null) {
                thought = thought.replace("%guess%", guess1)
                        .replace("%confidence%", String.valueOf(confidencePct));
                msg.sendChat(player, thought);
            }
        }
    }

    /**
     * Returns a random "theme skipped" line for the player. Used by
     * {@code GameManager.skipTheme} to print confirmation chat after the
     * skip feather is used.
     */
    public String pickSkipFeedback(@NonNull UUID playerId, @NonNull String arenaName) {
        SessionFeedback sf = sessions.get(arenaName);
        if (sf == null)
            return null;
        return thoughts.pick(sf.lang, "game.ai.skip-feedback", playerId);
    }

    /**
     * Returns {@code true} if the active session config has the skip feather
     * feature turned on. {@link GameManager} consults this before populating
     * slot 8 at game start.
     */
    public boolean skipFeatherEnabledFor(@NonNull String arenaName) {
        SessionFeedback sf = sessions.get(arenaName);
        return sf != null && sf.cfg.skipFeatherEnabled();
    }

    // ── private painting helpers ──────────────────────────────────────

    /**
     * Lazily creates (or returns existing) per-session feedback state. Returns
     * {@code null} when feedback is globally disabled by config.
     */
    private SessionFeedback ensureSession(GameSession session) {
        String arena = session.arena().name();
        SessionFeedback existing = sessions.get(arena);
        if (existing != null)
            return existing;
        FeedbackConfig cfg = FeedbackConfig.fromYaml(
                plugin.getContext().getConfigService().config());
        if (!cfg.enabled())
            return null;
        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        SessionFeedback sf = new SessionFeedback(session, cfg, lang);
        sessions.put(arena, sf);
        return sf;
    }

    /**
     * Paints the WAITING-mode scoreboard + tab list for one player. Creates
     * the Board on first call; subsequent calls update lines in-place.
     */
    private void paintWaitingFor(SessionFeedback sf, Player player, GamePlayer gp) {
        if (sf.cfg.scoreboardEnabled()) {
            BoardMicroService.Board board = ensureBoard(sf, player, gp);
            String sep = sf.lang.get("game.ai.scoreboard.sep");
            board.setLine(player, LINE_SEP_TOP, sep);
            board.setLine(player, LINE_ARENA,
                    sf.lang.get("game.ai.scoreboard.arena",
                            "%arena%", sf.session.arena().name()));
            board.setLine(player, LINE_SPACE1, sep + " ");
            board.setLine(player, LINE_STATUS_LABEL,
                    sf.lang.get("game.ai.scoreboard.status-label"));
            board.setLine(player, LINE_STATUS_VALUE,
                    sf.lang.get("game.ai.scoreboard.status-waiting"));
            board.setLine(player, LINE_SPACE2, sep + "  ");
            board.setLine(player, LINE_W_PLAYERS,
                    sf.lang.get("game.ai.scoreboard.players-line",
                            "%count%", String.valueOf(sf.session.players().size()),
                            "%max%", String.valueOf(sf.session.arena().maxPlayers())));
            int needed = Math.max(0,
                    sf.session.arena().minPlayers() - sf.session.players().size());
            board.setLine(player, LINE_W_NEED_OR_COUNTDOWN,
                    sf.lang.get("game.ai.scoreboard.min-to-start",
                            "%needed%", String.valueOf(needed)));
            // Erase any PLAYING-mode lines that might be present from a
            // previous round on the same Board (defensive — endSession should
            // have removed the Board already, but be robust).
            board.removeLine(player, LINE_P_AI_VALUE);
            board.removeLine(player, LINE_P_AI_LABEL);
            board.removeLine(player, LINE_P_SPACE3);
            board.removeLine(player, LINE_P_GAME_TIME);
        }

        if (sf.cfg.tabEnabled())
            plugin.getContext().getMessageService().sendTab(player,
                    sf.lang.get("game.ai.tab.header-waiting",
                            "%arena%", sf.session.arena().name()),
                    sf.lang.get("game.ai.tab.footer-waiting",
                            "%count%", String.valueOf(sf.session.players().size()),
                            "%max%", String.valueOf(sf.session.arena().maxPlayers())));
    }

    /**
     * Paints the COUNTDOWN-mode scoreboard + tab list for one player.
     * Conceptually identical to the WAITING layout but with the
     * "Starting in: Ns" line in place of "Need N more".
     */
    private void paintCountdownFor(SessionFeedback sf, Player player, GamePlayer gp, int seconds) {
        if (sf.cfg.scoreboardEnabled()) {
            BoardMicroService.Board board = ensureBoard(sf, player, gp);
            String sep = sf.lang.get("game.ai.scoreboard.sep");
            board.setLine(player, LINE_SEP_TOP, sep);
            board.setLine(player, LINE_ARENA,
                    sf.lang.get("game.ai.scoreboard.arena",
                            "%arena%", sf.session.arena().name()));
            board.setLine(player, LINE_SPACE1, sep + " ");
            board.setLine(player, LINE_STATUS_LABEL,
                    sf.lang.get("game.ai.scoreboard.status-label"));
            board.setLine(player, LINE_STATUS_VALUE,
                    sf.lang.get("game.ai.scoreboard.status-countdown"));
            board.setLine(player, LINE_SPACE2, sep + "  ");
            board.setLine(player, LINE_W_PLAYERS,
                    sf.lang.get("game.ai.scoreboard.players-line",
                            "%count%", String.valueOf(sf.session.players().size()),
                            "%max%", String.valueOf(sf.session.arena().maxPlayers())));
            board.setLine(player, LINE_W_NEED_OR_COUNTDOWN,
                    sf.lang.get("game.ai.scoreboard.countdown-line",
                            "%seconds%", String.valueOf(seconds)));
        }

        if (sf.cfg.tabEnabled())
            plugin.getContext().getMessageService().sendTab(player,
                    sf.lang.get("game.ai.tab.header-countdown",
                            "%arena%", sf.session.arena().name(),
                            "%seconds%", String.valueOf(seconds)),
                    sf.lang.get("game.ai.tab.footer-countdown",
                            "%count%", String.valueOf(sf.session.players().size()),
                            "%max%", String.valueOf(sf.session.arena().maxPlayers())));
    }

    /**
     * Paints the PLAYING-mode scoreboard + tab list for one player.
     * Removes WAITING-mode lines that would otherwise linger from the
     * lobby/countdown layout.
     */
    private void paintPlayingFor(SessionFeedback sf, Player player, GamePlayer gp) {
        if (sf.cfg.scoreboardEnabled()) {
            BoardMicroService.Board board = ensureBoard(sf, player, gp);
            String sep = sf.lang.get("game.ai.scoreboard.sep");
            String theme = ThemeFormatter.format(sf.lang,
                    sf.session.getTheme(gp.themeIndex()));

            board.setLine(player, LINE_SEP_TOP, sep);
            board.setLine(player, LINE_ARENA,
                    sf.lang.get("game.ai.scoreboard.arena",
                            "%arena%", sf.session.arena().name()));
            board.setLine(player, LINE_SPACE1, sep + " ");
            board.setLine(player, LINE_P_THEME_LABEL,
                    sf.lang.get("game.ai.scoreboard.theme-label"));
            board.setLine(player, LINE_P_THEME_VALUE,
                    sf.lang.get("game.ai.scoreboard.theme-value", "%theme%", theme));
            board.setLine(player, LINE_SPACE2, sep + "  ");
            board.setLine(player, LINE_P_SCORE,
                    sf.lang.get("game.ai.scoreboard.score",
                            "%score%", String.valueOf(gp.score())));
            board.setLine(player, LINE_P_BUILD_TIME,
                    sf.lang.get("game.ai.scoreboard.build-time",
                            "%time%", formatTime(gp.buildTimeRemaining())));
            board.setLine(player, LINE_P_GAME_TIME,
                    sf.lang.get("game.ai.scoreboard.game-time",
                            "%time%", formatTime(sf.session.gameTimeRemaining())));
            board.setLine(player, LINE_P_SPACE3, sep + "   ");
            board.setLine(player, LINE_P_AI_LABEL,
                    sf.lang.get("game.ai.scoreboard.ai-label"));
            board.setLine(player, LINE_P_AI_VALUE,
                    sf.lang.get("game.ai.scoreboard.ai-thinking"));
        }

        if (sf.cfg.tabEnabled())
            refreshTabPlayingFor(sf, player, gp);
    }

    /**
     * Returns the player's Board, creating it on first call. Boards live
     * in {@link SessionFeedback#boards} keyed by player UUID.
     */
    private BoardMicroService.Board ensureBoard(SessionFeedback sf, Player player, GamePlayer gp) {
        BoardMicroService.Board board = sf.boards.get(gp.playerId());
        if (board == null) {
            board = plugin.getContext().getMessageService().createBoard(player,
                    sf.lang.get("game.ai.scoreboard.title"));
            sf.boards.put(gp.playerId(), board);
        }
        return board;
    }

    /**
     * Triumph branch — fires on a successful AI match. Big title, action-bar
     * confirmation, optional arena-wide chat broadcast, celebratory sound.
     */
    private void handleCorrect(SessionFeedback sf, Player player, GamePlayer gp, List<TopKEntry> topK) {
        // The player's theme at evaluation time may differ from the one we
        // see now (GameManager.handleScore advances themeIndex before we run).
        // Use top-1 from the prediction — that's the class the AI saw and
        // celebrated.
        String matchedTheme = topK.isEmpty()
                ? "?"
                : ThemeFormatter.format(sf.lang, topK.get(0).className());

        BBAIMessageService msg = plugin.getContext().getMessageService();

        if (sf.cfg.titleOnCorrectEnabled()) {
            String title = thoughts.pick(sf.lang, "game.ai.correct-title", gp.playerId());
            String subtitle = thoughts.pick(sf.lang, "game.ai.correct-subtitle", gp.playerId());
            if (subtitle != null)
                subtitle = subtitle.replace("%theme%", matchedTheme);
            msg.sendTitle(player, title, subtitle, TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
        }

        if (sf.cfg.actionBarEnabled()) {
            String bar = thoughts.pick(sf.lang, "game.ai.correct-actionbar", gp.playerId());
            if (bar != null)
                msg.sendActionBar(player, bar.replace("%theme%", matchedTheme));
        }

        SoundPalette.CELEBRATE.play(player);

        if (sf.cfg.broadcastOnCorrectEnabled()) {
            String broadcast = thoughts.pick(sf.lang, "game.ai.correct-broadcast", gp.playerId());
            if (broadcast != null) {
                broadcast = broadcast
                        .replace("%player%", gp.playerName())
                        .replace("%theme%", matchedTheme);
                for (GamePlayer other : sf.session.players().values()) {
                    Player p = Bukkit.getPlayer(other.playerId());
                    if (p != null)
                        msg.sendChat(p, broadcast);
                }
            }
        }
    }

    /** Refreshes the tab list header/footer for one player in PLAYING mode. */
    private void refreshTabPlayingFor(SessionFeedback sf, Player player, GamePlayer gp) {
        String header = sf.lang.get("game.ai.tab.header",
                "%arena%", sf.session.arena().name(),
                "%round%", String.valueOf(gp.score() + 1));
        String footer = sf.lang.get("game.ai.tab.footer",
                "%count%", String.valueOf(sf.session.players().size()),
                "%max%", String.valueOf(sf.session.arena().maxPlayers()),
                "%time%", formatTime(sf.session.gameTimeRemaining()));
        plugin.getContext().getMessageService().sendTab(player, header, footer);
    }

    /**
     * After one player leaves, repaint the "Players: N/M" line on every
     * other player's scoreboard so the count is in sync immediately.
     * Only the players-count line is touched.
     */
    private void repaintPlayersLineForOthers(SessionFeedback sf) {
        ArenaState state = sf.session.state();
        if (state != ArenaState.WAITING && state != ArenaState.COUNTDOWN)
            return; // PLAYING scoreboard doesn't have a players-count line
        for (GamePlayer gp : sf.session.players().values()) {
            Player player = Bukkit.getPlayer(gp.playerId());
            if (player == null)
                continue;
            BoardMicroService.Board board = sf.boards.get(gp.playerId());
            if (board == null)
                continue;
            board.setLine(player, LINE_W_PLAYERS,
                    sf.lang.get("game.ai.scoreboard.players-line",
                            "%count%", String.valueOf(sf.session.players().size()),
                            "%max%", String.valueOf(sf.session.arena().maxPlayers())));
            if (state == ArenaState.WAITING) {
                int needed = Math.max(0,
                        sf.session.arena().minPlayers() - sf.session.players().size());
                board.setLine(player, LINE_W_NEED_OR_COUNTDOWN,
                        sf.lang.get("game.ai.scoreboard.min-to-start",
                                "%needed%", String.valueOf(needed)));
            }
        }
    }

    /** {@code 73} → {@code "1:13"}, {@code 9} → {@code "0:09"}. */
    private static String formatTime(int totalSeconds) {
        if (totalSeconds < 0)
            totalSeconds = 0;
        int m = totalSeconds / 60;
        int s = totalSeconds % 60;
        return m + ":" + (s < 10 ? "0" + s : Integer.toString(s));
    }

    /** Cosine score in roughly [-1, 1] → percent [0, 100], clamped. */
    private static int clampPercent(float score) {
        int pct = Math.round(score * 100f);
        if (pct < 0) return 0;
        if (pct > 100) return 100;
        return pct;
    }

    /**
     * Per-arena live state. Captured once at session start so config flips
     * during a game don't take effect mid-round.
     */
    private static final class SessionFeedback {
        final GameSession session;
        final FeedbackConfig cfg;
        final Lang lang;
        final Map<UUID, BoardMicroService.Board> boards = new HashMap<>();
        final Map<UUID, String> lastGuess = new HashMap<>();
        final Map<UUID, Integer> lastConfidence = new HashMap<>();

        SessionFeedback(@NonNull GameSession session,
                        @NonNull FeedbackConfig cfg,
                        @NonNull Lang lang) {
            this.session = session;
            this.cfg = cfg;
            this.lang = lang;
        }
    }
}
