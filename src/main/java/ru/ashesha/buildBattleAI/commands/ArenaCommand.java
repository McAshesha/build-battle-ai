package ru.ashesha.buildBattleAI.commands;

import lombok.NonNull;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.arena.api.BBAIArenaManager;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.config.api.Lang;
import ru.ashesha.buildBattleAI.data.api.BBAIDataService;
import ru.ashesha.buildBattleAI.data.api.PlayerData;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats;
import ru.ashesha.buildBattleAI.game.ArenaState;
import ru.ashesha.buildBattleAI.game.api.BBAIGameManager;
import ru.ashesha.buildBattleAI.message.micro.ChatMicroService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Main plugin command ({@code /bbai}) for arena management.
 * <p>
 * Public subcommands (visible in tab completion):
 * <ul>
 *     <li>{@code create <name>} — starts the interactive arena setup wizard
 *         (hidden in tab completion while the player is already in a game)</li>
 *     <li>{@code list} — displays all configured arenas with game status</li>
 *     <li>{@code delete <name>} — permanently removes an arena and its world</li>
 *     <li>{@code join <arena>} — joins a game in the specified arena
 *         (hidden in tab completion while the player is already in a game)</li>
 *     <li>{@code leave} — leaves the current game (hidden in tab completion
 *         while the player is not in any game)</li>
 *     <li>{@code lang [code]} — shows or switches the player's UI language</li>
 *     <li>{@code stats} — prints evaluation-pipeline metrics</li>
 * </ul>
 * Internal subcommands (triggered by clickable chat messages during the
 * setup wizard, intentionally hidden from tab completion):
 * <ul>
 *     <li>{@code setup players <n>} — sets player count (2–8)</li>
 *     <li>{@code setup lobby} — records lobby position</li>
 *     <li>{@code setup spectator} — records spectator position</li>
 *     <li>{@code setup spawn <plot>} — records plot spawn</li>
 *     <li>{@code setup corner1|corner2 <plot>} — records build zone corner</li>
 *     <li>{@code setup camera <plot>} — records camera angle</li>
 *     <li>{@code setup confirm|cancel} — finalizes or aborts setup</li>
 * </ul>
 */
public class ArenaCommand extends CommandService.PluginCommand {

    /** Full set of public subcommands the command will execute. */
    private static final List<String> PUBLIC_SUBCOMMANDS =
            Arrays.asList("create", "list", "delete", "join", "leave", "stats", "lang");

    /**
     * Returns the immutable list of public subcommand names. Exposed so the
     * flat-mode command bootstrapper (see {@link FlatSubcommand}) can iterate
     * over the same canonical list rather than duplicate the literals.
     */
    public static List<String> publicSubcommands() {
        return PUBLIC_SUBCOMMANDS;
    }

    /**
     * Forwards an already-shaped {@code args} array (subcommand name at index 0,
     * remaining tokens after) into the standard execution path. Used by
     * {@link FlatSubcommand} so flat aliases share the exact dispatch logic of
     * {@code /bbai}.
     */
    void dispatch(CommandSender sender, String[] args) {
        execute(sender, args);
    }

    /**
     * Tab-completion counterpart of {@link #dispatch(CommandSender, String[])}.
     * Flat wrappers prepend their own subcommand name before delegating so the
     * second-arg branch of {@link #suggest} resolves correctly.
     */
    List<String> dispatchSuggest(CommandSender sender, String[] args) {
        return suggest(sender, args);
    }

