package ru.ashesha.buildBattleAI.commands;

import lombok.NonNull;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.commands.CommandService.PluginCommand;
import ru.ashesha.buildBattleAI.entity.hologram.HologramService;
import ru.ashesha.buildBattleAI.entity.hologram.api.BBAIHologramService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test command for spawning, editing, removing, and teleporting packet-based holograms.
 * <ul>
 *     <li>{@code /testholo <line1|line2|...>} — spawns a multiline hologram at the
 *         player's location; lines are separated by {@code |}</li>
 *     <li>{@code /testholo remove <id>} — removes a previously spawned hologram
 *         by the entity ID of its first line</li>
 *     <li>{@code /testholo edit <id> <lineIndex> <text>} — replaces a single line</li>
 *     <li>{@code /testholo tp <id>} — teleports the hologram to the player's
 *         current location</li>
 *     <li>{@code /testholo list} — lists all spawned holograms with their IDs
 *         and line counts</li>
 * </ul>
 * The first entity ID is printed after each successful spawn for use with
 * other subcommands.
 */
public class TestHologramCommand extends PluginCommand {

    /**
     * Tracks holograms spawned via this command, keyed by the entity ID
     * of the first line (top armor stand) for easy lookup.
     */
    private final Map<Integer, HologramService.Hologram> spawnedHolograms = new HashMap<>();

    /**
     * Creates the test hologram command.
     *
     * @param plugin the plugin instance
     */
    public TestHologramCommand(@NonNull BuildBattleAI plugin) {
        super(plugin, "testholo",
                "Test command for hologram spawning, editing, and removal",
                "<line1|line2|...>|remove <id>|edit <id> <line> <text>|tp <id>|list");
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("\u00a7cOnly players can use this command.");
            return;
        }
        Player player = (Player) sender;
        BBAIHologramService holoService = plugin.getContext().getHologramService();

        if (args.length == 0) {
            player.sendMessage("\u00a7cUsage: /testholo <line1|line2|...>");
            return;
        }

        // /testholo remove <id>
        if ("remove".equalsIgnoreCase(args[0])) {
            handleRemove(player, holoService, args);
            return;
        }

        // /testholo edit <id> <lineIndex> <text...>
        if ("edit".equalsIgnoreCase(args[0])) {
            handleEdit(player, holoService, args);
            return;
        }

        // /testholo tp <id>
        if ("tp".equalsIgnoreCase(args[0])) {
            handleTeleport(player, holoService, args);
            return;
        }

        // /testholo list
        if ("list".equalsIgnoreCase(args[0])) {
            handleList(player);
            return;
        }

