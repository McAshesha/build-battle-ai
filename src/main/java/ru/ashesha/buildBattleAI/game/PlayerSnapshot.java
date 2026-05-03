package ru.ashesha.buildBattleAI.game;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Captures and restores a player's full state before entering a game session.
 * <p>
 * Handles multi-version differences:
 * <ul>
 *     <li>Off-hand slot exists only on 1.9+ — gated by {@link ServerVersion} check</li>
 *     <li>{@code getContents()}/{@code setContents()} layout differs but saving
 *         and restoring the same array works correctly across versions</li>
 * </ul>
 * All mutable objects (ItemStacks, PotionEffects) are deep-cloned to prevent
 * reference sharing with the live player object.
 */
@Getter
@Accessors(fluent = true)
class PlayerSnapshot {

    private final String worldName;
    private final double x, y, z;
    private final float yaw, pitch;
    private final GameMode gameMode;
    private final ItemStack[] inventoryContents;
    private final ItemStack[] armorContents;
    private final List<PotionEffect> potionEffects;
    private final int level;
    private final float exp;
    private final double health;
    private final int foodLevel;
    private final float saturation;
    private final boolean allowFlight;
    private final boolean flying;
    private final int fireTicks;
    /** Off-hand item, or {@code null} on 1.8 servers. */
    private final ItemStack offHand;

    private PlayerSnapshot(String worldName, double x, double y, double z,
                           float yaw, float pitch, GameMode gameMode,
                           ItemStack[] inventoryContents, ItemStack[] armorContents,
                           List<PotionEffect> potionEffects, int level, float exp,
                           double health, int foodLevel, float saturation,
                           boolean allowFlight, boolean flying, int fireTicks,
                           ItemStack offHand) {
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.gameMode = gameMode;
        this.inventoryContents = inventoryContents;
        this.armorContents = armorContents;
        this.potionEffects = potionEffects;
        this.level = level;
        this.exp = exp;
        this.health = health;
        this.foodLevel = foodLevel;
        this.saturation = saturation;
        this.allowFlight = allowFlight;
        this.flying = flying;
        this.fireTicks = fireTicks;
        this.offHand = offHand;
    }

    /**
     * Captures the player's current state. All mutable values are cloned.
     *
     * @param player  the player to snapshot
     * @param version the server version (for off-hand gating)
     * @return a new snapshot
     */
    static PlayerSnapshot capture(Player player, ServerVersion version) {
        Location loc = player.getLocation();

        // Clone inventory contents
        ItemStack[] inv = cloneItemArray(player.getInventory().getContents());
        ItemStack[] armor = cloneItemArray(player.getInventory().getArmorContents());

        // Off-hand: only on 1.9+
        ItemStack offHand = null;
        if (version.isNewerThanOrEquals(ServerVersion.V_1_9)) {
            ItemStack raw = player.getInventory().getItemInOffHand();
            if (raw != null)
                offHand = raw.clone();
        }

        // Clone potion effects
        List<PotionEffect> effects = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects())
            effects.add(new PotionEffect(
                    effect.getType(), effect.getDuration(), effect.getAmplifier(),
                    effect.isAmbient(), effect.hasParticles()));

        return new PlayerSnapshot(
                loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch(),
                player.getGameMode(),
                inv, armor, effects,
                player.getLevel(), player.getExp(),
                player.getHealth(), player.getFoodLevel(),
                player.getSaturation(), player.getAllowFlight(),
                player.isFlying(), player.getFireTicks(),
                offHand
        );
    }

    /**
     * Restores the player to the captured state.
     * Teleports back, restores inventory, effects, gamemode, and all stats.
     *
     * @param player  the player to restore
     * @param version the server version (for off-hand gating)
     */
    void restore(Player player, ServerVersion version) {
        // Teleport to saved location
        World world = Bukkit.getWorld(worldName);
        if (world != null)
            player.teleport(new Location(world, x, y, z, yaw, pitch));
        else
            player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());

        // Restore gamemode first (affects inventory behavior)
        player.setGameMode(gameMode);

        // Restore inventory
        player.getInventory().setContents(cloneItemArray(inventoryContents));
        player.getInventory().setArmorContents(cloneItemArray(armorContents));
        if (version.isNewerThanOrEquals(ServerVersion.V_1_9) && offHand != null)
            player.getInventory().setItemInOffHand(offHand.clone());

        // Clear existing effects, then restore saved ones
        for (PotionEffect active : player.getActivePotionEffects())
            player.removePotionEffect(active.getType());
        for (PotionEffect effect : potionEffects)
            player.addPotionEffect(new PotionEffect(
                    effect.getType(), effect.getDuration(), effect.getAmplifier(),
                    effect.isAmbient(), effect.hasParticles()));

        // Restore stats
        player.setLevel(level);
        player.setExp(exp);
        player.setHealth(health);
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setFireTicks(fireTicks);

        // Restore flight (order matters: allowFlight before flying)
        player.setAllowFlight(allowFlight);
        player.setFlying(flying && allowFlight);
    }

    /**
     * Deep-clones an array of ItemStacks, handling null entries.
     */
    private static ItemStack[] cloneItemArray(ItemStack[] original) {
        if (original == null)
            return new ItemStack[0];
        ItemStack[] clone = new ItemStack[original.length];
        for (int i = 0; i < original.length; i++)
            if (original[i] != null)
                clone[i] = original[i].clone();
        return clone;
    }
}
