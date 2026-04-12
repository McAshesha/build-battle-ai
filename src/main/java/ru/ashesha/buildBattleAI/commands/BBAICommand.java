package ru.ashesha.buildBattleAI.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.api.BBAIChatMessage;
import ru.ashesha.buildBattleAI.core.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.commands.base.PluginCommand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Primary plugin command ({@code /bbai}).
 * <p>
 * Without arguments, displays plugin information (version, authors, status).
 * The {@code /bbai demo <mode>} subcommand showcases all messaging capabilities
 * provided by the {@link BBAIMessageService} (chat, rich text, action bar, title,
 * tab header/footer, and player list name).
 */
public class BBAICommand extends PluginCommand {

    public BBAICommand(BuildBattleAI plugin) {
        super(plugin, "bbai");
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        // Dispatch to demo subcommand if requested
        if (args.length > 0 && "demo".equalsIgnoreCase(args[0])) {
            executeDemo(sender, args);
            return;
        }

        // Default: show plugin info
        PluginDescriptionFile description = plugin.getDescription();

        sender.sendMessage(ChatColor.GOLD + "=== BuildBattleAI ===");
        sender.sendMessage(ChatColor.YELLOW + "Version: " + ChatColor.WHITE + description.getVersion());
        sender.sendMessage(ChatColor.YELLOW + "Authors: " + ChatColor.WHITE + String.join(", ", description.getAuthors()));
        sender.sendMessage(ChatColor.YELLOW + "Status: " + ChatColor.GREEN + "Enabled");
        sender.sendMessage(ChatColor.YELLOW + "Usage: " + ChatColor.WHITE + "/bbai demo <all|chat|rich|bar|title|tab|listname|reset>");
    }

    @Override
    protected List<String> suggest(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("demo");
            return suggestions;
        }

        if (args.length == 2 && "demo".equalsIgnoreCase(args[0])) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("all");
            suggestions.add("chat");
            suggestions.add("rich");
            suggestions.add("bar");
            suggestions.add("title");
            suggestions.add("tab");
            suggestions.add("listname");
            suggestions.add("reset");
            return suggestions;
        }

        return Collections.emptyList();
    }

    /**
     * Executes the demo subcommand, dispatching to the appropriate messaging demo
     * based on the mode argument. Requires the sender to be a player.
     */
    private void executeDemo(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cDemo commands can only be used by a player.");
            return;
        }

        if (plugin.getContext().getMessageService() == null) {
            sender.sendMessage("§cMessageService is not available.");
            return;
        }

        Player player = (Player) sender;
        String mode = args.length > 1 ? args[1].toLowerCase() : "all";
        BBAIMessageService messageService = plugin.getContext().getMessageService();

        if ("all".equals(mode)) {
            runChatDemo(player, messageService);
            runRichDemo(player, messageService);
            runActionBarDemo(player, messageService);
            runTitleDemo(player, messageService);
            runTabDemo(player, messageService);
            runListNameDemo(player, messageService);
            player.sendMessage("§aAll messaging demos were sent.");
            return;
        }

        if ("chat".equals(mode)) {
            runChatDemo(player, messageService);
            return;
        }

        if ("rich".equals(mode)) {
            runRichDemo(player, messageService);
            return;
        }

        if ("bar".equals(mode)) {
            runActionBarDemo(player, messageService);
            return;
        }

        if ("title".equals(mode)) {
            runTitleDemo(player, messageService);
            return;
        }

        if ("tab".equals(mode)) {
            runTabDemo(player, messageService);
            return;
        }

        if ("listname".equals(mode)) {
            runListNameDemo(player, messageService);
            return;
        }

        if ("reset".equals(mode)) {
            resetDemo(player, messageService);
            return;
        }

        player.sendMessage("§cUnknown demo mode. Use /bbai demo <all|chat|rich|bar|title|tab|listname|reset>");
    }

    /** Sends a plain chat message via PacketEvents. */
    private void runChatDemo(Player player, BBAIMessageService messageService) {
        messageService.sendChat(player, "&6[Chat Demo] &fPlain PacketEvents chat message with &acolors&f.");
        player.sendMessage("§aChat demo sent.");
    }

    /** Sends a rich chat message with clickable segments and hover tooltips. */
    private void runRichDemo(Player player, BBAIMessageService messageService) {
        BBAIChatMessage message = BBAIChatMessage.builder()
                .append("&6[Rich Demo] &f")
                .append("&a[RUN /bbai]", BBAIChatMessage.ClickAction.RUN_COMMAND, "/bbai", "&7Runs the base plugin command")
                .append(" &7| ")
                .append("&b[SUGGEST /shot screenshot]", BBAIChatMessage.ClickAction.SUGGEST_COMMAND, "/shot screenshot", "&7Only inserts the command into chat")
                .append(" &7| ")
                .append("&d[OPEN URL]", BBAIChatMessage.ClickAction.OPEN_URL, "https://github.com/retrooper/packetevents", "&7Open PacketEvents page")
                .build();
        messageService.sendChat(player, message);
        player.sendMessage("§aRich chat demo sent.");
    }

    /** Displays a message on the action bar above the hotbar. */
    private void runActionBarDemo(Player player, BBAIMessageService messageService) {
        messageService.sendActionBar(player, "&e[Bar Demo] &fPacketEvents action bar test");
        player.sendMessage("§aAction bar demo sent.");
    }

    /** Displays a title and subtitle overlay on the player's screen. */
    private void runTitleDemo(Player player, BBAIMessageService messageService) {
        messageService.sendTitle(player, "&6BuildBattleAI", "&fPacketEvents title/subtitle demo", 10, 50, 15);
        player.sendMessage("§aTitle demo sent.");
    }

    /** Sets custom header and footer in the player list (tab) overlay. */
    private void runTabDemo(Player player, BBAIMessageService messageService) {
        messageService.sendTab(
                player,
                "&6BuildBattleAI Test Header\n&fPacketEvents tab demo",
                "&7Player: &e" + player.getName() + "\n&aFooter line 2"
        );
        player.sendMessage("§aTab demo sent.");
    }

    /** Changes the player's display name in the tab list for all online viewers. */
    private void runListNameDemo(Player player, BBAIMessageService messageService) {
        messageService.sendPlayerListName(player, "&b[AI] &f" + player.getName(), player.getServer().getOnlinePlayers());
        player.sendMessage("§aPlayer list name demo sent to all online viewers.");
    }

    /** Resets tab header/footer and player list name to defaults. */
    private void resetDemo(Player player, BBAIMessageService messageService) {
        messageService.sendTab(player, "", "");
        messageService.sendPlayerListName(player, null, player.getServer().getOnlinePlayers());
        player.sendMessage("§aTab header/footer and player list name were reset.");
    }
}
