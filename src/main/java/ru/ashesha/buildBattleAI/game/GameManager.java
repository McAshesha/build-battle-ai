package ru.ashesha.buildBattleAI.game;

import com.cryptomorin.xseries.XMaterial;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.config.api.Lang;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.game.api.BBAIGameManager;
import ru.ashesha.buildBattleAI.game.feedback.FeedbackController;
import ru.ashesha.buildBattleAI.game.feedback.SkipThemeItem;
import ru.ashesha.buildBattleAI.message.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;
import ru.ashesha.buildBattleAI.util.SoundPalette;
import org.bukkit.inventory.ItemStack;

import java.util.*;

import org.bukkit.GameMode;

/**
 * Full implementation of {@link BBAIGameManager}.
 * <p>
 * Manages game sessions across all arenas: join/leave flow, countdown,
 * game start with theme assignment, per-second game tick (build timers
 * and global timer), render/ML pipeline every 5 seconds, zone clearing,
 * results display, and player state restoration.
 * <p>
 * All timers use the Bukkit scheduler. Rendering and ML prediction run
 * asynchronously; block capture and zone clearing run on the main thread.
 *
 * @see GameSession
 * @see GamePlayer
 * @see PlayerSnapshot
 */
@RequiredArgsConstructor
public class GameManager implements BBAIGameManager, PluginService {

    /** Fallback themes used when ML service is unreachable. */
    private static final List<String> FALLBACK_THEMES = Arrays.asList(
            "cat", "sword", "ball", "house", "tree", "glasses"
    );

    /** Duration of results display before returning players. */
    private static final int ENDING_DURATION_TICKS = 200; // 10 seconds

    @NonNull
    private final BuildBattleAI plugin;

    /** Active sessions keyed by arena name. */
    private final Map<String, GameSession> sessions = new HashMap<>();

    /** Player UUID → arena name mapping for quick lookups. */
    private final Map<UUID, String> playerArenaMap = new HashMap<>();

    /** Server version, resolved in enable(). */
    private ServerVersion serverVersion;

    /**
     * Cached legacy flag — {@code true} on pre-1.13 servers where blocks
     * carry a {@code byte} data value instead of a {@code BlockData}.
     * Resolved once in {@link #enable()}.
     */
    private boolean legacy;

    /**
     * In-game presentation layer for the chatty "AI thinking" persona:
     * action-bar guesses, occasional chat thoughts, sidebar scoreboard,
     * tab list, and triumph title on a correct guess. Initialised lazily
     * in {@link #enable()} so service constructors stay context-free.
     */
    private FeedbackController feedback;

    // ── PluginService lifecycle ───────────────────────────────────────

    @Override
    public void enable() {
        serverVersion = plugin.getContext().getServerVersion();
        // Pre-1.13 blocks use byte data values; 1.13+ uses BlockData strings.
        this.legacy = !serverVersion.isNewerThanOrEquals(ServerVersion.V_1_13);
        this.feedback = new FeedbackController(plugin);
        plugin.getPluginLogger().info("GameManager enabled.");
    }

    /**
     * Shuts down all active sessions: cancels timers, restores all players,
     * clears zones. Ensures clean state after server stop or plugin reload.
     */
    @Override
    public void shutdown() {
        for (GameSession session : new ArrayList<>(sessions.values()))
            forceEndSession(session);
        sessions.clear();
        playerArenaMap.clear();
        plugin.getPluginLogger().debug("GameManager shut down.");
    }

    // ── BBAIGameManager API ───────────────────────────────────────────

    @Override
    public boolean joinArena(Player player, String arenaName) {
        // Use the joining player's own preferred language for all replies in
        // this method. Broadcasts (which go to the whole lobby) use per-recipient
        // lang via broadcastLocalized.
        Lang lang = plugin.getContext().getConfigService().getLangFor(player.getUniqueId());
        BBAIMessageService msg = plugin.getContext().getMessageService();

        // Already in a game?
        if (playerArenaMap.containsKey(player.getUniqueId())) {
            msg.sendChat(player, lang.get("game.join.already-in-game"));
            return false;
        }

        // Arena exists and is enabled?
        Arena arena = plugin.getContext().getArenaManager().getArena(arenaName);
        if (arena == null) {
            msg.sendChat(player, lang.get("game.join.arena-not-found", "%arena%", arenaName));
            return false;
        }
        if (!arena.enabled()) {
            msg.sendChat(player, lang.get("game.join.arena-disabled", "%arena%", arenaName));
            return false;
        }

        // Get or create session
        GameSession session = sessions.get(arenaName);
        if (session == null) {
            session = new GameSession(arena);
            sessions.put(arenaName, session);
        }

        // Check state allows joining
        if (session.state() != ArenaState.WAITING && session.state() != ArenaState.COUNTDOWN) {
            msg.sendChat(player, lang.get("game.join.arena-in-progress", "%arena%", arenaName));
            return false;
        }

        // Check not full
        if (session.players().size() >= arena.maxPlayers()) {
            msg.sendChat(player, lang.get("game.join.arena-full", "%arena%", arenaName));
            return false;
        }

        // Find available plot
        int plotIndex = session.findAvailablePlot();
        if (plotIndex < 0) {
            msg.sendChat(player, lang.get("game.join.arena-full", "%arena%", arenaName));
            return false;
        }

        // Capture snapshot before any state changes
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player, serverVersion);

