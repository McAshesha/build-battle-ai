package ru.ashesha.buildBattleAI.commands;

import lombok.NonNull;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.commands.CommandService.PluginCommand;
import ru.ashesha.buildBattleAI.message.BoardMicroService;
import ru.ashesha.buildBattleAI.message.api.BBAIMessageService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Test command for creating, editing, and removing packet-based sidebar scoreboards.
 * <p>
 * Subcommands:
 * <ul>
 *     <li>{@code /testboard} — creates a demo scoreboard with sample lines</li>
 *     <li>{@code /testboard title <text>} — updates the scoreboard title</li>
 *     <li>{@code /testboard set <line> <text>} — sets text for a specific line (0 = bottom, 14 = top)</li>
 *     <li>{@code /testboard removeline <line>} — removes a specific line</li>
 *     <li>{@code /testboard remove} — removes the entire scoreboard</li>
 * </ul>
 */
public class TestBoardCommand extends PluginCommand {

    /** Available subcommand names for tab completion. */
    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "title", "set", "removeline", "remove"
    );

    /**
     * Creates the test board command.
     *
     * @param plugin the plugin instance
     */
    public TestBoardCommand(@NonNull BuildBattleAI plugin) {
        super(plugin, "testboard", "Test command for scoreboard management",
                "[title <text>|set <line> <text>|removeline <line>|remove]");
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("\u00a7cOnly players can use this command.");
            return;
        }
        Player player = (Player) sender;
        BBAIMessageService messageService = plugin.getContext().getMessageService();

        // /testboard — create a demo board
        if (args.length == 0) {
            handleCreate(player, messageService);
            return;
        }

        String sub = args[0].toLowerCase();

        // /testboard title <text...>
        if ("title".equals(sub)) {
            handleTitle(player, messageService, args);
            return;
        }

        // /testboard set <line> <text...>
        if ("set".equals(sub)) {
            handleSet(player, messageService, args);
            return;
        }

        // /testboard removeline <line>
        if ("removeline".equals(sub)) {
            handleRemoveLine(player, messageService, args);
            return;
        }

        // /testboard remove
        if ("remove".equals(sub)) {
            handleRemove(player, messageService);
            return;
        }

        player.sendMessage("\u00a7cUnknown subcommand: " + args[0]);
        player.sendMessage("\u00a77Usage: " + getUsage());
    }

    /**
     * Creates a demo scoreboard with sample lines to showcase the board functionality.
     * Lines are set with explicit indices so they appear in a natural top-to-bottom layout.
     */
    private void handleCreate(Player player, BBAIMessageService messageService) {
        BoardMicroService.Board board = messageService.createBoard(player, "&e&lBuildBattle AI");

        // Build lines from top (14) to bottom (0) for natural visual layout
        int line = 14;
        board.setLine(line--, "&7&m                    ");
        board.setLine(line--, " ");
        board.setLine(line--, "&fPlayer: &a" + player.getName());
        board.setLine(line--, "&fWorld: &a" + player.getWorld().getName());
        board.setLine(line--, "  ");
        board.setLine(line--, "&fScore: &e100");
        board.setLine(line--, "   ");
        board.setLine(line--, "&7&m                    ");
        board.setLine(line--, "    ");
        board.setLine(line, "&eplay.example.com");

        player.sendMessage("\u00a7aScoreboard created! Use \u00a7e/testboard remove\u00a7a to remove it.");
    }

    /**
     * Handles the {@code /testboard title <text...>} subcommand.
     */
    private void handleTitle(Player player, BBAIMessageService messageService, String[] args) {
        BoardMicroService.Board board = messageService.getBoard(player);
        if (board == null) {
            player.sendMessage("\u00a7cYou don't have an active scoreboard. Use \u00a7e/testboard\u00a7c to create one.");
            return;
        }
        if (args.length < 2) {
            player.sendMessage("\u00a7cUsage: /testboard title <text>");
            return;
        }
        String title = joinArgs(args, 1);
        board.setTitle(title);
        player.sendMessage("\u00a7aTitle updated to: " + title);
    }

    /**
     * Handles the {@code /testboard set <line> <text...>} subcommand.
     */
    private void handleSet(Player player, BBAIMessageService messageService, String[] args) {
        BoardMicroService.Board board = messageService.getBoard(player);
        if (board == null) {
            player.sendMessage("\u00a7cYou don't have an active scoreboard. Use \u00a7e/testboard\u00a7c to create one.");
            return;
        }
        if (args.length < 3) {
            player.sendMessage("\u00a7cUsage: /testboard set <line> <text>");
            return;
        }
        int line = parseLine(player, args[1]);
        if (line < 0)
            return;
        String text = joinArgs(args, 2);
        board.setLine(line, text);
        player.sendMessage("\u00a7aLine " + line + " set to: " + text);
    }

    /**
     * Handles the {@code /testboard removeline <line>} subcommand.
     */
    private void handleRemoveLine(Player player, BBAIMessageService messageService, String[] args) {
        BoardMicroService.Board board = messageService.getBoard(player);
        if (board == null) {
            player.sendMessage("\u00a7cYou don't have an active scoreboard. Use \u00a7e/testboard\u00a7c to create one.");
            return;
        }
        if (args.length < 2) {
            player.sendMessage("\u00a7cUsage: /testboard removeline <line>");
            return;
        }
        int line = parseLine(player, args[1]);
        if (line < 0)
            return;
        board.removeLine(line);
        player.sendMessage("\u00a7aLine " + line + " removed.");
    }

    /**
     * Handles the {@code /testboard remove} subcommand.
     */
    private void handleRemove(Player player, BBAIMessageService messageService) {
        BoardMicroService.Board board = messageService.getBoard(player);
        if (board == null) {
            player.sendMessage("\u00a7cYou don't have an active scoreboard.");
            return;
        }
        messageService.removeBoard(player);
        player.sendMessage("\u00a7aScoreboard removed.");
    }

    /**
     * Parses a line index from a string argument. Returns -1 and sends an error
     * message if the input is not a valid line number.
     *
     * @param player the player to notify on error
     * @param arg    the string to parse
     * @return the parsed line index (0–14), or -1 on failure
     */
    private int parseLine(Player player, String arg) {
        try {
            int line = Integer.parseInt(arg);
            if (line < 0 || line >= BoardMicroService.MAX_LINES) {
                player.sendMessage("\u00a7cLine must be 0-" + (BoardMicroService.MAX_LINES - 1) + ".");
                return -1;
            }
            return line;
        } catch (NumberFormatException e) {
            player.sendMessage("\u00a7cInvalid line number: " + arg);
            return -1;
        }
    }

    /**
     * Joins command arguments from the given start index with spaces.
     *
     * @param args  the full argument array
     * @param start the index to start joining from (inclusive)
     * @return the joined string
     */
    private static String joinArgs(String[] args, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start)
                sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    @Override
    protected List<String> suggest(CommandSender sender, String[] args) {
        if (args.length == 1)
            return filterPrefix(SUBCOMMANDS, args[0]);

        String sub = args[0].toLowerCase();

        // Line number suggestions for "set" and "removeline"
        if (args.length == 2 && ("set".equals(sub) || "removeline".equals(sub)))
            return filterPrefix(lineNumbers(), args[1]);

        return Collections.emptyList();
    }

    /**
     * Generates a list of valid line number strings (0–14) for tab completion.
     */
    private static List<String> lineNumbers() {
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < BoardMicroService.MAX_LINES; i++)
            numbers.add(String.valueOf(i));
        return numbers;
    }

    /**
     * Filters a suggestion list to entries matching the given prefix (case-insensitive).
     */
    private static List<String> filterPrefix(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String option : options)
            if (option.toLowerCase().startsWith(lower))
                result.add(option);
        return result;
    }
}
