package ru.ashesha.buildBattleAI.commands;

import lombok.NonNull;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.arena.api.BBAIArenaManager;
import ru.ashesha.buildBattleAI.config.api.Lang;
import ru.ashesha.buildBattleAI.message.micro.ChatMicroService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Main plugin command ({@code /bbai}) for arena management.
 * <p>
 * Public subcommands (visible in tab completion):
 * <ul>
 *     <li>{@code create <name>} — starts the interactive arena setup wizard</li>
 *     <li>{@code list} — displays all configured arenas with their status</li>
 *     <li>{@code delete <name>} — permanently removes an arena and its world</li>
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

    /** Subcommands exposed in tab completion. */
    private static final List<String> PUBLIC_SUBCOMMANDS = Arrays.asList("create", "list", "delete");

    /**
     * Creates the arena command.
     *
     * @param plugin the plugin instance
     */
    public ArenaCommand(@NonNull BuildBattleAI plugin) {
        super(plugin, "bbai", "BuildBattleAI arena management", "<create|list|delete> [name]");
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
            case "setup":
                handleSetup(sender, args);
                break;
            default:
                sendUsage(sender);
                break;
        }
    }

    @Override
    protected List<String> suggest(CommandSender sender, String[] args) {
        if (args.length == 1)
            return filterStartsWith(PUBLIC_SUBCOMMANDS, args[0]);
        if (args.length == 2 && "delete".equalsIgnoreCase(args[0]))
            return filterStartsWith(
                    new ArrayList<>(plugin.getContext().getArenaManager().getArenaNames()),
                    args[1]);
        return Collections.emptyList();
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

    /** Handles {@code /bbai list}. */
    private void handleList(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sendPlayerOnly(sender);
            return;
        }
        Player player = (Player) sender;
        BBAIArenaManager arenaManager = plugin.getContext().getArenaManager();
        Lang lang = plugin.getContext().getConfigService().getDefaultLang();

        plugin.getContext().getMessageService().sendChat(player, lang.get("arena.setup.divider"));
        plugin.getContext().getMessageService().sendChat(player, lang.get("arena.list.header"));

        if (arenaManager.getArenas().isEmpty())
            plugin.getContext().getMessageService().sendChat(player, lang.get("arena.list.empty"));
        else
            for (Arena arena : arenaManager.getArenas()) {
                String status = arena.enabled()
                        ? lang.get("arena.list.status-enabled")
                        : lang.get("arena.list.status-disabled");
                ChatMicroService.ChatMessage entry = new ChatMicroService.ChatMessage();
                entry.append(lang.get("arena.list.entry",
                        "%arena%", arena.name(),
                        "%players%", String.valueOf(arena.maxPlayers()),
                        "%status%", status));
                plugin.getContext().getMessageService().sendChat(player, entry);
            }

        plugin.getContext().getMessageService().sendChat(player, lang.get("arena.setup.divider"));
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

    // ── helpers ─────────────────────────────────────────────────────────

    private void sendUsage(CommandSender sender) {
        if (sender instanceof Player) {
            Lang lang = plugin.getContext().getConfigService().getDefaultLang();
            plugin.getContext().getMessageService().sendChat((Player) sender,
                    lang.get("arena.usage"));
        } else
            sender.sendMessage("Usage: /bbai <create|list|delete> [name]");
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