        // Create game player and register
        GamePlayer gp = new GamePlayer(player.getUniqueId(), player.getName(),
                plotIndex, snapshot, arena.buildTime());
        session.addPlayer(gp);
        playerArenaMap.put(player.getUniqueId(), arenaName);

        // Prepare player for lobby
        prepareForLobby(player, arena);

        // Notify
        msg.sendChat(player, lang.get("game.join.success", "%arena%", arenaName));
        broadcastLocalized(session, "game.join.broadcast",
                "%player%", player.getName(),
                "%current%", String.valueOf(session.players().size()),
                "%max%", String.valueOf(arena.maxPlayers()));

        // Paint the lobby scoreboard + tab list for the new player. Other
        // already-in-lobby players get their players-count line refreshed
        // by the feedback layer too (it iterates session.players() internally
        // via repaintPlayersLineForOthers on each new join).
        if (feedback != null) {
            feedback.onPlayerJoinedWaiting(session, player.getUniqueId());
            // Refresh existing players' "Players: N/M" so the count visibly
            // ticks up the moment someone joins.
            for (GamePlayer other : session.players().values())
                if (!other.playerId().equals(player.getUniqueId()))
                    feedback.onPlayerJoinedWaiting(session, other.playerId());
        }

        // Check if we should start countdown
        if (session.state() == ArenaState.WAITING
                && session.players().size() >= arena.minPlayers())
            startCountdown(session);
        else if (session.state() == ArenaState.WAITING) {
            int needed = arena.minPlayers() - session.players().size();
            broadcastLocalized(session, "game.waiting.player-needed",
                    "%needed%", String.valueOf(needed));
        }