    /**
     * Creates the arena command.
     *
     * @param plugin the plugin instance
     */
    public ArenaCommand(@NonNull BuildBattleAI plugin) {
        super(plugin, "bbai", "BuildBattleAI arena management",
                "<create|list|delete|join|leave|lang|stats> [arg]");
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create":
                handleCreate(sender, args);
                break;
            case "list":
                handleList(sender);
                break;
            case "delete":
                handleDelete(sender, args);
                break;
            case "join":
                handleJoin(sender, args);
                break;
            case "leave":
                handleLeave(sender);
                break;
            case "stats":
                handleStatsCommand(sender);
                break;
            case "lang":
                handleLang(sender, args);
                break;
            case "setup":
                handleSetup(sender, args);
                break;
            default:
                sendUsage(sender);
                break;
        }
    }

    /**
     * Provides tab-completion suggestions for {@code /bbai}.
     * <p>
     * The first-arg list is filtered by the player's current state so the
     * client only sees actions that make sense:
     * <ul>
     *     <li>If the player is in an active game session, {@code create} and
     *         {@code join} are hidden (they would just print an error) while
     *         {@code leave} stays available.</li>
     *     <li>If the player is not in any session, {@code leave} is hidden
     *         and the rest is offered.</li>
     *     <li>For non-players (console), the full list is shown — console
     *         can administer arenas and {@code lang} is a no-op anyway.</li>
     * </ul>
     * Second-arg completion proposes arena names for {@code delete} /
     * {@code join} and language codes for {@code lang}.
     */
    @Override
    protected List<String> suggest(CommandSender sender, String[] args) {
        if (args.length == 1)
            return filterStartsWith(visibleSubcommandsFor(sender), args[0]);
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if ("delete".equals(sub) || "join".equals(sub))
                return filterStartsWith(
                        new ArrayList<>(plugin.getContext().getArenaManager().getArenaNames()),
                        args[1]);
            if ("lang".equals(sub))
                return filterStartsWith(
                        new ArrayList<>(plugin.getContext().getConfigService().getAvailableLangs()),
                        args[1]);
        }
        return Collections.emptyList();
    }

    /**
     * Returns the subcommands worth offering to {@code sender} right now,
     * taking the player's session state into account. See {@link #suggest}
     * for the filtering rules.
     */
    private List<String> visibleSubcommandsFor(CommandSender sender) {
        if (!(sender instanceof Player))
            return PUBLIC_SUBCOMMANDS;
        Player player = (Player) sender;
        boolean inGame = plugin.getContext().getGameManager().isInGame(player.getUniqueId());
        List<String> out = new ArrayList<>(PUBLIC_SUBCOMMANDS.size());
        for (String sub : PUBLIC_SUBCOMMANDS) {
            // While in a session, hide commands that the player cannot use
            // from here (create / join) and surface only the actions that
            // make sense (leave / list / stats / lang). Mirror logic for
            // the not-in-game case — leave is suppressed.
            if (inGame && ("create".equals(sub) || "join".equals(sub)))
                continue;
            if (!inGame && "leave".equals(sub))
                continue;
            out.add(sub);
        }
        return out;
    }

    // ── subcommand handlers ────────────────────────────────────────────

    /** Handles {@code /bbai create <name>}. */
    private void handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sendPlayerOnly(sender);
            return;
        }
        if (args.length < 2) {
            Lang lang = plugin.getContext().getConfigService().getDefaultLang();
            plugin.getContext().getMessageService().sendChat((Player) sender,
                    lang.get("arena.setup.name-required"));
            return;
        }
        plugin.getContext().getArenaManager().startSetup((Player) sender, args[1]);
    }

    /** Handles {@code /bbai list} — shows arenas with game state and clickable join. */
    private void handleList(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sendPlayerOnly(sender);
            return;
        }
        Player player = (Player) sender;
        BBAIArenaManager arenaManager = plugin.getContext().getArenaManager();
        BBAIGameManager gameManager = plugin.getContext().getGameManager();
        Lang lang = plugin.getContext().getConfigService().getDefaultLang();

        plugin.getContext().getMessageService().sendChat(player, lang.get("arena.setup.divider"));
        plugin.getContext().getMessageService().sendChat(player, lang.get("arena.list.header"));

        if (arenaManager.getArenas().isEmpty())
            plugin.getContext().getMessageService().sendChat(player, lang.get("arena.list.empty"));
        else
            for (Arena arena : arenaManager.getArenas()) {
                if (!arena.enabled())
                    continue;

                ArenaState state = gameManager.getArenaState(arena.name());
                int current = gameManager.getPlayerCount(arena.name());
                int max = arena.maxPlayers();

                String stateText;
                switch (state) {
                    case COUNTDOWN:
                        stateText = lang.get("arena.list.state-countdown");
                        break;
                    case PLAYING:
                        stateText = lang.get("arena.list.state-playing");
                        break;
                    case ENDING:
                        stateText = lang.get("arena.list.state-ending");
                        break;
                    default:
                        stateText = lang.get("arena.list.state-waiting");
                        break;
                }

                ChatMicroService.ChatMessage entry = new ChatMicroService.ChatMessage();
                entry.append(lang.get("arena.list.entry-game",
                        "%arena%", arena.name(),
                        "%current%", String.valueOf(current),
                        "%max%", String.valueOf(max),
                        "%state%", stateText));

                // Join / Full / In Progress button
                boolean joinable = (state == ArenaState.WAITING || state == ArenaState.COUNTDOWN)
                        && current < max;
                if (joinable)
                    entry.append(lang.get("arena.list.join-btn"),
                            ChatMicroService.ClickAction.RUN_COMMAND,
                            "/bbai join " + arena.name(),
                            lang.get("arena.list.join-hover", "%arena%", arena.name()));
                else if (state == ArenaState.PLAYING || state == ArenaState.ENDING)
                    entry.append(lang.get("arena.list.in-progress-btn"));
                else
                    entry.append(lang.get("arena.list.full-btn"));

                plugin.getContext().getMessageService().sendChat(player, entry);
            }

        plugin.getContext().getMessageService().sendChat(player, lang.get("arena.setup.divider"));
    }

    /** Handles {@code /bbai join <arena>}. */
    private void handleJoin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sendPlayerOnly(sender);
            return;
        }
        if (args.length < 2) {
            Lang lang = plugin.getContext().getConfigService().getDefaultLang();
            plugin.getContext().getMessageService().sendChat((Player) sender,
                    lang.get("arena.usage"));
            return;
        }
        plugin.getContext().getGameManager().joinArena((Player) sender, args[1]);
    }

    /** Handles {@code /bbai leave}. */
    private void handleLeave(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sendPlayerOnly(sender);
            return;
        }
        Player player = (Player) sender;
        BBAIGameManager gameManager = plugin.getContext().getGameManager();
        Lang lang = plugin.getContext().getConfigService().getDefaultLang();

        if (!gameManager.isInGame(player.getUniqueId())) {
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("game.leave.not-in-game"));
            return;
        }
        gameManager.leaveArena(player);
        plugin.getContext().getMessageService().sendChat(player,
                lang.get("game.leave.success"));
    }

    /** Handles {@code /bbai delete <name>}. */
    private void handleDelete(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sendPlayerOnly(sender);
            return;
        }
        Player player = (Player) sender;
        Lang lang = plugin.getContext().getConfigService().getDefaultLang();

        if (args.length < 2) {
            plugin.getContext().getMessageService().sendChat(player, lang.get("arena.delete.usage"));
            return;
        }

        String name = args[1];
        if (plugin.getContext().getArenaManager().getArena(name) == null) {
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("arena.delete.not-found", "%arena%", name));
            return;
        }

        plugin.getContext().getArenaManager().deleteArena(name);
        plugin.getContext().getMessageService().sendChat(player,
                lang.get("arena.delete.success", "%arena%", name));
    }

    /**
     * Routes internal setup subcommands triggered by clickable chat buttons.
     * These are never shown in tab completion.
     */
    private void handleSetup(CommandSender sender, String[] args) {
        if (!(sender instanceof Player))
            return;
        Player player = (Player) sender;
        BBAIArenaManager am = plugin.getContext().getArenaManager();

        if (!am.hasSetupSession(player.getUniqueId())) {
            Lang lang = plugin.getContext().getConfigService().getDefaultLang();
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("arena.setup.no-session"));
            return;
        }
        if (args.length < 2)
            return;

        String action = args[1].toLowerCase();
        switch (action) {
            case "players":
                if (args.length >= 3) {
                    int count = parseIntSafe(args[2]);
                    if (count >= 2 && count <= 8)
                        am.handleSetPlayers(player, count);
                }
                break;
            case "lobby":
                am.handleSetLobby(player);
                break;
            case "spectator":
                am.handleSetSpectator(player);
                break;
            case "spawn":
                if (args.length >= 3) {
                    int plot = parseIntSafe(args[2]);
                    if (plot >= 1)
                        am.handleSetSpawn(player, plot);
                }
                break;
            case "corner1":
                if (args.length >= 3) {
                    int plot = parseIntSafe(args[2]);
                    if (plot >= 1)
                        am.handleSetCorner1(player, plot);
                }
                break;
            case "corner2":
                if (args.length >= 3) {
                    int plot = parseIntSafe(args[2]);
                    if (plot >= 1)
                        am.handleSetCorner2(player, plot);
                }
                break;
            case "camera1":
                if (args.length >= 3) {
                    int plot = parseIntSafe(args[2]);
                    if (plot >= 1)
                        am.handleSetCamera(player, plot, 1);
                }
                break;
            case "camera2":
                if (args.length >= 3) {
                    int plot = parseIntSafe(args[2]);
                    if (plot >= 1)
                        am.handleSetCamera(player, plot, 2);
                }
                break;
            case "camera3":
                if (args.length >= 3) {
                    int plot = parseIntSafe(args[2]);
                    if (plot >= 1)
                        am.handleSetCamera(player, plot, 3);
                }
                break;
            case "tab":
                if (args.length >= 3) {
                    int plot = parseIntSafe(args[2]);
                    if (plot >= 1)
                        am.handleSetTab(player, plot);
                }
                break;
            case "pic-corner1":
                if (args.length >= 3) {
                    int plot = parseIntSafe(args[2]);
                    if (plot >= 1)
                        am.handleSetPictureCorner1(player, plot);
                }
                break;
            case "pic-corner2":
                if (args.length >= 3) {
                    int plot = parseIntSafe(args[2]);
                    if (plot >= 1)
                        am.handleSetPictureCorner2(player, plot);
                }
                break;
            case "pic-face":
                if (args.length >= 4) {
                    int plot = parseIntSafe(args[2]);
                    BlockFace face = parseCardinalFace(args[3]);
                    if (plot >= 1 && face != null)
                        am.handleSetPictureFace(player, plot, face);
                }
                break;
            case "minplayers":
                if (args.length >= 3)
                    handleSetupMinPlayers(player, am, args[2]);
                break;
            case "buildtime":
                if (args.length >= 3)
                    handleSetupBuildTime(player, am, args[2]);
                break;
            case "gametime":
                if (args.length >= 3)
                    handleSetupGameTime(player, am, args[2]);
                break;
            case "countdown":
                if (args.length >= 3)
                    handleSetupCountdown(player, am, args[2]);
                break;
            case "confirm":
                am.handleConfirm(player);
                break;
            case "cancel":
                am.handleCancel(player);
                break;
            default:
                break;
        }
    }

    /**
     * Validates and applies the min-players setup value.
     * Must be an integer between 2 and maxPlayers (or 8 if maxPlayers not set).
     */
    private void handleSetupMinPlayers(Player player, BBAIArenaManager am, String value) {
        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        int count = parseIntSafe(value);
        int maxAllowed = 8; // default cap if max players not yet set
        if (count < 2 || count > maxAllowed) {
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("arena.setup.minplayers.invalid", "%max%", String.valueOf(maxAllowed)));
            return;
        }
        am.handleSetMinPlayers(player, count);
    }

    /**
     * Validates and applies the build-time setup value.
     * Input is in decimal minutes (0.5–10), stored as seconds internally.
     */
    private void handleSetupBuildTime(Player player, BBAIArenaManager am, String value) {
        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        double minutes = parseDoubleSafe(value);
        if (minutes < 0.5 || minutes > 10.0) {
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("arena.setup.buildtime.invalid"));
            return;
        }
        int seconds = (int) Math.round(minutes * 60);
        am.handleSetBuildTime(player, seconds);
    }

    /**
     * Validates and applies the game-time setup value.
     * Input is in decimal minutes (1–30), stored as seconds internally.
     */
    private void handleSetupGameTime(Player player, BBAIArenaManager am, String value) {
        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        double minutes = parseDoubleSafe(value);
        if (minutes < 1.0 || minutes > 30.0) {
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("arena.setup.gametime.invalid"));
            return;
        }
        int seconds = (int) Math.round(minutes * 60);
        am.handleSetGameTime(player, seconds);
    }

    /**
     * Validates and applies the countdown setup value.
     * Input is in integer seconds (3–60).
     */
    private void handleSetupCountdown(Player player, BBAIArenaManager am, String value) {
        Lang lang = plugin.getContext().getConfigService().getDefaultLang();
        int seconds = parseIntSafe(value);
        if (seconds < 3 || seconds > 60) {
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("arena.setup.countdown.invalid"));
            return;
        }
        am.handleSetCountdown(player, seconds);
    }

    /**
     * Handles {@code /bbai lang [code]} — without an argument prints the
     * player's current language plus the list of available languages; with
     * a code argument persists the new language preference and confirms in
     * the freshly-switched language.
     */
    private void handleLang(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sendPlayerOnly(sender);
            return;
        }
        Player player = (Player) sender;
        BBAIConfigService cfg = plugin.getContext().getConfigService();
        // Resolve the current per-player lang so the response is shown in
        // whatever language the player currently uses — feels natural even
        // when they are about to switch away.
        Lang lang = cfg.getLangFor(player.getUniqueId());
        java.util.Set<String> available = cfg.getAvailableLangs();

        if (args.length < 2) {
            // List mode: show current + available codes.
            String current = currentLangNameFor(player);
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("lang.current", "%lang%", current));
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("lang.available",
                            "%list%", String.join(", ", available)));
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("lang.usage"));
            return;
        }

        String requested = args[1].toLowerCase();
        if (!available.contains(requested)) {
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("lang.unknown",
                            "%lang%", requested,
                            "%list%", String.join(", ", available)));
            return;
        }

        // Persistence requires DataService — when disabled (data.enabled=false)
        // there is nowhere durable to remember the preference, so we refuse
        // rather than silently dropping the choice on the next relog.
        BBAIDataService data = plugin.getContext().getDataService();
        if (!data.isEnabled()) {
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("lang.disabled"));
            return;
        }
        PlayerData pd = data.getOrCreatePlayer(player.getUniqueId(), player.getName());
        pd.language(requested);
        data.savePlayer(pd);

        // After saving, fetch the new lang to confirm in THE NEW language.
        Lang newLang = cfg.getLangFor(player.getUniqueId());
        plugin.getContext().getMessageService().sendChat(player,
                newLang.get("lang.switched", "%lang%", requested));
    }

    /**
     * Returns the player's currently-stored language code, or the default
     * language name when the player has not set a preference.
     */
    private String currentLangNameFor(Player player) {
        BBAIDataService data = plugin.getContext().getDataService();
        if (data.isEnabled()) {
            PlayerData pd = data.getPlayer(player.getUniqueId());
            if (pd != null && pd.language() != null && !pd.language().isEmpty())
                return pd.language();
        }
        Lang def = plugin.getContext().getConfigService().getDefaultLang();
        return def != null ? def.name() : "?";
    }

    /**
     * Prints a snapshot of the evaluation-pipeline metrics. Admin-only
     * diagnostic command; output is read-only and safe to invoke from
     * any context.
     */
    private void handleStatsCommand(@NonNull CommandSender sender) {
        EvaluationStats s = plugin.getContext().getEvaluationService().stats();
        sendStatsLine(sender, "&7── &eEvaluation Pipeline Stats &7──");
        sendStatsLine(sender, "&7Sessions: &f" + s.registeredSessions()
                + "  &7Players: &f" + s.activePlayers());
        sendStatsLine(sender, "&7Rendered: &f" + s.rendersCompleted()
                + "  &7ML batches: &f" + s.mlBatchesCompleted()
                + "  &7Matches: &f" + s.matchesDispatched());
        sendStatsLine(sender, "&7Avg render: &f" + s.renderLatencyAvgMicros() + "us"
                + "  &7Avg ML: &f" + s.mlLatencyAvgMicros() + "us");
        sendStatsLine(sender, "&7Dropped (R/M): &f" + s.droppedRenderJobs() + "&7/&f" + s.droppedMlJobs()
                + "  &7Errors (R/M): &f" + s.renderErrors() + "&7/&f" + s.mlErrors());
        sendStatsLine(sender, "&7Queue depth (R/M): &f" + s.renderQueueDepth()
                + "&7/&f" + s.mlQueueDepth());
        StringBuilder hist = new StringBuilder("&7Batch sizes: &f");
        long[] h = s.batchSizeHistogram();
        for (int i = 1; i < h.length; i++)
            hist.append("[").append(i).append(":").append(h[i]).append("]");
        sendStatsLine(sender, hist.toString());
    }

    /**
     * Sends a stats line through {@code MessageService} for players (to
     * preserve packet-based chat dispatch) and via the raw console sink
     * otherwise. The {@code &}-to-{@code §} substitution mirrors what
     * Bukkit's legacy color handler does for console output.
     */
    private void sendStatsLine(@NonNull CommandSender sender, @NonNull String line) {
        if (sender instanceof Player)
            plugin.getContext().getMessageService().sendChat((Player) sender, line);
        else
            sender.sendMessage(line.replace("&", "§"));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void sendUsage(CommandSender sender) {
        if (sender instanceof Player) {
            Lang lang = plugin.getContext().getConfigService().getDefaultLang();
            plugin.getContext().getMessageService().sendChat((Player) sender,
                    lang.get("arena.usage"));
        } else
            sender.sendMessage("Usage: /bbai <create|list|delete|stats> [name]");
    }

    private void sendPlayerOnly(CommandSender sender) {
        sender.sendMessage("This command can only be used by players.");
    }

    /** Parses an int from a string, returning -1 on failure. */
    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Parses a double from a string, returning -1 on failure. */
    private static double parseDoubleSafe(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Parses a cardinal {@link BlockFace} name (NORTH/SOUTH/EAST/WEST,
     * case-insensitive). Returns {@code null} for unknown or non-cardinal
     * values; the picture surface only supports wall-mounted frames so the
     * other {@link BlockFace} entries (UP/DOWN/diagonals) are rejected here.
     */
    private static BlockFace parseCardinalFace(String raw) {
        if (raw == null)
            return null;
        try {
            BlockFace face = BlockFace.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            switch (face) {
                case NORTH:
                case SOUTH:
                case EAST:
                case WEST:
                    return face;
                default:
                    return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Filters options by prefix for tab completion (case-insensitive). */
    private static List<String> filterStartsWith(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String option : options)
            if (option.toLowerCase().startsWith(lower))
                result.add(option);
        return result;
    }
}
