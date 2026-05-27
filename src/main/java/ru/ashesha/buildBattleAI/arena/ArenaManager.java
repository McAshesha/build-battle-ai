package ru.ashesha.buildBattleAI.arena;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.arena.api.BBAIArenaManager;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.config.api.Lang;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.entity.hologram.HologramService;
import ru.ashesha.buildBattleAI.entity.hologram.api.BBAIHologramService;
import ru.ashesha.buildBattleAI.entity.npc.NPCService;
import ru.ashesha.buildBattleAI.entity.npc.api.BBAINPCService;
import ru.ashesha.buildBattleAI.message.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.message.micro.ChatMicroService;
import ru.ashesha.buildBattleAI.util.SoundPalette;

import java.util.*;

/**
 * Default implementation of {@link BBAIArenaManager}.
 * <p>
 * Manages the full arena lifecycle: loading from YAML on startup with
 * strict validation, interactive non-linear creation via a panel-based
 * wizard with visual feedback (titles, holograms, camera NPCs), runtime
 * state tracking, and deletion with world cleanup.
 * <p>
 * Arena worlds follow the naming convention {@code bbai_<arena_name>}
 * and are created as void worlds via
 * {@link ru.ashesha.buildBattleAI.world.api.BBAIWorldService}.
 * <p>
 * Each arena plot has 3 camera positions for multi-angle AI classification.
 * During setup, positions are marked with floating holograms and camera
 * angles are shown as NPCs facing the render direction.
 * <p>
 * Implements both {@link BBAIArenaManager} (the public API contract) and
 * {@link PluginService} (the internal lifecycle contract) independently.
 */
@RequiredArgsConstructor
public class ArenaManager implements BBAIArenaManager, PluginService {

    /** World name prefix for arena worlds, avoiding conflicts with user worlds. */
    private static final String WORLD_PREFIX = "bbai_";

    /** Skin texture for camera marker NPCs (camera-themed head). */
    private static final String CAMERA_NPC_TEXTURE =
            "ewogICJ0aW1lc3RhbXAiIDogMTc3NzMwOTMwMTUyOCwKICAicHJvZmlsZUlkIiA6ICJmYWM3NWQzMzNkNmU0Mzk3ODczOWViNmQwNmE1ZWIzMiIsCiAgInByb2ZpbGVOYW1lIiA6ICJncmV5Q09DTyIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS81MWQ4NjI2ZjI2YmE5YTI5ZGY4OTFhZjU3YTMyZTUxZDg5YjRhMjY1N2JjZWMyMGMzYjg4MDNjYjE2MzJjZWFhIgogICAgfQogIH0KfQ==";

    /** Signature for the camera marker NPC skin. */
    private static final String CAMERA_NPC_SIGNATURE =
            "bmFela0q8ePgOCJrDp0SwYG5qgAibwyKeGsE/EAq48ZbMqI8AMt4b9+TsEIMmzaU2CaNbU5hv3qb4FUs/rjuejyRFJ+vDCieBTLpz7CTC7W2555gJ1XRWktoXqGGkTeZeTtOdkna8a7rOr1H5oqeguvBTLSy+V0EtI6dESe5tyzWJXkEuV6Z6+HPCfcUMwttIMFSpUQwVxDiVX2TibcEMo/uYEK13nRVJDIZ3nuSFpbUcZ5q1ljQKrQzKuZBnENigGf6hEfO5WdMyaZfTYVr4ZGTj7c88bbQ2Nnz6iojr1PEqgKgFbQFM3bA8ApFAboTR5NtguEPgpSwbj2ASfRBu0qZxOaqnHi09hKRo8R+eFVC4Wwy2wFboCwwg3kxyoMXBMHL99FSwBHKl2t2VS2ncM8OfUd9syvs/wVhkFuM8HpfjVt8GRvI81920diC2KLVceZwlRWS9kcZFg/64MJYISzi6XJ2gqst8hf8FMBvXVIPu/pFtghB9ERnrNMdIXw2d5F/oV388fNBTEHGix2CA6KfRNpv+ViciXpxTFyPGKZ3PWKkFgtrDjYY4PQyopS7p79TARrEEn/swjN/CDC9NES+FLyRQ3L6amr9prbCw/AesgMwxYKToAf5jT/I9G1qH5C2kw3C78z9CT3h2p19ZMGahYgEpCifvhZ5IOVMFfA=";

    /** Hologram offset above the marked position (blocks). */
    private static final double HOLOGRAM_Y_OFFSET = 2.0;

    /** Title fade-in duration in ticks. */
    private static final int TITLE_FADE_IN = 5;

    /** Title stay duration in ticks. */
    private static final int TITLE_STAY = 30;

    /** Title fade-out duration in ticks. */
    private static final int TITLE_FADE_OUT = 10;

    /** The plugin instance. */
    @NonNull
    private final BuildBattleAI plugin;

    /** Loaded arenas keyed by name. Insertion order preserved for stable listing. */
    private final Map<String, Arena> arenas = new LinkedHashMap<>();

    /** Active setup wizard sessions keyed by player UUID. */
    private final Map<UUID, ArenaSetupSession> setupSessions = new HashMap<>();

    // ── PluginService lifecycle ────────────────────────────────────────

    /**
     * Loads all arena definitions from YAML configs with strict validation
     * and loads the worlds for enabled arenas.
     */
    @Override
    public void enable() {
        loadArenas();
        plugin.getPluginLogger().info("ArenaManager enabled (%d arena(s) loaded).", arenas.size());
    }

    /**
     * Cancels any active setup sessions (cleaning up temporary worlds)
     * and clears the in-memory arena registry.
     */
    @Override
    public void shutdown() {
        for (ArenaSetupSession session : new ArrayList<>(setupSessions.values()))
            cancelSetupInternal(session, false);
        setupSessions.clear();
        arenas.clear();
        plugin.getPluginLogger().debug("ArenaManager shut down.");
    }

    // ── arena loading & serialization ──────────────────────────────────

