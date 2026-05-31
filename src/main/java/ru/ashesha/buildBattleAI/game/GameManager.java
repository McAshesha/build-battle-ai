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
import ru.ashesha.buildBattleAI.arena.ArenaManager;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.config.api.Lang;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.game.api.BBAIGameManager;
import ru.ashesha.buildBattleAI.message.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

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

    // ── PluginService lifecycle ───────────────────────────────────────

    @Override
    public void enable() {
        serverVersion = plugin.getContext().getServerVersion();
        // Pre-1.13 blocks use byte data values; 1.13+ uses BlockData strings.
        this.legacy = !serverVersion.isNewerThanOrEquals(ServerVersion.V_1_13);
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
        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
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
        broadcastToSession(session, lang.get("game.join.broadcast",
                "%player%", player.getName(),
                "%current%", String.valueOf(session.players().size()),
                "%max%", String.valueOf(arena.maxPlayers())));

        // Check if we should start countdown
        if (session.state() == ArenaState.WAITING
                && session.players().size() >= arena.minPlayers())
            startCountdown(session);
        else if (session.state() == ArenaState.WAITING) {
            int needed = arena.minPlayers() - session.players().size();
            broadcastToSession(session, lang.get("game.waiting.player-needed",
                    "%needed%", String.valueOf(needed)));
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

        // Restore player state
        gp.snapshot().restore(player, serverVersion);

        Lang lang = plugin.getContext().getConfigService().getDefaultLang();

        // Broadcast leave message
        broadcastToSession(session, lang.get("game.leave.broadcast",
                "%player%", player.getName(),
                "%current%", String.valueOf(session.players().size()),
                "%max%", String.valueOf(session.arena().maxPlayers())));

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

            Lang lang = plugin.getContext().getConfigService().getDefaultLang();
            broadcastToSession(session, lang.get("game.countdown.starting",
                    "%seconds%", String.valueOf(countdown[0])));
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
        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        broadcastToSession(session, lang.get("game.countdown.cancelled"));
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

        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        broadcastToSession(session, lang.get("game.countdown.go"));

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

            String theme = session.getTheme(gp.themeIndex());
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("game.playing.theme-assigned", "%theme%", theme));
        }

        // Start game tick timer (every second)
        startGameTickTimer(session);

        // Register session with the centralized evaluation pipeline. From now
        // on, render + ML inference for this arena is coordinated by the
        // EvaluationService (bounded queues, ML batching, per-player cadence).
        // The score callback is marshalled back to the Bukkit main thread by
        // the service, so calling handleScore directly is safe.
        plugin.getContext().getEvaluationService().registerSession(
                session,
                (playerId, themeIndex) -> handleScore(session.arena().name(), playerId, themeIndex));

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

            // Game time warnings
            int remaining = session.gameTimeRemaining();
            if (remaining == 60 || remaining == 30 || remaining == 10) {
                Lang lang = plugin.getContext().getConfigService().getDefaultLang();
                broadcastToSession(session, lang.get("game.playing.game-time-warning",
                        "%minutes%", String.valueOf(remaining / 60),
                        "%seconds%", String.valueOf(remaining % 60)));
            }

            if (remaining <= 0) {
                endGame(session);
                return;
            }

            // Per-player build timers
            Lang lang = plugin.getContext().getConfigService().getDefaultLang();
            Arena arena = session.arena();
            for (GamePlayer gp : new ArrayList<>(session.players().values())) {
                gp.decrementBuildTime();

                // Build time warnings (last 10s, 30s, 60s)
                if (gp.buildTimeRemaining() == 30 || gp.buildTimeRemaining() == 10) {
                    Player player = Bukkit.getPlayer(gp.playerId());
                    if (player != null)
                        plugin.getContext().getMessageService().sendChat(player,
                                lang.get("game.playing.build-time-warning",
                                        "%seconds%", String.valueOf(gp.buildTimeRemaining())));
                }

                if (gp.buildTimeRemaining() <= 0) {
                    // Build time expired
                    Player player = Bukkit.getPlayer(gp.playerId());
                    World arenaWorld = ensureWorldLoaded(arena);
                    if (arenaWorld != null)
                        clearZone(arenaWorld, arena.plots().get(gp.plotIndex()));
                    gp.clearZoneDirty();
                    gp.advanceTheme(session.themes().size());
                    gp.resetBuildTime(arena.buildTime());
                    if (player != null) {
                        plugin.getContext().getMessageService().sendChat(player,
                                lang.get("game.playing.build-time-expired"));
                        String newTheme = session.getTheme(gp.themeIndex());
                        plugin.getContext().getMessageService().sendChat(player,
                                lang.get("game.playing.new-theme", "%theme%", newTheme));
                    }
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
        Lang lang = plugin.getContext().getConfigService().getDefaultLang();

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

        // Notify player
        plugin.getContext().getMessageService().sendChat(player,
                lang.get("game.playing.score",
                        "%score%", String.valueOf(gp.score())));
        String newTheme = session.getTheme(gp.themeIndex());
        plugin.getContext().getMessageService().sendChat(player,
                lang.get("game.playing.new-theme", "%theme%", newTheme));
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
        session.cancelAllTasks();
        session.state(ArenaState.ENDING);

        Arena arena = session.arena();
        World arenaWorld = ensureWorldLoaded(arena);
        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        BBAIMessageService msg = plugin.getContext().getMessageService();

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

        // Announce results
        broadcastToSession(session, lang.get("game.ending.time-up"));
        broadcastToSession(session, lang.get("game.ending.results-header"));

        // Sort players by score (descending)
        List<GamePlayer> sorted = new ArrayList<>(session.players().values());
        Collections.sort(sorted, new Comparator<GamePlayer>() {
            @Override
            public int compare(GamePlayer a, GamePlayer b) {
                return Integer.compare(b.score(), a.score());
            }
        });

        // Display results
        for (int i = 0; i < sorted.size(); i++) {
            GamePlayer gp = sorted.get(i);
            broadcastToSession(session, lang.get("game.ending.result-entry",
                    "%position%", String.valueOf(i + 1),
                    "%player%", gp.playerName(),
                    "%score%", String.valueOf(gp.score())));
        }

        // Announce winner
        if (sorted.isEmpty() || sorted.get(0).score() == 0) {
            broadcastToSession(session, lang.get("game.ending.no-winner"));
        } else {
            int topScore = sorted.get(0).score();
            List<String> winners = new ArrayList<>();
            for (GamePlayer gp : sorted)
                if (gp.score() == topScore)
                    winners.add(gp.playerName());

            if (winners.size() == 1)
                broadcastToSession(session, lang.get("game.ending.winner",
                        "%player%", winners.get(0),
                        "%score%", String.valueOf(topScore)));
            else
                broadcastToSession(session, lang.get("game.ending.draw",
                        "%players%", joinNames(winners)));
        }

        broadcastToSession(session, lang.get("game.ending.returning",
                "%seconds%", "10"));

        // Schedule restoration after 10 seconds
        int endTaskId = Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                restoreAllPlayers(session);
                session.state(ArenaState.WAITING);
                sessions.remove(session.arena().name());
                plugin.getPluginLogger().info("Game ended in arena '%s'.", session.arena().name());
            }
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
     * Broadcasts a chat message to all online players in a session.
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
        if (legacy) {
            // Block.getData() is deprecated on modern Bukkit but valid on 1.8–1.12.
            //noinspection deprecation
            mirror.setBlock(x, y, z, mat, placedBlock.getData());
        } else {
            mirror.setBlock(x, y, z, mat, placedBlock.getBlockData().getAsString());
        }
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
