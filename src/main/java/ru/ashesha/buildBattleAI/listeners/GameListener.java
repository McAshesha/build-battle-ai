package ru.ashesha.buildBattleAI.listeners;

import lombok.NonNull;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.game.ArenaState;
import ru.ashesha.buildBattleAI.game.GameManager;
import ru.ashesha.buildBattleAI.game.api.BBAIGameManager;
import ru.ashesha.buildBattleAI.game.feedback.SkipThemeItem;

import java.util.Objects;

/**
 * Handles game-related events: block protection, damage cancellation,
 * food level freezing, item drop prevention, and disconnect cleanup.
 * <p>
 * All block place/break events are restricted to the player's assigned
 * zone during the PLAYING state only. Damage and food changes are
 * canceled for all players in game sessions.
 */
public class GameListener extends ListenerService.PluginListener {

    /**
     * Reflective handle for {@code PlayerInteractEvent#getHand()} — added in 1.9
     * together with the off-hand system. {@code null} on 1.8 servers where the
     * method does not exist. Resolved once at class load to avoid a per-event
     * lookup cost on the hot interact path.
     */
    private static final java.lang.reflect.Method GET_HAND_METHOD;

    /**
     * Reflectively resolved {@code EquipmentSlot.HAND} enum constant — also
     * 1.9+. {@code null} on 1.8. Held as {@code Object} so this class compiles
     * and verifies without {@code org.bukkit.inventory.EquipmentSlot} being
     * present on the runtime classpath.
     */
    private static final Object EQUIPMENT_SLOT_HAND;

    static {
        java.lang.reflect.Method handMethod = null;
        Object handConstant = null;
        try {
            handMethod = PlayerInteractEvent.class.getMethod("getHand");
            Class<?> slotClass = Class.forName("org.bukkit.inventory.EquipmentSlot");
            // EquipmentSlot is an enum on every server version that ships it;
            // valueOf is the cheapest cross-version way to obtain the HAND
            // constant without importing the class.
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object resolved = Enum.valueOf((Class<Enum>) slotClass.asSubclass(Enum.class), "HAND");
            handConstant = resolved;
        } catch (Throwable ignored) {
            // 1.8 server — off-hand and EquipmentSlot do not exist. The
            // interact handler will skip its hand filter entirely; on 1.8
            // there is only one hand so the filter is a no-op anyway.
        }
        GET_HAND_METHOD = handMethod;
        EQUIPMENT_SLOT_HAND = handConstant;
    }

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

        // Update the per-plot mirror so the next render reflects this placement.
        // BlockMultiPlaceEvent extends BlockPlaceEvent (single firing) — for
        // doors/beds we must iterate the per-half replaced states.
        if (event instanceof BlockMultiPlaceEvent) {
            BlockMultiPlaceEvent multi = (BlockMultiPlaceEvent) event;
            for (BlockState blockState : multi.getReplacedBlockStates())
                gameManager.applyMirrorPlace(player.getUniqueId(),
                        arenaName, blockState.getBlock());
        } else
            gameManager.applyMirrorPlace(player.getUniqueId(),
                    arenaName, event.getBlockPlaced());
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
        gameManager.applyMirrorBreak(player.getUniqueId(),
                arenaName, event.getBlock());
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
     * Detects right-clicks (air or block) on the skip-theme feather and invokes
     * {@link BBAIGameManager#skipTheme(Player)}.
     * <p>
     * No {@code ignoreCancelled}: Spigot/Paper fires {@code RIGHT_CLICK_AIR}
     * with the event pre-canceled by default (legacy "use" interaction model),
     * so {@code ignoreCancelled=true} would silently swallow every air-click.
     * <p>
     * Hand filter restricts to the main hand so the 1.9+ off-hand mirror-fire
     * does not trigger the skip twice. The filter is implemented via cached
     * reflection ({@link #GET_HAND_METHOD} / {@link #EQUIPMENT_SLOT_HAND}) so
     * 1.8 servers — where {@code PlayerInteractEvent#getHand()} and
     * {@code EquipmentSlot} do not exist — load and run this handler without
     * triggering {@code NoSuchMethodError} or {@code NoClassDefFoundError}
     * during JVM verification of the method body.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK)
            return;

        // Hand filter — 1.9+ only. On 1.8 both static handles are null and
        // we skip the filter entirely (no off-hand exists on 1.8, so the
        // event can only have come from the main hand anyway).
        if (GET_HAND_METHOD != null && EQUIPMENT_SLOT_HAND != null) {
            Object hand;
            try {
                hand = GET_HAND_METHOD.invoke(event);
            } catch (Throwable e) {
                // Reflection should never fail once the lookup succeeded at
                // class load time; bail out defensively rather than scoring
                // a skip the player did not request.
                return;
            }
            if (hand != null && hand != EQUIPMENT_SLOT_HAND)
                return;
        }

        org.bukkit.inventory.ItemStack hand = event.getItem();
        if (hand == null)
            return;

        Player player = event.getPlayer();
        BBAIGameManager gm = plugin.getContext().getGameManager();
        if (!gm.isInGame(player.getUniqueId()))
            return;
        if (gm.getArenaState(gm.getPlayerArena(player.getUniqueId())) != ArenaState.PLAYING)
            return;
        if (!SkipThemeItem.isSkipItem(hand))
            return;

        // Cancel so the feather doesn't, e.g., open a block GUI for whatever
        // the player happens to be facing.
        event.setCancelled(true);
        gm.skipTheme(player);
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
        String worldName = Objects.requireNonNull(event.getLocation().getWorld()).getName();
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