    /**
     * Loads all arena configs, validates them, and loads worlds for
     * enabled arenas. Invalid configs are skipped with error logging.
     */
    private void loadArenas() {
        BBAIConfigService configService = plugin.getContext().getConfigService();
        for (String name : configService.getArenaNames()) {
            YamlConfiguration config = configService.getArenaConfig(name);
            Arena arena = deserializeArena(name, config);
            if (arena == null)
                continue;
            arenas.put(name, arena);
            if (arena.enabled()) {
                World world = plugin.getContext().getWorldService().loadWorld(arena.worldName());
                if (world == null)
                    world = plugin.getContext().getWorldService().createEmptyWorld(arena.worldName());
                if (world != null)
                    plugin.getPluginLogger().info("Loaded arena '%s' (world: %s).", name, arena.worldName());
                else
                    plugin.getPluginLogger().warn("Failed to load world for arena '%s'.", name);
            }
        }
    }

    /**
     * Deserializes an arena from YAML with strict validation.
     * All required fields must be present; missing fields are collected
     * and logged as errors. Returns {@code null} if any validation fails.
     * <p>
     * Each plot requires 3 camera positions ({@code camera1}, {@code camera2},
     * {@code camera3}) for multi-angle AI classification.
     *
     * @param name   the arena name
     * @param config the YAML config
     * @return the validated arena, or {@code null} if invalid
     */
    private Arena deserializeArena(String name, YamlConfiguration config) {
        if (config == null)
            return null;

        List<String> errors = new ArrayList<>();

        // ── validate required global fields ────────────────────────────
        String worldName = config.getString("world", WORLD_PREFIX + name);
        int maxPlayers = config.getInt("max-players", -1);
        if (maxPlayers < 2 || maxPlayers > 8)
            errors.add("'max-players' must be between 2 and 8 (got " + maxPlayers + ")");

        Arena.Position lobby = readPosition(config, "lobby");
        if (lobby == null)
            errors.add("missing 'lobby'");

        // ── validate per-plot required fields ──────────────────────────
        List<Arena.PlotData> plots = new ArrayList<>();
        if (maxPlayers >= 2)
            for (int i = 1; i <= maxPlayers; i++) {
                String p = "plots." + i;
                Arena.Position spawn = readPosition(config, p + ".spawn");
                if (spawn == null)
                    errors.add("missing '" + p + ".spawn'");
                if (!config.contains(p + ".corner1.x"))
                    errors.add("missing '" + p + ".corner1'");
                if (!config.contains(p + ".corner2.x"))
                    errors.add("missing '" + p + ".corner2'");

                // 3 cameras per plot
                List<Arena.Position> cameras = new ArrayList<>();
                boolean allCamerasPresent = true;
                for (int c = 1; c <= 3; c++) {
                    Arena.Position cam = readPosition(config, p + ".camera" + c);
                    if (cam == null) {
                        errors.add("missing '" + p + ".camera" + c + "'");
                        allCamerasPresent = false;
                    } else
                        cameras.add(cam);
                }

                // Only build plot if all its fields are present
                if (spawn != null && config.contains(p + ".corner1.x")
                        && config.contains(p + ".corner2.x") && allCamerasPresent)
                    plots.add(new Arena.PlotData(
                            spawn,
                            config.getInt(p + ".corner1.x"), config.getInt(p + ".corner1.y"),
                            config.getInt(p + ".corner1.z"),
                            config.getInt(p + ".corner2.x"), config.getInt(p + ".corner2.y"),
                            config.getInt(p + ".corner2.z"),
                            cameras
                    ));
            }

        // ── report errors ──────────────────────────────────────────────
        if (!errors.isEmpty()) {
            for (String error : errors)
                plugin.getPluginLogger().error("Arena '%s': %s", name, error);
            plugin.getPluginLogger().error(
                    "Arena '%s' will not be activated due to configuration errors.", name);
            return null;
        }

        // ── read optional fields with defaults ─────────────────────────
        Arena.Position spectator = readPosition(config, "spectator");
        int minPlayers = config.getInt("min-players", 2);
        int buildTime = config.getInt("build-time", 150);
        int gameTime = config.getInt("game-time", 300);
        int countdownTime = config.getInt("countdown", 5);
        boolean enabled = config.getBoolean("enabled", true);

        return new Arena(name, worldName, maxPlayers, enabled, lobby, spectator,
                minPlayers, buildTime, gameTime, countdownTime, plots);
    }

    /**
     * Reads a {@link Arena.Position} from a YAML path.
     *
     * @param config the config
     * @param path   the base path (e.g. "lobby", "plots.1.spawn")
     * @return the position, or {@code null} if the path does not exist
     */
    private Arena.Position readPosition(YamlConfiguration config, String path) {
        if (!config.contains(path + ".x"))
            return null;
        return new Arena.Position(
                config.getDouble(path + ".x"),
                config.getDouble(path + ".y"),
                config.getDouble(path + ".z"),
                (float) config.getDouble(path + ".yaw", 0.0),
                (float) config.getDouble(path + ".pitch", 0.0)
        );
    }

    /**
     * Serializes an arena to YAML and saves to disk.
     * Each plot's 3 cameras are stored as {@code camera1}, {@code camera2},
     * {@code camera3} sub-paths.
     */
    private void serializeArena(Arena arena) {
        BBAIConfigService configService = plugin.getContext().getConfigService();
        YamlConfiguration config = configService.getArenaConfig(arena.name());
        if (config == null)
            config = configService.createArenaConfig(arena.name());

        config.set("world", arena.worldName());
        config.set("max-players", arena.maxPlayers());
        config.set("enabled", arena.enabled());
        config.set("min-players", arena.minPlayers());
        config.set("build-time", arena.buildTime());
        config.set("game-time", arena.gameTime());
        config.set("countdown", arena.countdownTime());

        writePosition(config, "lobby", arena.lobby());
        if (arena.spectator() != null)
            writePosition(config, "spectator", arena.spectator());

        for (int i = 0; i < arena.plots().size(); i++) {
            Arena.PlotData plot = arena.plots().get(i);
            String p = "plots." + (i + 1);
            writePosition(config, p + ".spawn", plot.spawn());
            config.set(p + ".corner1.x", plot.corner1X());
            config.set(p + ".corner1.y", plot.corner1Y());
            config.set(p + ".corner1.z", plot.corner1Z());
            config.set(p + ".corner2.x", plot.corner2X());
            config.set(p + ".corner2.y", plot.corner2Y());
            config.set(p + ".corner2.z", plot.corner2Z());
            List<Arena.Position> cameras = plot.cameras();
            for (int c = 0; c < cameras.size(); c++)
                writePosition(config, p + ".camera" + (c + 1), cameras.get(c));
        }

        configService.saveArenaConfig(arena.name());
    }

