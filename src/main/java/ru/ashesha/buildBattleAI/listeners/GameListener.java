package ru.ashesha.buildBattleAI.listeners;

import lombok.NonNull;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.game.ArenaState;
import ru.ashesha.buildBattleAI.game.GameManager;
import ru.ashesha.buildBattleAI.game.api.BBAIGameManager;

/**
 * Handles game-related events: block protection, damage cancellation,
 * food level freezing, item drop prevention, and disconnect cleanup.
 * <p>
 * All block place/break events are restricted to the player's assigned
 * zone during the PLAYING state only. Damage and food changes are
 * cancelled for all players in game sessions.
 */
public class GameListener extends ListenerService.PluginListener {

    /**
     * Creates the game listener.
     *
     * @param plugin the plugin instance
     */
    public GameListener(@NonNull BuildBattleAI plugin) {
        super(plugin);
    }

    /**
     * Allows block placement only within the player's zone during PLAYING.
     * Sets the zone dirty flag for the render pipeline.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        BBAIGameManager gm = plugin.getContext().getGameManager();
        if (!gm.isInGame(player.getUniqueId()))
            return;

        String arenaName = gm.getPlayerArena(player.getUniqueId());
        ArenaState state = gm.getArenaState(arenaName);

        // Only allow during PLAYING
        if (state != ArenaState.PLAYING) {
            event.setCancelled(true);
            return;
        }

        // Check if block is within the player's zone
        Location loc = event.getBlock().getLocation();
        Arena arena = plugin.getContext().getArenaManager().getArena(arenaName);
        if (arena == null) {
            event.setCancelled(true);
            return;
        }

        GameManager gameManager = (GameManager) gm;
        // Find the player's plot via the game manager
        int plotIndex = getPlayerPlotIndex(player, gameManager, arenaName);
        if (plotIndex < 0 || plotIndex >= arena.plots().size()) {
            event.setCancelled(true);
            return;
        }

        Arena.PlotData plot = arena.plots().get(plotIndex);
        if (!GameManager.isInZone(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), plot)) {
            event.setCancelled(true);
            return;
        }

        // Mark zone as dirty for the render pipeline
        markZoneDirty(player, gameManager, arenaName);
    }

    /**
     * Allows block breaking only within the player's zone during PLAYING.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        BBAIGameManager gm = plugin.getContext().getGameManager();
        if (!gm.isInGame(player.getUniqueId()))
            return;

        String arenaName = gm.getPlayerArena(player.getUniqueId());
        ArenaState state = gm.getArenaState(arenaName);

        if (state != ArenaState.PLAYING) {
            event.setCancelled(true);
            return;
        }

        Location loc = event.getBlock().getLocation();
        Arena arena = plugin.getContext().getArenaManager().getArena(arenaName);
        if (arena == null) {
            event.setCancelled(true);
            return;
        }

        GameManager gameManager = (GameManager) gm;
        int plotIndex = getPlayerPlotIndex(player, gameManager, arenaName);
        if (plotIndex < 0 || plotIndex >= arena.plots().size()) {
            event.setCancelled(true);
            return;
        }

        Arena.PlotData plot = arena.plots().get(plotIndex);
        if (!GameManager.isInZone(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), plot)) {
            event.setCancelled(true);
            return;
        }

        markZoneDirty(player, gameManager, arenaName);
    }

    /**
     * Cancels all damage for players in game sessions.
     * Uses HIGH priority to prevent death before other plugins process.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player))
            return;
        Player player = (Player) event.getEntity();
        if (plugin.getContext().getGameManager().isInGame(player.getUniqueId()))
            event.setCancelled(true);
    }

    /**
     * Prevents food level changes for players in game sessions.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player))
            return;
        Player player = (Player) event.getEntity();
        if (plugin.getContext().getGameManager().isInGame(player.getUniqueId()))
            event.setCancelled(true);
    }

    /**
     * Prevents item drops for players in game sessions.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (plugin.getContext().getGameManager().isInGame(event.getPlayer().getUniqueId()))
            event.setCancelled(true);
    }

    /**
     * Handles player disconnect during a game — restores state via leaveArena.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (plugin.getContext().getGameManager().isInGame(player.getUniqueId()))
            plugin.getContext().getGameManager().leaveArena(player);
    }

    /**
     * Cancels block explosions (TNT) in arena worlds.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        String worldName = event.getBlock().getWorld().getName();
        if (worldName.startsWith("bbai_"))
            event.setCancelled(true);
    }

    /**
     * Cancels entity explosions (creepers, fireballs) in arena worlds.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        String worldName = event.getLocation().getWorld().getName();
        if (worldName.startsWith("bbai_"))
            event.setCancelled(true);
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Retrieves the player's plot index from the game session.
     * Uses reflection-free access through the package-visible GameManager.
     *
     * @return the 0-based plot index, or -1 if not found
     */
    private int getPlayerPlotIndex(Player player, GameManager gameManager, String arenaName) {
        // Access the internal session to get the plot index
        // GameManager.isInZone is static and package-visible from GameManager
        // We delegate to GameManager for plot lookup
        return gameManager.getPlayerPlotIndex(player.getUniqueId(), arenaName);
    }

    /**
     * Marks the player's zone as dirty in the game session.
     */
    private void markZoneDirty(Player player, GameManager gameManager, String arenaName) {
        gameManager.markPlayerZoneDirty(player.getUniqueId(), arenaName);
    }
}