        plugin.getPluginLogger().info("Player '%s' joined arena '%s'.", player.getName(), arenaName);
        return true;
    }

    @Override
    public boolean leaveArena(Player player) {
        String arenaName = playerArenaMap.get(player.getUniqueId());
        if (arenaName == null)
            return false;

        GameSession session = sessions.get(arenaName);
        if (session == null) {
            playerArenaMap.remove(player.getUniqueId());
            return false;
        }

        GamePlayer gp = session.removePlayer(player.getUniqueId());
        playerArenaMap.remove(player.getUniqueId());

        if (gp == null)
            return false;

        // Drop the per-player feedback visuals (scoreboard, tab overlay).
        // Safe even if the session never reached PLAYING — endSession was a no-op there.
        if (feedback != null)
            feedback.playerLeft(arenaName, player.getUniqueId());

        // Restore player state
        gp.snapshot().restore(player, serverVersion);

        // Broadcast leave message — per recipient lang.
        broadcastLocalized(session, "game.leave.broadcast",
                "%player%", player.getName(),
                "%current%", String.valueOf(session.players().size()),
                "%max%", String.valueOf(session.arena().maxPlayers()));

        // State-dependent cleanup
        handlePlayerLeaveState(session);

        plugin.getPluginLogger().info("Player '%s' left arena '%s'.", player.getName(), arenaName);
        return true;
    }

    @Override
    public boolean isInGame(UUID playerId) {
        return playerArenaMap.containsKey(playerId);
    }

    @Override
    public String getPlayerArena(UUID playerId) {
        return playerArenaMap.get(playerId);
    }

    @Override
    public ArenaState getArenaState(String arenaName) {
        GameSession session = sessions.get(arenaName);
        return session != null ? session.state() : ArenaState.WAITING;
    }

    @Override
    public int getPlayerCount(String arenaName) {
        GameSession session = sessions.get(arenaName);
        return session != null ? session.players().size() : 0;
    }

    // ── countdown phase ───────────────────────────────────────────────

    /**
     * Starts the countdown to game start. Sends countdown messages
     * every second and transitions to PLAYING when it reaches 0.
     */
    private void startCountdown(GameSession session) {
        session.state(ArenaState.COUNTDOWN);
        final int[] countdown = {session.arena().countdownTime()};

        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Check if session is still in COUNTDOWN (might have been cancelled)
            if (session.state() != ArenaState.COUNTDOWN) {
                Bukkit.getScheduler().cancelTask(session.countdownTaskId());
                session.countdownTaskId(-1);
                return;
            }

            if (countdown[0] <= 0) {
                Bukkit.getScheduler().cancelTask(session.countdownTaskId());
                session.countdownTaskId(-1);
                startGame(session);
                return;
            }

            broadcastLocalized(session, "game.countdown.starting",
                    "%seconds%", String.valueOf(countdown[0]));
            // Refresh every scoreboard + tab to show the current countdown
            // second. The feedback layer paints the COUNTDOWN layout (which
            // differs from WAITING in the bottom-line: "Starts in: 5s" vs.
            // "Need 2 more"). Cheap — same call rate as the existing chat.
            if (feedback != null)
                feedback.onCountdownTick(session, countdown[0]);
            // Audible tick on the last few seconds.
            if (countdown[0] <= 5)
                for (GamePlayer gp : session.players().values()) {
                    Player p = Bukkit.getPlayer(gp.playerId());
                    if (p != null)
                        SoundPalette.TICK_URGENT.play(p);
                }
            countdown[0]--;
        }, 0L, 20L).getTaskId();

        session.countdownTaskId(taskId);
    }

    /**
     * Cancels an active countdown and returns to WAITING state.
     */
    private void cancelCountdown(GameSession session) {
        if (session.countdownTaskId() != -1) {
            Bukkit.getScheduler().cancelTask(session.countdownTaskId());
            session.countdownTaskId(-1);
        }
        session.state(ArenaState.WAITING);
        broadcastLocalized(session, "game.countdown.cancelled");
        // Repaint scoreboards back to WAITING layout.
        if (feedback != null)
            feedback.onCountdownCancelled(session);
    }

    // ── game start ────────────────────────────────────────────────────

    /**
     * Starts the active game phase: fetches themes, teleports players to
     * plots, assigns initial themes, and starts game tick + render timers.
     */
    private void startGame(GameSession session) {
        session.state(ArenaState.PLAYING);
        Arena arena = session.arena();

        // Fetch theme list from ML service or use fallback
        List<String> themes = fetchThemes();
        session.setThemes(themes);
        session.gameTimeRemaining(arena.gameTime());

        broadcastLocalized(session, "game.countdown.go");

        World arenaWorld = ensureWorldLoaded(arena);

        // Set up each player
        for (GamePlayer gp : session.players().values()) {
            Player player = Bukkit.getPlayer(gp.playerId());
            if (player == null)
                continue;

            Arena.PlotData plot = arena.plots().get(gp.plotIndex());
            Location spawnLoc = toLocation(arenaWorld, plot.spawn());

            player.setGameMode(GameMode.CREATIVE);
            player.teleport(spawnLoc);
            gp.resetBuildTime(arena.buildTime());
            gp.clearZoneDirty();

            // Install the per-plot mirror that will be updated by Bukkit
            // block events for the rest of this session.
            session.installMirror(gp.plotIndex(),
                    MutablePlotScene.forPlot(plot, legacy));

            // Per-player language lookup — each builder sees the theme and
            // skip feather in their preferred language.
            Lang playerLang = plugin.getContext().getConfigService().getLangFor(gp.playerId());
            String theme = session.getTheme(gp.themeIndex());
            plugin.getContext().getMessageService().sendChat(player,
                    playerLang.get("game.playing.theme-assigned", "%theme%", theme));

            // Hand out the skip-theme feather to slot 8 (last hotbar slot)
            // if the feature is enabled by config. The session's feedback
            // bundle was set up when the player joined the lobby, so the
            // config check uses the same snapshot.
            if (feedback != null && feedback.skipFeatherEnabledFor(arena.name())) {
                ItemStack skipItem = SkipThemeItem.create(playerLang);
                player.getInventory().setItem(SkipThemeItem.HOTBAR_SLOT, skipItem);
            }
        }

        // Start game tick timer (every second)
        startGameTickTimer(session);

        // Transition the in-game feedback layer from WAITING/COUNTDOWN to
        // PLAYING. Must happen AFTER themes are assigned so the initial
        // PLAYING-mode scoreboard paint has correct values.
        feedback.startPlayingPhase(session);

        // Register session with the centralized evaluation pipeline. The
        // callback fires for EVERY ML evaluation (not only matches), so we
        // can surface the AI's "thinking out loud" to the builder. Matches
        // additionally run the scoring branch.
        plugin.getContext().getEvaluationService().registerSession(
                session,
                (playerId, themeIndex, topK, matched) -> {
                    if (matched)
                        handleScore(session.arena().name(), playerId, themeIndex);
                    feedback.onEvaluated(session.arena().name(),
                            playerId, topK, matched);
                });

        plugin.getPluginLogger().info("Game started in arena '%s' with %d player(s).",
                arena.name(), session.players().size());
    }

    /**
     * Fetches the theme list directly from the ML service's registered class
     * names. Falls back to the built-in theme set if the ML service didn't
     * register any classes (e.g. failed to load the ONNX model on startup).
     */
    private List<String> fetchThemes() {
        BBAIMLService ml = plugin.getContext().getMlService();
        List<String> classes = ml.classNames();
        List<String> themes = classes.isEmpty()
                ? new ArrayList<>(FALLBACK_THEMES)
                : new ArrayList<>(classes);
        Collections.shuffle(themes);
        return themes;
    }

    // ── game tick timer ───────────────────────────────────────────────

    /**
     * Starts the per-second game tick timer that manages:
     * <ul>
     *     <li>Global game time countdown</li>
     *     <li>Per-player build time countdown and theme rotation</li>
     *     <li>Time warning messages</li>
     * </ul>
     */
    private void startGameTickTimer(GameSession session) {
        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (session.state() != ArenaState.PLAYING)
                return;

            // Global timer
            session.gameTimeRemaining(session.gameTimeRemaining() - 1);

            // Refresh scoreboard time fields each second — without this the
            // builder would only see timer updates every ~5 s (ML cadence).
            feedback.onTick(session);

            // Game time warnings
            int remaining = session.gameTimeRemaining();
            if (remaining == 60 || remaining == 30 || remaining == 10)
                broadcastLocalized(session, "game.playing.game-time-warning",
                        "%minutes%", String.valueOf(remaining / 60),
                        "%seconds%", String.valueOf(remaining % 60));

            if (remaining <= 0) {
                endGame(session);
                return;
            }

            // Per-player build timers. Each player gets their own lang lookup.
            Arena arena = session.arena();
            for (GamePlayer gp : new ArrayList<>(session.players().values())) {
                gp.decrementBuildTime();
                Lang playerLang = plugin.getContext().getConfigService()
                        .getLangFor(gp.playerId());

                // Build time warnings (last 10s, 30s, 60s)
                if (gp.buildTimeRemaining() == 30 || gp.buildTimeRemaining() == 10) {
                    Player player = Bukkit.getPlayer(gp.playerId());
                    if (player != null)
                        plugin.getContext().getMessageService().sendChat(player,
                                playerLang.get("game.playing.build-time-warning",
                                        "%seconds%", String.valueOf(gp.buildTimeRemaining())));
                }

                if (gp.buildTimeRemaining() <= 0) {
                    // Build time expired
                    Player player = Bukkit.getPlayer(gp.playerId());
                    World arenaWorld = ensureWorldLoaded(arena);
                    if (arenaWorld != null)
                        clearZone(arenaWorld, arena.plots().get(gp.plotIndex()));
                    // Mirror is session-scoped: wipe it so the next render-tick
                    // doesn't keep showing the expired build to the ML model.
                    // GAME-11: clearAll() must not skip the advanceTheme/reset
                    // pair — the world zone is already cleared above, so the
                    // per-player counters MUST advance to keep state consistent.
                    MutablePlotScene m = session.mirror(gp.plotIndex());
                    if (m != null) {
                        try {
                            m.clearAll();
                        } catch (Throwable t) {
                            plugin.getPluginLogger().error(
                                    "mirror.clearAll() failed for arena %s player %s: %s",
                                    arena.name(), gp.playerId(), t.getMessage());
                        }
                    }
                    gp.clearZoneDirty();
                    gp.advanceTheme(session.themes().size());
                    gp.resetBuildTime(arena.buildTime());
                    if (player != null) {
                        plugin.getContext().getMessageService().sendChat(player,
                                playerLang.get("game.playing.build-time-expired"));
                        String newTheme = session.getTheme(gp.themeIndex());
                        plugin.getContext().getMessageService().sendChat(player,
                                playerLang.get("game.playing.new-theme", "%theme%", newTheme));
                    }
                    // Repaint scoreboard theme/score for the new round.
                    feedback.onThemeOrScoreChanged(session, gp.playerId());
                }
            }
        }, 20L, 20L).getTaskId();

        session.gameTickTaskId(taskId);
    }

    // ── score handling ────────────────────────────────────────────────

    /**
     * Handles a successful ML match on the main thread. Verifies the
     * player is still in the session and on the same theme before
     * applying the score.
     * <p>
     * Invoked by the {@code EvaluationService} score callback (already
     * marshalled to the Bukkit main thread).
     */
    private void handleScore(String arenaName, UUID playerId, int expectedThemeIndex) {
        GameSession session = sessions.get(arenaName);
        if (session == null || session.state() != ArenaState.PLAYING)
            return;

        GamePlayer gp = session.getPlayer(playerId);
        if (gp == null)
            return;

        // Verify the player is still on the same theme (not stale result)
        if (gp.themeIndex() != expectedThemeIndex)
            return;

        Player player = Bukkit.getPlayer(playerId);
        if (player == null)
            return;

        Arena arena = session.arena();
        Lang lang = plugin.getContext().getConfigService().getLangFor(playerId);

        // Score!
        gp.incrementScore();

        // Clear zone
        World arenaWorld = ensureWorldLoaded(arena);
        if (arenaWorld != null)
            clearZone(arenaWorld, arena.plots().get(gp.plotIndex()));
        gp.clearZoneDirty();

        // World is wiped — sync the mirror to match so the next render-tick
        // sees an empty scene without re-capturing from the world.
        MutablePlotScene mirror = session.mirror(gp.plotIndex());
        if (mirror != null)
            mirror.clearAll();

        // Advance theme
        gp.advanceTheme(session.themes().size());
        gp.resetBuildTime(arena.buildTime());

        // Notify player. The triumph title + sound + arena broadcast are
        // produced by the FeedbackController (invoked right after this
        // method via the same evaluation callback) — so we keep only the
        // baseline "new-theme" line in chat here.
        plugin.getContext().getMessageService().sendChat(player,
                lang.get("game.playing.score",
                        "%score%", String.valueOf(gp.score())));
        String newTheme = session.getTheme(gp.themeIndex());
        plugin.getContext().getMessageService().sendChat(player,
                lang.get("game.playing.new-theme", "%theme%", newTheme));

        // Repaint the scoreboard's theme + score lines for this player so the
        // sidebar reflects the new round immediately instead of after the next
        // ML tick.
        feedback.onThemeOrScoreChanged(session, playerId);
    }

    // ── skip theme ────────────────────────────────────────────────────

    /**
     * Public API entry point — players invoke this through the skip-theme
     * feather (slot 8). Clears the player's build zone, wipes the per-plot
     * mirror, resets the build timer, advances the theme, and refreshes the
     * scoreboard. No-op if the player is not in a PLAYING session.
     */
    @Override
    public boolean skipTheme(@NonNull Player player) {
        String arenaName = playerArenaMap.get(player.getUniqueId());
        if (arenaName == null)
            return false;
        GameSession session = sessions.get(arenaName);
        if (session == null || session.state() != ArenaState.PLAYING)
            return false;
        GamePlayer gp = session.getPlayer(player.getUniqueId());
        if (gp == null)
            return false;

        Arena arena = session.arena();
        World arenaWorld = ensureWorldLoaded(arena);
        if (arenaWorld != null)
            clearZone(arenaWorld, arena.plots().get(gp.plotIndex()));
        MutablePlotScene m = session.mirror(gp.plotIndex());
        if (m != null)
            m.clearAll();

        gp.clearZoneDirty();
        gp.advanceTheme(session.themes().size());
        gp.resetBuildTime(arena.buildTime());

        Lang lang = plugin.getContext().getConfigService().getLangFor(player.getUniqueId());
        // Pick a random "theme skipped" line from lang for variety. Falls back
        // to a literal message when the lang list is empty.
        String feedbackLine = feedback != null
                ? feedback.pickSkipFeedback(player.getUniqueId(), arenaName)
                : null;
        if (feedbackLine == null)
            feedbackLine = lang.get("game.playing.skipped");
        plugin.getContext().getMessageService().sendChat(player, feedbackLine);
        String newTheme = session.getTheme(gp.themeIndex());
        plugin.getContext().getMessageService().sendChat(player,
                lang.get("game.playing.new-theme", "%theme%", newTheme));

        SoundPalette.SKIP_THEME.play(player);
        if (feedback != null)
            feedback.onThemeOrScoreChanged(session, gp.playerId());
        return true;
    }

    // ── game end ──────────────────────────────────────────────────────

    /**
     * Ends the game: cancels timers, clears zones, teleports players
     * to spectator, announces results, then schedules player restoration.
     */
    private void endGame(GameSession session) {
        // Detach from the evaluation pipeline before we start tearing the
        // session down so no in-flight render/ML job races with restoration.
        plugin.getContext().getEvaluationService().unregisterSession(session.arena().name());
        // Pull down the feedback layer (scoreboards, tab list overlays).
        feedback.endSession(session.arena().name());
        session.cancelAllTasks();
        session.state(ArenaState.ENDING);

        Arena arena = session.arena();
        World arenaWorld = ensureWorldLoaded(arena);

        // Clear all zones
        if (arenaWorld != null)
            for (GamePlayer gp : session.players().values())
                clearZone(arenaWorld, arena.plots().get(gp.plotIndex()));

        // Mirrors are session-scoped — drop them now that the world is wiped.
        session.clearMirrors();

        // Teleport all to spectator and set Adventure mode
        Arena.Position specPos = arena.effectiveSpectator();
        Location specLoc = arenaWorld != null ? toLocation(arenaWorld, specPos) : null;
        for (GamePlayer gp : session.players().values()) {
            Player player = Bukkit.getPlayer(gp.playerId());
            if (player == null)
                continue;
            player.setGameMode(GameMode.ADVENTURE);
            if (specLoc != null)
                player.teleport(specLoc);
        }

        // Announce results — per-recipient localization.
        broadcastLocalized(session, "game.ending.time-up");
        broadcastLocalized(session, "game.ending.results-header");

        // Sort players by score (descending)
        List<GamePlayer> sorted = new ArrayList<>(session.players().values());
        sorted.sort((a, b) -> Integer.compare(b.score(), a.score()));

        // Display results
        for (int i = 0; i < sorted.size(); i++) {
            GamePlayer gp = sorted.get(i);
            broadcastLocalized(session, "game.ending.result-entry",
                    "%position%", String.valueOf(i + 1),
                    "%player%", gp.playerName(),
                    "%score%", String.valueOf(gp.score()));
        }

        // Announce winner
        if (sorted.isEmpty() || sorted.get(0).score() == 0)
            broadcastLocalized(session, "game.ending.no-winner");
        else {
            int topScore = sorted.get(0).score();
            List<String> winners = new ArrayList<>();
            for (GamePlayer gp : sorted)
                if (gp.score() == topScore)
                    winners.add(gp.playerName());

            if (winners.size() == 1)
                broadcastLocalized(session, "game.ending.winner",
                        "%player%", winners.get(0),
                        "%score%", String.valueOf(topScore));
            else
                broadcastLocalized(session, "game.ending.draw",
                        "%players%", joinNames(winners));
        }

        broadcastLocalized(session, "game.ending.returning",
                "%seconds%", "10");

        // Schedule restoration after 10 seconds
        int endTaskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            restoreAllPlayers(session);
            session.state(ArenaState.WAITING);
            sessions.remove(session.arena().name());
            plugin.getPluginLogger().info("Game ended in arena '%s'.", session.arena().name());
        }, ENDING_DURATION_TICKS).getTaskId();

        session.endingTaskId(endTaskId);
    }

    /**
     * Forces immediate end of a session without results display.
     * Used during shutdown to clean up all sessions.
     */
    private void forceEndSession(GameSession session) {
        // Detach from the evaluation pipeline before tearing the session down
        // so no in-flight render/ML job tries to score a session being wiped.
        plugin.getContext().getEvaluationService().unregisterSession(session.arena().name());
        // Tear down feedback visuals even when forced (no results display).
        if (feedback != null)
            feedback.endSession(session.arena().name());
        session.cancelAllTasks();

        Arena arena = session.arena();
        World arenaWorld = ensureWorldLoaded(arena);

        // Clear all zones
        if (arenaWorld != null)
            for (GamePlayer gp : session.players().values())
                clearZone(arenaWorld, arena.plots().get(gp.plotIndex()));

        // Mirrors are session-scoped — drop them now that the world is wiped.
        session.clearMirrors();

        // Restore all players immediately
        for (GamePlayer gp : new ArrayList<>(session.players().values())) {
            playerArenaMap.remove(gp.playerId());
            Player player = Bukkit.getPlayer(gp.playerId());
            if (player != null)
                gp.snapshot().restore(player, serverVersion);
        }
        session.players().clear();
        session.state(ArenaState.WAITING);
    }

    /**
     * Restores all players in a session to their pre-game state and
     * removes them from tracking.
     */
    private void restoreAllPlayers(GameSession session) {
        for (GamePlayer gp : new ArrayList<>(session.players().values())) {
            playerArenaMap.remove(gp.playerId());
            Player player = Bukkit.getPlayer(gp.playerId());
            if (player != null)
                gp.snapshot().restore(player, serverVersion);
        }
        session.players().clear();
    }

    // ── state transition helpers ──────────────────────────────────────

    /**
     * Handles state transitions when a player leaves mid-game.
     */
    private void handlePlayerLeaveState(GameSession session) {
        int count = session.players().size();
        Arena arena = session.arena();

        switch (session.state()) {
            case COUNTDOWN:
                if (count < arena.minPlayers())
                    cancelCountdown(session);
                break;
            case PLAYING:
                if (count == 0) {
                    // All left — skip ending, clean up immediately. Detach
                    // from the evaluation pipeline first so it stops scanning
                    // this (now empty) session.
                    plugin.getContext().getEvaluationService().unregisterSession(arena.name());
                    if (feedback != null)
                        feedback.endSession(arena.name());
                    session.cancelAllTasks();
                    World arenaWorld = ensureWorldLoaded(arena);
                    if (arenaWorld != null)
                        for (int i = 0; i < arena.plots().size(); i++)
                            clearZone(arenaWorld, arena.plots().get(i));
                    session.state(ArenaState.WAITING);
                    sessions.remove(arena.name());
                    plugin.getPluginLogger().info("All players left arena '%s', game cancelled.",
                            arena.name());
                }
                break;
            case ENDING:
                if (count == 0) {
                    session.cancelAllTasks();
                    session.state(ArenaState.WAITING);
                    sessions.remove(arena.name());
                }
                break;
            default:
                // WAITING — check if session should be removed (empty)
                if (count == 0)
                    sessions.remove(arena.name());
                break;
        }
    }

    // ── utility helpers ───────────────────────────────────────────────

    /**
     * Prepares a player for the lobby: clears inventory, effects,
     * sets Adventure mode, full health/food, teleports to lobby.
     */
    @SuppressWarnings("deprecation")
    private void prepareForLobby(Player player, Arena arena) {
        // Clear inventory and effects
        player.getInventory().clear();
        player.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
        for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects())
            player.removePotionEffect(effect.getType());

        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        player.setExp(0f);
        player.setLevel(0);
        player.setAllowFlight(false);
        player.setFlying(false);

        // Teleport to lobby
        World arenaWorld = ensureWorldLoaded(arena);
        if (arenaWorld != null) {
            Location lobbyLoc = toLocation(arenaWorld, arena.lobby());
            player.teleport(lobbyLoc);
        }
    }

    /**
     * Clears all blocks within a plot's zone to AIR.
     * MUST run on the main thread.
     */
    private void clearZone(World world, Arena.PlotData plot) {
        int minX = Math.min(plot.corner1X(), plot.corner2X());
        int maxX = Math.max(plot.corner1X(), plot.corner2X());
        int minY = Math.min(plot.corner1Y(), plot.corner2Y());
        int maxY = Math.max(plot.corner1Y(), plot.corner2Y());
        int minZ = Math.min(plot.corner1Z(), plot.corner2Z());
        int maxZ = Math.max(plot.corner1Z(), plot.corner2Z());
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    world.getBlockAt(x, y, z).setType(Material.AIR);
    }

    /**
     * Ensures the arena world is loaded, creating it if needed.
     *
     * @return the loaded world, or {@code null} on failure
     */
    private World ensureWorldLoaded(Arena arena) {
        World world = Bukkit.getWorld(arena.worldName());
        if (world != null)
            return world;
        world = plugin.getContext().getWorldService().loadWorld(arena.worldName());
        if (world == null)
            world = plugin.getContext().getWorldService().createEmptyWorld(arena.worldName());
        return world;
    }

    /**
     * Broadcasts a chat message to all online players in a session. Uses a
     * pre-rendered string (already localised), so every recipient sees the
     * same text. For broadcasts that depend on per-player language, use
     * {@link #broadcastLocalized(GameSession, String, Object...)} instead.
     */
    private void broadcastToSession(GameSession session, String message) {
        BBAIMessageService msg = plugin.getContext().getMessageService();
        for (GamePlayer gp : session.players().values()) {
            Player player = Bukkit.getPlayer(gp.playerId());
            if (player != null)
                msg.sendChat(player, message);
        }
    }

    /**
     * Broadcasts a localised chat message to all online players in a session,
     * rendering the message in EACH recipient's own preferred language. Use
     * for broadcasts that contain translatable text. Replacements are passed
     * straight to {@link Lang#get(String, Object...)}.
     *
     * @param session      the target session
     * @param langKey      the lang key to render per-player
     * @param replacements alternating placeholder/value pairs
     */
    private void broadcastLocalized(GameSession session, String langKey, Object... replacements) {
        BBAIMessageService msg = plugin.getContext().getMessageService();
        for (GamePlayer gp : session.players().values()) {
            Player player = Bukkit.getPlayer(gp.playerId());
            if (player == null)
                continue;
            Lang langFor = plugin.getContext().getConfigService().getLangFor(gp.playerId());
            msg.sendChat(player, langFor.get(langKey, replacements));
        }
    }

    /**
     * Converts an {@link Arena.Position} to a Bukkit {@link Location}.
     */
    private static Location toLocation(World world, Arena.Position pos) {
        return new Location(world, pos.x(), pos.y(), pos.z(), pos.yaw(), pos.pitch());
    }

    /**
     * Joins a list of names with commas and "and" for the last element.
     */
    private static String joinNames(List<String> names) {
        if (names.size() == 1)
            return names.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0)
                sb.append(i == names.size() - 1 ? " & " : ", ");
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    /**
     * Returns the plot index for a player in a given arena session.
     * Used by {@link ru.ashesha.buildBattleAI.listeners.GameListener}
     * for zone bounds checking.
     *
     * @param playerId  the player UUID
     * @param arenaName the arena name
     * @return 0-based plot index, or -1 if not found
     */
    public int getPlayerPlotIndex(UUID playerId, String arenaName) {
        GameSession session = sessions.get(arenaName);
        if (session == null)
            return -1;
        GamePlayer gp = session.getPlayer(playerId);
        return gp != null ? gp.plotIndex() : -1;
    }

    /**
     * Marks a player's build zone as dirty (block placed/broken).
     * Used by {@link ru.ashesha.buildBattleAI.listeners.GameListener}
     * to notify the render pipeline.
     *
     * @param playerId  the player UUID
     * @param arenaName the arena name
     */
    public void markPlayerZoneDirty(UUID playerId, String arenaName) {
        GameSession session = sessions.get(arenaName);
        if (session == null)
            return;
        GamePlayer gp = session.getPlayer(playerId);
        if (gp != null)
            gp.markZoneDirty();
    }

    /**
     * Checks whether the given location is within a plot's build zone.
     *
     * @param x    block X
     * @param y    block Y
     * @param z    block Z
     * @param plot the plot data
     * @return {@code true} if the location is inside the zone
     */
    public static boolean isInZone(int x, int y, int z, Arena.PlotData plot) {
        int minX = Math.min(plot.corner1X(), plot.corner2X());
        int maxX = Math.max(plot.corner1X(), plot.corner2X());
        int minY = Math.min(plot.corner1Y(), plot.corner2Y());
        int maxY = Math.max(plot.corner1Y(), plot.corner2Y());
        int minZ = Math.min(plot.corner1Z(), plot.corner2Z());
        int maxZ = Math.max(plot.corner1Z(), plot.corner2Z());
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /**
     * Called by {@link ru.ashesha.buildBattleAI.listeners.GameListener} after
     * a {@code BlockPlaceEvent} (or multi-place) has passed the zone check.
     * Updates the per-plot mirror so the next render reflects the placement.
     * <p>
     * No-op if the player has no session, no mirror, or the block is out of
     * the plot bounds (the mirror does its own bounds check).
     *
     * @param playerId    the player UUID
     * @param arenaName   the arena name
     * @param placedBlock the block whose state was placed
     */
    @SuppressWarnings("deprecation")
    public void applyMirrorPlace(@NonNull UUID playerId,
                                 @NonNull String arenaName,
                                 @NonNull Block placedBlock) {
        GameSession session = sessions.get(arenaName);
        if (session == null)
            return;
        GamePlayer gp = session.getPlayer(playerId);
        if (gp == null)
            return;
        MutablePlotScene mirror = session.mirror(gp.plotIndex());
        if (mirror == null)
            return;

        XMaterial mat = XMaterial.matchXMaterial(placedBlock.getType());
        int x = placedBlock.getX(), y = placedBlock.getY(), z = placedBlock.getZ();
        // Block.getData() is deprecated on modern Bukkit but valid on 1.8–1.12.
        if (legacy)
            mirror.setBlock(x, y, z, mat, placedBlock.getData());
        else
            mirror.setBlock(x, y, z, mat, placedBlock.getBlockData().getAsString());
    }

    /**
     * Called by {@link ru.ashesha.buildBattleAI.listeners.GameListener} after
     * a {@code BlockBreakEvent} has passed the zone check.
     *
     * @param playerId    the player UUID
     * @param arenaName   the arena name
     * @param brokenBlock the block that was broken
     */
    public void applyMirrorBreak(@NonNull UUID playerId,
                                 @NonNull String arenaName,
                                 @NonNull Block brokenBlock) {
        GameSession session = sessions.get(arenaName);
        if (session == null)
            return;
        GamePlayer gp = session.getPlayer(playerId);
        if (gp == null)
            return;
        MutablePlotScene mirror = session.mirror(gp.plotIndex());
        if (mirror == null)
            return;
        mirror.clearBlock(brokenBlock.getX(), brokenBlock.getY(), brokenBlock.getZ());
    }
}