    /**
     * Writes a position to a YAML path.
     */
    private void writePosition(YamlConfiguration config, String path, Arena.Position pos) {
        config.set(path + ".x", pos.x());
        config.set(path + ".y", pos.y());
        config.set(path + ".z", pos.z());
        config.set(path + ".yaw", (double) pos.yaw());
        config.set(path + ".pitch", (double) pos.pitch());
    }

    // ── BBAIArenaManager: query ────────────────────────────────────────

    @Override
    public Arena getArena(String name) {
        return arenas.get(name);
    }

    @Override
    public Set<String> getArenaNames() {
        return Collections.unmodifiableSet(arenas.keySet());
    }

    @Override
    public Collection<Arena> getArenas() {
        return Collections.unmodifiableCollection(arenas.values());
    }

    @Override
    public boolean isArenaLoaded(String name) {
        Arena arena = arenas.get(name);
        return arena != null && arena.enabled();
    }

    // ── BBAIArenaManager: setup wizard ─────────────────────────────────

    @Override
    public void startSetup(@NonNull Player player, @NonNull String arenaName) {
        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        BBAIMessageService msg = plugin.getContext().getMessageService();

        if (hasSetupSession(player.getUniqueId())) {
            msg.sendChat(player, lang.get("arena.setup.already-in-setup"));
            return;
        }
        if (arenas.containsKey(arenaName) ||
                plugin.getContext().getConfigService().getArenaConfig(arenaName) != null) {
            msg.sendChat(player, lang.get("arena.setup.already-exists", "%arena%", arenaName));
            return;
        }

        String worldName = WORLD_PREFIX + arenaName;
        World world = plugin.getContext().getWorldService().createEmptyWorld(worldName);
        if (world == null) {
            msg.sendChat(player, lang.get("arena.setup.world-failed", "%arena%", arenaName));
            return;
        }

        Location loc = player.getLocation();
        ArenaSetupSession session = new ArenaSetupSession(
                player.getUniqueId(), arenaName, worldName,
                loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch(), player.getAllowFlight()
        );
        setupSessions.put(player.getUniqueId(), session);

        player.teleport(new Location(world, 0.5, 65, 0.5));
        player.setAllowFlight(true);
        player.setFlying(true);

        // Welcome title overlay
        msg.sendTitle(player,
                lang.get("arena.setup.title.welcome"),
                lang.get("arena.setup.title.welcome-sub"),
                TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
        SoundPalette.WELCOME.play(player);

        sendSetupPanel(player, session);
    }

    @Override
    public boolean hasSetupSession(UUID playerId) {
        return setupSessions.containsKey(playerId);
    }

    @Override
    public void handleSetPlayers(@NonNull Player player, int count) {
        ArenaSetupSession session = setupSessions.get(player.getUniqueId());
        if (session == null)
            return;

        // Despawn visual markers for plots that will be trimmed
        if (session.maxPlayers() != null && count < session.maxPlayers())
            for (int i = count + 1; i <= session.maxPlayers(); i++) {
                ArenaSetupSession.PlotSetupData plot = session.plots().get(i);
                if (plot != null)
                    cleanupPlotMarkers(player, plot);
            }

        session.maxPlayers(count);
        session.trimPlotsAbove(count);

        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        plugin.getContext().getMessageService().sendTitle(player,
                lang.get("arena.setup.title.players"),
                lang.get("arena.setup.title.players-sub", "%count%", String.valueOf(count)),
                TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
        SoundPalette.SELECT.play(player);

        sendSetupPanel(player, session);
    }

    @Override
    public void handleSetLobby(@NonNull Player player) {
        ArenaSetupSession session = setupSessions.get(player.getUniqueId());
        if (session == null)
            return;

        // Despawn previous lobby hologram if present
        if (session.lobbyHologram() != null)
            plugin.getContext().getHologramService().despawn(player, session.lobbyHologram());

        session.lobby(positionFromPlayer(player));

        // Spawn hologram marker at the lobby position
        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        session.lobbyHologram(spawnMarkerHologram(player,
                lang.get("arena.setup.marker.lobby"), session.lobby()));

        plugin.getContext().getMessageService().sendTitle(player,
                lang.get("arena.setup.title.lobby"),
                lang.get("arena.setup.title.position-saved"),
                TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
        SoundPalette.CONFIRM.play(player);

        sendSetupPanel(player, session);
    }

    @Override
    public void handleSetSpectator(@NonNull Player player) {
        ArenaSetupSession session = setupSessions.get(player.getUniqueId());
        if (session == null)
            return;

        if (session.spectatorHologram() != null)
            plugin.getContext().getHologramService().despawn(player, session.spectatorHologram());

        session.spectator(positionFromPlayer(player));

        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        session.spectatorHologram(spawnMarkerHologram(player,
                lang.get("arena.setup.marker.spectator"), session.spectator()));

        plugin.getContext().getMessageService().sendTitle(player,
                lang.get("arena.setup.title.spectator"),
                lang.get("arena.setup.title.position-saved"),
                TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
        SoundPalette.CONFIRM.play(player);

        sendSetupPanel(player, session);
    }

    @Override
    public void handleSetSpawn(@NonNull Player player, int plotIndex) {
        ArenaSetupSession session = setupSessions.get(player.getUniqueId());
        if (session == null || !isValidPlot(session, plotIndex))
            return;

        ArenaSetupSession.PlotSetupData plot = session.getOrCreatePlot(plotIndex);

        if (plot.spawnHologram() != null)
            plugin.getContext().getHologramService().despawn(player, plot.spawnHologram());

        plot.spawn(positionFromPlayer(player));

        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        String idx = String.valueOf(plotIndex);
        plot.spawnHologram(spawnMarkerHologram(player,
                lang.get("arena.setup.marker.spawn", "%index%", idx), plot.spawn()));

        plugin.getContext().getMessageService().sendTitle(player,
                lang.get("arena.setup.title.spawn", "%index%", idx),
                lang.get("arena.setup.title.position-saved"),
                TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
        SoundPalette.CONFIRM.play(player);

        sendSetupPanel(player, session);
    }

    @Override
    public void handleSetCorner1(@NonNull Player player, int plotIndex) {
        ArenaSetupSession session = setupSessions.get(player.getUniqueId());
        if (session == null || !isValidPlot(session, plotIndex))
            return;

        ArenaSetupSession.PlotSetupData plot = session.getOrCreatePlot(plotIndex);

        if (plot.corner1Hologram() != null)
            plugin.getContext().getHologramService().despawn(player, plot.corner1Hologram());

        Location loc = player.getLocation();
        plot.corner1(new int[]{loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()});

        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        String idx = String.valueOf(plotIndex);
        plot.corner1Hologram(spawnCornerMarkerHologram(player,
                lang.get("arena.setup.marker.corner1", "%index%", idx), plot.corner1()));

        plugin.getContext().getMessageService().sendTitle(player,
                lang.get("arena.setup.title.corner", "%index%", idx),
                lang.get("arena.setup.title.corner1-saved"),
                TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
        SoundPalette.CONFIRM.play(player);

        sendSetupPanel(player, session);
    }

    @Override
    public void handleSetCorner2(@NonNull Player player, int plotIndex) {
        ArenaSetupSession session = setupSessions.get(player.getUniqueId());
        if (session == null || !isValidPlot(session, plotIndex))
            return;

        ArenaSetupSession.PlotSetupData plot = session.getOrCreatePlot(plotIndex);

        if (plot.corner2Hologram() != null)
            plugin.getContext().getHologramService().despawn(player, plot.corner2Hologram());

        Location loc = player.getLocation();
        plot.corner2(new int[]{loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()});

        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        String idx = String.valueOf(plotIndex);
        plot.corner2Hologram(spawnCornerMarkerHologram(player,
                lang.get("arena.setup.marker.corner2", "%index%", idx), plot.corner2()));

        plugin.getContext().getMessageService().sendTitle(player,
                lang.get("arena.setup.title.corner", "%index%", idx),
                lang.get("arena.setup.title.corner2-saved"),
                TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
        SoundPalette.CONFIRM.play(player);

        sendSetupPanel(player, session);
    }

    @Override
    public void handleSetCamera(@NonNull Player player, int plotIndex, int cameraIndex) {
        ArenaSetupSession session = setupSessions.get(player.getUniqueId());
        if (session == null || !isValidPlot(session, plotIndex))
            return;
        if (cameraIndex < 1 || cameraIndex > 3)
            return;

        ArenaSetupSession.PlotSetupData plot = session.getOrCreatePlot(plotIndex);
        Arena.Position camPos = positionFromPlayer(player);

        // Despawn previous NPC for this camera slot
        NPCService.NPC oldNpc = getCameraNpc(plot, cameraIndex);
        if (oldNpc != null)
            plugin.getContext().getNpcService().despawn(player, oldNpc);

        // Store the camera position
        setCameraPosition(plot, cameraIndex, camPos);

        // Spawn a camera NPC marker facing the same direction as the player
        BBAINPCService npcService = plugin.getContext().getNpcService();
        NPCService.NPC npc = npcService.createNPC("", CAMERA_NPC_TEXTURE, CAMERA_NPC_SIGNATURE);
        Location npcLoc = toLocation(player.getWorld(), camPos);
        npcService.spawn(player, npc, npcLoc);
        setCameraNpc(plot, cameraIndex, npc);

        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        String idx = String.valueOf(plotIndex);
        String cam = String.valueOf(cameraIndex);
        plugin.getContext().getMessageService().sendTitle(player,
                lang.get("arena.setup.title.camera", "%index%", idx),
                lang.get("arena.setup.title.camera-saved", "%camera%", cam),
                TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
        SoundPalette.CONFIRM_ALT.play(player);

        sendSetupPanel(player, session);
    }

    @Override
    public void handleSetMinPlayers(@NonNull Player player, int count) {
        ArenaSetupSession session = setupSessions.get(player.getUniqueId());
        if (session == null)
            return;

        session.minPlayers(count);

        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        plugin.getContext().getMessageService().sendTitle(player,
                lang.get("arena.setup.title.minplayers"),
                lang.get("arena.setup.title.minplayers-sub", "%count%", String.valueOf(count)),
                TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
        SoundPalette.CONFIRM.play(player);

        sendSetupPanel(player, session);
    }

    @Override
    public void handleSetBuildTime(@NonNull Player player, int seconds) {
        ArenaSetupSession session = setupSessions.get(player.getUniqueId());
        if (session == null)
            return;

        session.buildTime(seconds);

        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        plugin.getContext().getMessageService().sendTitle(player,
                lang.get("arena.setup.title.buildtime"),
                lang.get("arena.setup.title.buildtime-sub", "%minutes%", fmt(seconds / 60.0)),
                TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
        SoundPalette.CONFIRM.play(player);

        sendSetupPanel(player, session);
    }

    @Override
    public void handleSetGameTime(@NonNull Player player, int seconds) {
        ArenaSetupSession session = setupSessions.get(player.getUniqueId());
        if (session == null)
            return;

        session.gameTime(seconds);

        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        plugin.getContext().getMessageService().sendTitle(player,
                lang.get("arena.setup.title.gametime"),
                lang.get("arena.setup.title.gametime-sub", "%minutes%", fmt(seconds / 60.0)),
                TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
        SoundPalette.CONFIRM.play(player);

        sendSetupPanel(player, session);
    }

    @Override
    public void handleSetCountdown(@NonNull Player player, int seconds) {
        ArenaSetupSession session = setupSessions.get(player.getUniqueId());
        if (session == null)
            return;

        session.countdownTime(seconds);

        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        plugin.getContext().getMessageService().sendTitle(player,
                lang.get("arena.setup.title.countdown"),
                lang.get("arena.setup.title.countdown-sub", "%seconds%", String.valueOf(seconds)),
                TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
        SoundPalette.CONFIRM.play(player);

        sendSetupPanel(player, session);
    }

    @Override
    public void handleConfirm(@NonNull Player player) {
        ArenaSetupSession session = setupSessions.get(player.getUniqueId());
        if (session == null)
            return;

        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        BBAIMessageService msg = plugin.getContext().getMessageService();

        if (!session.isComplete()) {
            msg.sendChat(player, lang.get("arena.setup.incomplete"));
            msg.sendTitle(player,
                    lang.get("arena.setup.title.incomplete"),
                    lang.get("arena.setup.title.incomplete-sub"),
                    TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
            SoundPalette.DENY.play(player);
            return;
        }

        // Build plot data with 3 cameras each
        List<Arena.PlotData> plots = new ArrayList<>();
        for (int i = 1; i <= session.maxPlayers(); i++) {
            ArenaSetupSession.PlotSetupData pd = session.plots().get(i);
            List<Arena.Position> cameras = Arrays.asList(
                    pd.camera1(), pd.camera2(), pd.camera3());
            plots.add(new Arena.PlotData(
                    pd.spawn(),
                    pd.corner1()[0], pd.corner1()[1], pd.corner1()[2],
                    pd.corner2()[0], pd.corner2()[1], pd.corner2()[2],
                    cameras
            ));
        }

        int minPlayers = session.minPlayers() != null ? session.minPlayers() : 2;
        int buildTime = session.buildTime() != null ? session.buildTime() : 150;
        int gameTime = session.gameTime() != null ? session.gameTime() : 300;
        int countdownTime = session.countdownTime() != null ? session.countdownTime() : 5;

        Arena arena = new Arena(session.arenaName(), session.worldName(),
                session.maxPlayers(), true, session.lobby(), session.spectator(),
                minPlayers, buildTime, gameTime, countdownTime, plots);
        arenas.put(arena.name(), arena);
        serializeArena(arena);

        // Clean up visual markers before returning the player
        cleanupVisualMarkers(player, session);
        returnPlayer(player, session);
        setupSessions.remove(player.getUniqueId());

        msg.sendChat(player, lang.get("arena.setup.created", "%arena%", arena.name()));
        msg.sendTitle(player,
                lang.get("arena.setup.title.created"),
                lang.get("arena.setup.title.created-sub", "%arena%", arena.name()),
                TITLE_FADE_IN, 40, TITLE_FADE_OUT);
        SoundPalette.CELEBRATE.play(player);
    }

    @Override
    public void handleCancel(@NonNull Player player) {
        ArenaSetupSession session = setupSessions.get(player.getUniqueId());
        if (session == null)
            return;
        cancelSetupInternal(session, true);
    }

    @Override
    public void cancelSetupSession(UUID playerId) {
        ArenaSetupSession session = setupSessions.get(playerId);
        if (session != null)
            cancelSetupInternal(session, false);
    }

    // ── BBAIArenaManager: management ───────────────────────────────────

    @Override
    public void deleteArena(@NonNull String name) {
        Arena arena = arenas.remove(name);
        if (arena != null)
            plugin.getContext().getWorldService().deleteWorld(arena.worldName());
        plugin.getContext().getConfigService().deleteArenaConfig(name);
    }

    // ── internal helpers ───────────────────────────────────────────────

    /**
     * Cancels a setup session: cleans up visual markers, deletes the
     * temporary world, and optionally returns the player with feedback.
     */
    private void cancelSetupInternal(ArenaSetupSession session, boolean sendMessage) {
        setupSessions.remove(session.playerId());

        // Clean up visual markers while the player is still in the arena world
        Player player = Bukkit.getPlayer(session.playerId());
        if (player != null)
            cleanupVisualMarkers(player, session);

        plugin.getContext().getWorldService().deleteWorld(session.worldName());

        if (sendMessage && player != null) {
            returnPlayer(player, session);
            Lang lang = plugin.getContext().getConfigService().getDefaultLang();
            BBAIMessageService msg = plugin.getContext().getMessageService();
            msg.sendChat(player, lang.get("arena.setup.cancelled"));
            msg.sendTitle(player,
                    lang.get("arena.setup.title.cancelled"),
                    lang.get("arena.setup.title.cancelled-sub"),
                    TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
            SoundPalette.DISMISS.play(player);
        }
    }

    /** Teleports the player back to their pre-setup location. */
    private void returnPlayer(Player player, ArenaSetupSession session) {
        World returnWorld = Bukkit.getWorld(session.returnWorld());
        if (returnWorld != null)
            player.teleport(new Location(returnWorld,
                    session.returnX(), session.returnY(), session.returnZ(),
                    session.returnYaw(), session.returnPitch()));
        else
            player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        player.setAllowFlight(session.wasFlying());
        if (!session.wasFlying())
            player.setFlying(false);
    }

    /** Creates an {@link Arena.Position} from the player's current location. */
    private static Arena.Position positionFromPlayer(Player player) {
        Location loc = player.getLocation();
        return new Arena.Position(loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch());
    }

    /** Validates that a plot index is within the configured player count. */
    private static boolean isValidPlot(ArenaSetupSession session, int plotIndex) {
        return session.maxPlayers() != null && plotIndex >= 1 && plotIndex <= session.maxPlayers();
    }

    /** Rounds a double to one decimal place for display. */
    private static String fmt(double value) {
        return String.valueOf(Math.round(value * 10.0) / 10.0);
    }

    // ── visual marker helpers ──────────────────────────────────────────

    /**
     * Spawns a single-line hologram marker above the given position.
     *
     * @param player the viewer
     * @param text   the hologram label (supports {@code &} color codes)
     * @param pos    the world position to mark
     * @return the spawned hologram for later cleanup
     */
    private HologramService.Hologram spawnMarkerHologram(Player player, String text,
                                                          Arena.Position pos) {
        BBAIHologramService holoService = plugin.getContext().getHologramService();
        HologramService.Hologram hologram = holoService.createHologram(1);
        Location loc = new Location(player.getWorld(),
                pos.x(), pos.y() + HOLOGRAM_Y_OFFSET, pos.z());
        holoService.spawn(player, hologram, loc, Collections.singletonList(text));
        return hologram;
    }

    /**
     * Spawns a single-line hologram marker above a block corner position.
     *
     * @param player the viewer
     * @param text   the hologram label (supports {@code &} color codes)
     * @param corner the block coordinates {@code [x, y, z]}
     * @return the spawned hologram for later cleanup
     */
    private HologramService.Hologram spawnCornerMarkerHologram(Player player, String text,
                                                                int[] corner) {
        BBAIHologramService holoService = plugin.getContext().getHologramService();
        HologramService.Hologram hologram = holoService.createHologram(1);
        Location loc = new Location(player.getWorld(),
                corner[0] + 0.5, corner[1] + HOLOGRAM_Y_OFFSET, corner[2] + 0.5);
        holoService.spawn(player, hologram, loc, Collections.singletonList(text));
        return hologram;
    }

    /**
     * Converts an {@link Arena.Position} to a Bukkit {@link Location}.
     */
    private static Location toLocation(World world, Arena.Position pos) {
        return new Location(world, pos.x(), pos.y(), pos.z(), pos.yaw(), pos.pitch());
    }

    /** Returns the camera NPC from a plot by 1-based camera index. */
    private static NPCService.NPC getCameraNpc(ArenaSetupSession.PlotSetupData plot,
                                                int cameraIndex) {
        switch (cameraIndex) {
            case 1:
                return plot.cameraNpc1();
            case 2:
                return plot.cameraNpc2();
            case 3:
                return plot.cameraNpc3();
            default:
                return null;
        }
    }

    /** Sets the camera NPC on a plot by 1-based camera index. */
    private static void setCameraNpc(ArenaSetupSession.PlotSetupData plot,
                                     int cameraIndex, NPCService.NPC npc) {
        switch (cameraIndex) {
            case 1:
                plot.cameraNpc1(npc);
                break;
            case 2:
                plot.cameraNpc2(npc);
                break;
            case 3:
                plot.cameraNpc3(npc);
                break;
        }
    }

    /** Sets the camera position on a plot by 1-based camera index. */
    private static void setCameraPosition(ArenaSetupSession.PlotSetupData plot,
                                          int cameraIndex, Arena.Position pos) {
        switch (cameraIndex) {
            case 1:
                plot.camera1(pos);
                break;
            case 2:
                plot.camera2(pos);
                break;
            case 3:
                plot.camera3(pos);
                break;
        }
    }

    /** Returns the camera position from a plot by 1-based camera index. */
    private static Arena.Position getCameraPosition(ArenaSetupSession.PlotSetupData plot,
                                                    int cameraIndex) {
        switch (cameraIndex) {
            case 1:
                return plot.camera1();
            case 2:
                return plot.camera2();
            case 3:
                return plot.camera3();
            default:
                return null;
        }
    }

    /**
     * Despawns all visual markers (holograms and NPCs) tracked by the session.
     * Safe to call when the player is still online; a no-op if the player is null.
     */
    private void cleanupVisualMarkers(Player player, ArenaSetupSession session) {
        BBAIHologramService holoService = plugin.getContext().getHologramService();

        if (session.lobbyHologram() != null)
            holoService.despawn(player, session.lobbyHologram());
        if (session.spectatorHologram() != null)
            holoService.despawn(player, session.spectatorHologram());

        for (ArenaSetupSession.PlotSetupData plot : session.plots().values())
            cleanupPlotMarkers(player, plot);
    }

    /**
     * Despawns all visual markers for a single plot (holograms and camera NPCs).
     */
    private void cleanupPlotMarkers(Player player, ArenaSetupSession.PlotSetupData plot) {
        BBAIHologramService holoService = plugin.getContext().getHologramService();
        BBAINPCService npcService = plugin.getContext().getNpcService();

        if (plot.spawnHologram() != null)
            holoService.despawn(player, plot.spawnHologram());
        if (plot.corner1Hologram() != null)
            holoService.despawn(player, plot.corner1Hologram());
        if (plot.corner2Hologram() != null)
            holoService.despawn(player, plot.corner2Hologram());
        if (plot.cameraNpc1() != null)
            npcService.despawn(player, plot.cameraNpc1());
        if (plot.cameraNpc2() != null)
            npcService.despawn(player, plot.cameraNpc2());
        if (plot.cameraNpc3() != null)
            npcService.despawn(player, plot.cameraNpc3());
    }

    // ── panel rendering ────────────────────────────────────────────────
    //
    // Every setting change re-sends the FULL panel. The panel shows all
    // settings and their current state (set / unset / optional).

    /**
     * Sends the complete setup panel to the player, reflecting the
     * current state of all settings in the session.
     */
    private void sendSetupPanel(Player player, ArenaSetupSession session) {
        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        BBAIMessageService msg = plugin.getContext().getMessageService();

        msg.sendChat(player, lang.get("arena.setup.divider"));
        msg.sendChat(player, lang.get("arena.setup.header", "%arena%", session.arenaName()));
        msg.sendChat(player, " ");

        sendPlayerCountLine(player, session, lang);
        sendPositionLine(player, lang,
                lang.get("arena.setup.lobby.label"), session.lobby(),
                "/bbai setup lobby", true,
                lang.get("arena.setup.lobby.hover-set"),
                lang.get("arena.setup.lobby.hover-change"));
        sendPositionLine(player, lang,
                lang.get("arena.setup.spectator.label"), session.spectator(),
                "/bbai setup spectator", false,
                lang.get("arena.setup.spectator.hover-set"),
                lang.get("arena.setup.spectator.hover-change"));

        // ── Optional game settings ────────────────────────────────────
        msg.sendChat(player, " ");
        sendSuggestLine(player, lang,
                lang.get("arena.setup.minplayers.label"),
                session.minPlayers() != null
                        ? lang.get("arena.setup.minplayers.value", "%count%",
                        String.valueOf(session.minPlayers()))
                        : lang.get("arena.setup.status.optional") + " &8(default: 2)",
                "/bbai setup minplayers ",
                lang.get("arena.setup.minplayers.hover"));
        sendSuggestLine(player, lang,
                lang.get("arena.setup.buildtime.label"),
                session.buildTime() != null
                        ? lang.get("arena.setup.buildtime.value", "%minutes%",
                        fmt(session.buildTime() / 60.0))
                        : lang.get("arena.setup.status.optional") + " &8(default: 2.5 min)",
                "/bbai setup buildtime ",
                lang.get("arena.setup.buildtime.hover"));
        sendSuggestLine(player, lang,
                lang.get("arena.setup.gametime.label"),
                session.gameTime() != null
                        ? lang.get("arena.setup.gametime.value", "%minutes%",
                        fmt(session.gameTime() / 60.0))
                        : lang.get("arena.setup.status.optional") + " &8(default: 5 min)",
                "/bbai setup gametime ",
                lang.get("arena.setup.gametime.hover"));
        sendSuggestLine(player, lang,
                lang.get("arena.setup.countdown.label"),
                session.countdownTime() != null
                        ? lang.get("arena.setup.countdown.value", "%seconds%",
                        String.valueOf(session.countdownTime()))
                        : lang.get("arena.setup.status.optional") + " &8(default: 5s)",
                "/bbai setup countdown ",
                lang.get("arena.setup.countdown.hover"));

        // Plot sections — only shown if player count is selected
        if (session.maxPlayers() != null) {
            for (int i = 1; i <= session.maxPlayers(); i++) {
                msg.sendChat(player, " ");
                sendPlotSection(player, session, i, lang);
            }
        } else {
            msg.sendChat(player, " ");
            msg.sendChat(player, lang.get("arena.setup.select-players-hint"));
        }

        msg.sendChat(player, " ");
        sendConfirmCancelLine(player, session, lang);
        msg.sendChat(player, lang.get("arena.setup.divider"));
    }

    /**
     * Sends the player count selector line with clickable number buttons.
     * The currently selected number is highlighted green; others are gray.
     */
    private void sendPlayerCountLine(Player player, ArenaSetupSession session, Lang lang) {
        ChatMicroService.ChatMessage line = new ChatMicroService.ChatMessage();
        line.append(lang.get("arena.setup.players.label"));
        for (int i = 2; i <= 8; i++) {
            if (i > 2)
                line.append(" ");
            boolean selected = session.maxPlayers() != null && session.maxPlayers() == i;
            String text = selected
                    ? lang.get("arena.setup.players.selected", "%count%", String.valueOf(i))
                    : lang.get("arena.setup.players.unselected", "%count%", String.valueOf(i));
            line.append(text,
                    ChatMicroService.ClickAction.RUN_COMMAND,
                    "/bbai setup players " + i,
                    lang.get("arena.setup.players.hover", "%count%", String.valueOf(i)));
        }
        plugin.getContext().getMessageService().sendChat(player, line);
    }

    /**
     * Sends a line for a setting that uses {@code SUGGEST_COMMAND} so the admin
     * types the value. Used for min players, build time, game time, countdown.
     */
    private void sendSuggestLine(Player player, Lang lang,
                                 String label, String valueDisplay,
                                 String suggestCommand, String hoverText) {
        ChatMicroService.ChatMessage line = new ChatMicroService.ChatMessage();
        line.append(label);
        line.append(valueDisplay,
                ChatMicroService.ClickAction.SUGGEST_COMMAND, suggestCommand, hoverText);
        plugin.getContext().getMessageService().sendChat(player, line);
    }

    /**
     * Sends a position setting line (lobby, spectator).
     * Shows status indicator + coordinates (if set) + [Set]/[Change] button.
     */
    private void sendPositionLine(Player player, Lang lang,
                                  String label, Arena.Position pos,
                                  String command, boolean required,
                                  String hoverSet, String hoverChange) {
        ChatMicroService.ChatMessage line = new ChatMicroService.ChatMessage();
        line.append(label);
        if (pos != null) {
            line.append(lang.get("arena.setup.status.set") + " "
                    + lang.get("arena.setup.value",
                    "%x%", fmt(pos.x()), "%y%", fmt(pos.y()), "%z%", fmt(pos.z())));
            line.append(" " + lang.get("arena.setup.btn.change"),
                    ChatMicroService.ClickAction.RUN_COMMAND, command, hoverChange);
        } else {
            line.append(required
                    ? lang.get("arena.setup.status.unset")
                    : lang.get("arena.setup.status.optional"));
            line.append(" " + lang.get("arena.setup.btn.set"),
                    ChatMicroService.ClickAction.RUN_COMMAND, command, hoverSet);
        }
        plugin.getContext().getMessageService().sendChat(player, line);
    }

    /**
     * Sends the full section for a single plot: header, spawn line,
     * zone (corners) line, cameras line (3 cameras).
     */
    private void sendPlotSection(Player player, ArenaSetupSession session, int index, Lang lang) {
        BBAIMessageService msg = plugin.getContext().getMessageService();
        ArenaSetupSession.PlotSetupData plot = session.plots().get(index);
        String idx = String.valueOf(index);

        msg.sendChat(player, lang.get("arena.setup.plot.header", "%index%", idx));

        // ── Spawn line ─────────────────────────────────────────────────
        ChatMicroService.ChatMessage spawnLine = new ChatMicroService.ChatMessage();
        spawnLine.append(lang.get("arena.setup.plot.spawn.label"));
        Arena.Position spawn = plot != null ? plot.spawn() : null;
        if (spawn != null) {
            spawnLine.append(lang.get("arena.setup.status.set") + " "
                    + lang.get("arena.setup.value",
                    "%x%", fmt(spawn.x()), "%y%", fmt(spawn.y()), "%z%", fmt(spawn.z())));
            spawnLine.append(" " + lang.get("arena.setup.btn.change"),
                    ChatMicroService.ClickAction.RUN_COMMAND, "/bbai setup spawn " + index,
                    lang.get("arena.setup.plot.spawn.hover-change", "%index%", idx));
        } else {
            spawnLine.append(lang.get("arena.setup.status.unset"));
            spawnLine.append(" " + lang.get("arena.setup.btn.set"),
                    ChatMicroService.ClickAction.RUN_COMMAND, "/bbai setup spawn " + index,
                    lang.get("arena.setup.plot.spawn.hover-set", "%index%", idx));
        }
        msg.sendChat(player, spawnLine);

        // ── Zone (corners) line ────────────────────────────────────────
        ChatMicroService.ChatMessage zoneLine = new ChatMicroService.ChatMessage();
        zoneLine.append(lang.get("arena.setup.plot.zone.label"));

        int[] c1 = plot != null ? plot.corner1() : null;
        if (c1 != null) {
            zoneLine.append(lang.get("arena.setup.status.set") + " "
                    + lang.get("arena.setup.corner.value",
                    "%x%", String.valueOf(c1[0]), "%y%", String.valueOf(c1[1]),
                    "%z%", String.valueOf(c1[2])));
            zoneLine.append(" " + lang.get("arena.setup.plot.zone.c1"),
                    ChatMicroService.ClickAction.RUN_COMMAND, "/bbai setup corner1 " + index,
                    lang.get("arena.setup.plot.zone.c1-hover-change", "%index%", idx));
        } else {
            zoneLine.append(lang.get("arena.setup.status.unset"));
            zoneLine.append(" " + lang.get("arena.setup.plot.zone.c1"),
                    ChatMicroService.ClickAction.RUN_COMMAND, "/bbai setup corner1 " + index,
                    lang.get("arena.setup.plot.zone.c1-hover-set", "%index%", idx));
        }

        zoneLine.append("  ");

        int[] c2 = plot != null ? plot.corner2() : null;
        if (c2 != null) {
            zoneLine.append(lang.get("arena.setup.status.set") + " "
                    + lang.get("arena.setup.corner.value",
                    "%x%", String.valueOf(c2[0]), "%y%", String.valueOf(c2[1]),
                    "%z%", String.valueOf(c2[2])));
            zoneLine.append(" " + lang.get("arena.setup.plot.zone.c2"),
                    ChatMicroService.ClickAction.RUN_COMMAND, "/bbai setup corner2 " + index,
                    lang.get("arena.setup.plot.zone.c2-hover-change", "%index%", idx));
        } else {
            zoneLine.append(lang.get("arena.setup.status.unset"));
            zoneLine.append(" " + lang.get("arena.setup.plot.zone.c2"),
                    ChatMicroService.ClickAction.RUN_COMMAND, "/bbai setup corner2 " + index,
                    lang.get("arena.setup.plot.zone.c2-hover-set", "%index%", idx));
        }
        msg.sendChat(player, zoneLine);

        // ── Cameras line (3 cameras per plot) ──────────────────────────
        ChatMicroService.ChatMessage cameraLine = new ChatMicroService.ChatMessage();
        cameraLine.append(lang.get("arena.setup.plot.cameras.label"));
        for (int c = 1; c <= 3; c++) {
            if (c > 1)
                cameraLine.append("  ");
            Arena.Position camPos = plot != null ? getCameraPosition(plot, c) : null;
            String cam = String.valueOf(c);
            if (camPos != null) {
                cameraLine.append(lang.get("arena.setup.status.set") + " ");
                cameraLine.append(
                        lang.get("arena.setup.plot.cameras.set", "%camera%", cam),
                        ChatMicroService.ClickAction.RUN_COMMAND,
                        "/bbai setup camera" + c + " " + index,
                        lang.get("arena.setup.plot.cameras.hover-change",
                                "%camera%", cam, "%index%", idx));
            } else {
                cameraLine.append(lang.get("arena.setup.status.unset") + " ");
                cameraLine.append(
                        lang.get("arena.setup.plot.cameras.unset", "%camera%", cam),
                        ChatMicroService.ClickAction.RUN_COMMAND,
                        "/bbai setup camera" + c + " " + index,
                        lang.get("arena.setup.plot.cameras.hover-set",
                                "%camera%", cam, "%index%", idx));
            }
        }
        msg.sendChat(player, cameraLine);
    }

    /**
     * Sends the confirm/cancel line. Confirm is green when all required
     * settings are filled, gray otherwise. Always clickable.
     */
    private void sendConfirmCancelLine(Player player, ArenaSetupSession session, Lang lang) {
        ChatMicroService.ChatMessage line = new ChatMicroService.ChatMessage();
        line.append("  ");
        if (session.isComplete())
            line.append(lang.get("arena.setup.confirm.ready"),
                    ChatMicroService.ClickAction.RUN_COMMAND, "/bbai setup confirm",
                    lang.get("arena.setup.confirm.hover-ready"));
        else
            line.append(lang.get("arena.setup.confirm.not-ready"),
                    ChatMicroService.ClickAction.RUN_COMMAND, "/bbai setup confirm",
                    lang.get("arena.setup.confirm.hover-not-ready"));
        line.append(" ");
        line.append(lang.get("arena.setup.cancel.btn"),
                ChatMicroService.ClickAction.RUN_COMMAND, "/bbai setup cancel",
                lang.get("arena.setup.cancel.hover"));
        plugin.getContext().getMessageService().sendChat(player, line);
    }
}
