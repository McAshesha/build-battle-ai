package ru.ashesha.buildBattleAI.commands;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.commands.CommandService.PluginCommand;
import ru.ashesha.buildBattleAI.entity.npc.NPCService;
import ru.ashesha.buildBattleAI.entity.npc.api.BBAINPCService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test command for spawning and despawning packet-based NPCs.
 * <ul>
 *     <li>{@code /testnpc} — spawns an NPC at the player's location with
 *         the player's own skin and current equipment</li>
 *     <li>{@code /testnpc <skinName>} — same, but fetches the skin from
 *         the Mojang API by player name (async HTTP request)</li>
 *     <li>{@code /testnpc remove <id>} — despawns a previously created NPC
 *         by its entity ID</li>
 * </ul>
 * The entity ID is printed after each successful spawn for use with {@code remove}.
 */
public class TestNPCCommand extends PluginCommand {

    /** All equipment slots to copy, in array index order. */
    private static final EquipmentSlot[] EQUIPMENT_SLOTS = {
            EquipmentSlot.MAIN_HAND, EquipmentSlot.OFF_HAND,
            EquipmentSlot.HELMET, EquipmentSlot.CHEST_PLATE,
            EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS
    };

    /** Tracks NPCs spawned via this command, keyed by entity ID for despawn lookup. */
    private final Map<Integer, NPCService.NPC> spawnedNPCs = new HashMap<>();

    /** Active follow tasks, keyed by NPC entity ID. */
    private final Map<Integer, BukkitTask> followTasks = new HashMap<>();

    /** Whether the server supports off-hand equipment (1.9+). */
    private final boolean offHandSupported;