        // /testholo <line1|line2|...> — spawn a new multiline hologram
        handleSpawn(player, holoService, args);
    }

    /**
     * Spawns a new hologram at the player's eye location.
     * Lines are joined from all args and split by {@code |}.
     */
    private void handleSpawn(Player player, BBAIHologramService holoService, String[] args) {
        // Join all args to support spaces within lines
        String joined = join(args, " ");
        String[] lines = joined.split("\\|");
        List<String> lineList = Arrays.asList(lines);

        // Spawn slightly above the player's eye level
        Location loc = player.getEyeLocation().add(0, 0.5, 0);

        HologramService.Hologram hologram = holoService.createHologram(lines.length);
        holoService.spawn(player, hologram, loc, lineList);

        int id = hologram.getEntityId(0);
        spawnedHolograms.put(id, hologram);
        player.sendMessage("\u00a7aSpawned hologram with ID \u00a7e" + id
                + " \u00a7a(" + lines.length + " line" + (lines.length == 1 ? "" : "s") + ")");
    }

    /**
     * Handles the {@code /testholo remove <id>} subcommand.
     */
    private void handleRemove(Player player, BBAIHologramService holoService, String[] args) {
        if (args.length < 2) {
            player.sendMessage("\u00a7cUsage: /testholo remove <id>");
            return;
        }
        int id = parseId(player, args[1]);
        if (id == -1)
            return;

        HologramService.Hologram hologram = spawnedHolograms.remove(id);
        if (hologram == null) {
            player.sendMessage("\u00a7cHologram with ID " + id + " not found.");
            return;
        }

        holoService.despawn(player, hologram);
        player.sendMessage("\u00a7aRemoved hologram with ID \u00a7e" + id);
    }

    /**
     * Handles the {@code /testholo edit <id> <lineIndex> <text...>} subcommand.
     */
    private void handleEdit(Player player, BBAIHologramService holoService, String[] args) {
        if (args.length < 4) {
            player.sendMessage("\u00a7cUsage: /testholo edit <id> <lineIndex> <text...>");
            return;
        }
        int id = parseId(player, args[1]);
        if (id == -1)
            return;

        HologramService.Hologram hologram = spawnedHolograms.get(id);
        if (hologram == null) {
            player.sendMessage("\u00a7cHologram with ID " + id + " not found.");
            return;
        }

        int lineIndex;
        try {
            lineIndex = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage("\u00a7cInvalid line index: " + args[2]);
            return;
        }
        if (lineIndex < 0 || lineIndex >= hologram.getLineCount()) {
            player.sendMessage("\u00a7cLine index must be between 0 and " + (hologram.getLineCount() - 1));
            return;
        }

        // Join remaining args as the new text
        String[] textParts = new String[args.length - 3];
        System.arraycopy(args, 3, textParts, 0, textParts.length);
        String newText = join(textParts, " ");

        holoService.updateLine(player, hologram, lineIndex, newText);
        player.sendMessage("\u00a7aUpdated line " + lineIndex + " of hologram \u00a7e" + id);
    }

    /**
     * Handles the {@code /testholo tp <id>} subcommand.
     * Teleports the hologram to the player's current eye location.
     */
    private void handleTeleport(Player player, BBAIHologramService holoService, String[] args) {
        if (args.length < 2) {
            player.sendMessage("\u00a7cUsage: /testholo tp <id>");
            return;
        }
        int id = parseId(player, args[1]);
        if (id == -1)
            return;

        HologramService.Hologram hologram = spawnedHolograms.get(id);
        if (hologram == null) {
            player.sendMessage("\u00a7cHologram with ID " + id + " not found.");
            return;
        }

        Location dest = player.getEyeLocation().add(0, 0.5, 0);
        holoService.teleport(player, hologram, dest);
        player.sendMessage("\u00a7aTeleported hologram \u00a7e" + id + " \u00a7ato your location");
    }

    /**
     * Handles the {@code /testholo list} subcommand.
     * Lists all tracked holograms with their IDs and line counts.
     */
    private void handleList(Player player) {
        if (spawnedHolograms.isEmpty()) {
            player.sendMessage("\u00a7cNo holograms spawned.");
            return;
        }

        player.sendMessage("\u00a76--- Holograms (" + spawnedHolograms.size() + ") ---");
        for (Map.Entry<Integer, HologramService.Hologram> entry : spawnedHolograms.entrySet()) {
            HologramService.Hologram h = entry.getValue();
            player.sendMessage("\u00a7eID " + entry.getKey()
                    + " \u00a77(" + h.getLineCount() + " lines)");
        }
    }

    @Override
    protected List<String> suggest(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("remove");
            suggestions.add("edit");
            suggestions.add("tp");
            suggestions.add("list");
            return filterPrefix(suggestions, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if ("remove".equals(sub) || "edit".equals(sub) || "tp".equals(sub)) {
                List<String> ids = new ArrayList<>();
                for (Integer id : spawnedHolograms.keySet())
                    ids.add(String.valueOf(id));
                return filterPrefix(ids, args[1]);
            }
        }
        if (args.length == 3 && "edit".equalsIgnoreCase(args[0])) {
            // Suggest valid line indices for the given hologram
            int id = parseIdSilent(args[1]);
            HologramService.Hologram hologram = id != -1 ? spawnedHolograms.get(id) : null;
            if (hologram != null) {
                List<String> indices = new ArrayList<>();
                for (int i = 0; i < hologram.getLineCount(); i++)
                    indices.add(String.valueOf(i));
                return filterPrefix(indices, args[2]);
            }
        }
        return Collections.emptyList();
    }

    /**
     * Parses an integer ID from a string, sending an error message on failure.
     *
     * @return the parsed ID, or {@code -1} on parse failure
     */
    private static int parseId(Player player, String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            player.sendMessage("\u00a7cInvalid ID: " + input);
            return -1;
        }
    }

    /**
     * Parses an integer ID silently (no error message). Used for tab completion.
     *
     * @return the parsed ID, or {@code -1} on parse failure
     */
    private static int parseIdSilent(String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Joins string array elements with a delimiter. Java 8 compatible.
     */
    private static String join(String[] parts, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0)
                sb.append(delimiter);
            sb.append(parts[i]);
        }
        return sb.toString();
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