    /**
     * Creates the test NPC command.
     *
     * @param plugin the plugin instance
     */
    public TestNPCCommand(@NonNull BuildBattleAI plugin) {
        super(plugin, "testnpc", "Test command for NPC spawning and despawning",
                "[skinName|remove <id>|follow <id>|stop <id>]");
        offHandSupported = plugin.getContext().getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_9);
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return;
        }
        Player player = (Player) sender;
        BBAINPCService npcService = plugin.getContext().getNpcService();

        // /testnpc remove <id>
        if (args.length >= 1 && "remove".equalsIgnoreCase(args[0])) {
            handleRemove(player, npcService, args);
            return;
        }

        // /testnpc follow <id>
        if (args.length >= 1 && "follow".equalsIgnoreCase(args[0])) {
            handleFollow(player, npcService, args);
            return;
        }

        // /testnpc stop <id>
        if (args.length >= 1 && "stop".equalsIgnoreCase(args[0])) {
            handleStop(player, args);
            return;
        }

        // Capture mutable state on the main thread before going async
        Location loc = player.getLocation();
        ItemStack[] equipment = captureEquipment(player);

        if (args.length == 0) {
            // /testnpc — spawn with own skin (async because createNPC may
            // fall back to Mojang API on older servers where PacketEvents
            // doesn't expose the player's skin textures)
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    NPCService.NPC npc = npcService.createNPC(player, "");
                    npcService.spawn(player, npc, loc);
                    applyEquipment(player, npc, npcService, equipment);
                    spawnedNPCs.put(npc.getId(), npc);
                    player.sendMessage("§aSpawned NPC with ID §e" + npc.getId() + " §a(your skin)");
                } catch (Exception e) {
                    player.sendMessage("§cFailed to create NPC: " + e.getMessage());
                }
            });
            return;
        }

        // /testnpc <skinName> — spawn with skin fetched by name (async HTTP)
        String skinName = args[0];
        player.sendMessage("§7Fetching skin for §e" + skinName + "§7...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                NPCService.NPC npc = npcService.createNPC(skinName, "");
                npcService.spawn(player, npc, loc);
                applyEquipment(player, npc, npcService, equipment);

                spawnedNPCs.put(npc.getId(), npc);
                player.sendMessage("§aSpawned NPC with ID §e" + npc.getId()
                        + " §a(skin: §e" + skinName + "§a)");
            } catch (Exception e) {
                player.sendMessage("§cFailed to fetch skin: " + e.getMessage());
            }
        });
    }

    /**
     * Handles the {@code /testnpc remove <id>} subcommand.
     */
    private void handleRemove(Player player, BBAINPCService npcService, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /testnpc remove <id>");
            return;
        }
        int id;
        try {
            id = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid ID: " + args[1]);
            return;
        }
        NPCService.NPC npc = spawnedNPCs.remove(id);
        if (npc == null) {
            player.sendMessage("§cNPC with ID " + id + " not found.");
            return;
        }
        // Cancel follow task if active
        BukkitTask task = followTasks.remove(id);
        if (task != null) task.cancel();
        npcService.despawn(player, npc);
        player.sendMessage("§aDespawned NPC with ID §e" + id);
    }

    /**
     * Handles the {@code /testnpc follow <id>} subcommand.
     * Starts an async repeating task that moves the NPC toward the player
     * every tick, maintaining a 2-block following distance.
     */
    private void handleFollow(Player player, BBAINPCService npcService, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /testnpc follow <id>");
            return;
        }
        int id;
        try {
            id = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid ID: " + args[1]);
            return;
        }
        NPCService.NPC npc = spawnedNPCs.get(id);
        if (npc == null) {
            player.sendMessage("§cNPC with ID " + id + " not found.");
            return;
        }

        // Cancel existing follow task for this NPC if any
        BukkitTask existing = followTasks.remove(id);
        if (existing != null) existing.cancel();

        // Initialize NPC position: 2 blocks behind the player
        Location playerLoc = player.getLocation();
        double yawRad = Math.toRadians(playerLoc.getYaw());
        Location startLoc = new Location(
                playerLoc.getWorld(),
                playerLoc.getX() + Math.sin(yawRad) * 2,
                playerLoc.getY(),
                playerLoc.getZ() - Math.cos(yawRad) * 2,
                playerLoc.getYaw(), 0
        );
        npcService.teleport(player, npc, startLoc);

        // Mutable position tracker for the async task
        final double[] npcPos = {startLoc.getX(), startLoc.getY(), startLoc.getZ()};

        BukkitTask task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!player.isOnline()) {
                BukkitTask t = followTasks.remove(id);
                if (t != null) t.cancel();
                return;
            }

            Location pLoc = player.getLocation();
            double dx = pLoc.getX() - npcPos[0];
            double dy = pLoc.getY() - npcPos[1];
            double dz = pLoc.getZ() - npcPos[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            // Close enough — don't move
            if (dist <= 2.0) return;

            // Move toward player at ~0.28 blocks/tick (sprinting speed),
            // but cap so the NPC stops 2 blocks away
            double speed = Math.min(0.28, dist - 2.0);
            double mx = (dx / dist) * speed;
            double my = (dy / dist) * speed;
            double mz = (dz / dist) * speed;

            // Yaw facing the movement direction (toward the player)
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

            Location from = new Location(pLoc.getWorld(), npcPos[0], npcPos[1], npcPos[2]);
            Location to = new Location(pLoc.getWorld(),
                    npcPos[0] + mx, npcPos[1] + my, npcPos[2] + mz, yaw, 0);

            npcService.move(player, npc, from, to);

            npcPos[0] += mx;
            npcPos[1] += my;
            npcPos[2] += mz;
        }, 0L, 1L);

        followTasks.put(id, task);
        player.sendMessage("§aNPC §e" + id + " §ais now following you.");
    }

    /**
     * Handles the {@code /testnpc stop <id>} subcommand.
     * Cancels the follow task for the specified NPC.
     */
    private void handleStop(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /testnpc stop <id>");
            return;
        }
        int id;
        try {
            id = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid ID: " + args[1]);
            return;
        }
        BukkitTask task = followTasks.remove(id);
        if (task == null) {
            player.sendMessage("§cNPC with ID " + id + " is not following anyone.");
            return;
        }
        task.cancel();
        player.sendMessage("§aNPC §e" + id + " §astopped following.");
    }

    /**
     * Captures the player's current equipment as an array matching
     * {@link #EQUIPMENT_SLOTS} order. Must be called on the main thread.
     *
     * @param player the player whose equipment to capture
     * @return array of item stacks (may contain nulls for empty slots)
     */
    @SuppressWarnings("deprecation") // getItemInHand() required for 1.8 compatibility
    private ItemStack[] captureEquipment(Player player) {
        PlayerInventory inv = player.getInventory();
        return new ItemStack[]{
                inv.getItemInHand(),
                offHandSupported ? inv.getItemInOffHand() : null,
                inv.getHelmet(),
                inv.getChestplate(),
                inv.getLeggings(),
                inv.getBoots()
        };
    }

    /**
     * Applies captured equipment to an NPC, sending equipment packets to the viewer.
     * Skips null items and off-hand on servers older than 1.9.
     *
     * @param viewer    the player who sees the NPC
     * @param npc       the NPC to equip
     * @param service   the NPC service for sending packets
     * @param equipment the captured equipment array
     */
    private void applyEquipment(Player viewer, NPCService.NPC npc,
                                BBAINPCService service, ItemStack[] equipment) {
        for (int i = 0; i < equipment.length; i++) {
            // Skip off-hand slot (index 1) on pre-1.9 servers
            if (i == 1 && !offHandSupported) continue;
            if (equipment[i] != null)
                service.setEquipment(viewer, npc, EQUIPMENT_SLOTS[i], equipment[i]);
        }
    }

    @Override
    protected List<String> suggest(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("remove");
            suggestions.add("follow");
            suggestions.add("stop");
            for (Player online : Bukkit.getOnlinePlayers())
                suggestions.add(online.getName());
            return filterPrefix(suggestions, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if ("remove".equals(sub) || "follow".equals(sub)) {
                List<String> ids = new ArrayList<>();
                for (Integer id : spawnedNPCs.keySet())
                    ids.add(String.valueOf(id));
                return filterPrefix(ids, args[1]);
            }
            if ("stop".equals(sub)) {
                List<String> ids = new ArrayList<>();
                for (Integer id : followTasks.keySet())
                    ids.add(String.valueOf(id));
                return filterPrefix(ids, args[1]);
            }
        }
        return Collections.emptyList();
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
