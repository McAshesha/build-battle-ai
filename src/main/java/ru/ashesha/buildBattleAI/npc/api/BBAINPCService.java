package ru.ashesha.buildBattleAI.npc.api;

import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import lombok.NonNull;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.ashesha.buildBattleAI.npc.NPCService;

import java.util.Collection;

/**
 * Service for creating and managing client-side fake-player NPCs via packets.
 * <p>
 * NPCs are purely packet-based entities that exist only in individual clients'
 * rendering — the server never tracks them. Each viewer can see the same NPC
 * at a different position and with different equipment; all state management
 * is the caller's responsibility.
 * <p>
 * NPCs are spawned using the standard Minecraft player-spawn protocol sequence:
 * player info (tab list entry with skin textures), entity spawn, skin-layers
 * metadata, and head rotation. After a short delay the tab list entry is removed
 * so the NPC does not pollute the player list while remaining visible in the world.
 * <p>
 * All packets are sent through PacketEvents, providing fully cross-version NPC
 * display (1.8–1.21+) without NMS reflection.
 */
public interface BBAINPCService {

    /**
     * Creates a new NPC with the given raw skin textures.
     * <p>
     * The NPC is not yet visible to any player — call {@link #spawn} to show it.
     * Each call allocates a unique entity ID and UUID for the NPC.
     *
     * @param name      the display name shown above the NPC's head, or empty for none
     * @param texture   Base64-encoded skin texture value from a Mojang profile
     * @param signature Base64 RSA signature of the texture value
     * @return a new NPC instance ready to be spawned
     */
    @NonNull
    NPCService.NPC createNPC(@NonNull String name,
                             @NonNull String texture, @NonNull String signature);

    /**
     * Creates a new NPC using the skin of an online player.
     * <p>
     * Copies the skin texture properties from the given player's PacketEvents
     * {@link com.github.retrooper.packetevents.protocol.player.UserProfile}.
     * On older server versions (notably 1.8) where PacketEvents does not expose
     * texture properties, this method falls back to fetching the skin from the
     * Mojang API by the player's name — <b>this fallback performs blocking HTTP
     * requests</b>, so call from an async context when targeting pre-1.13 servers.
     *
     * @param skinSource the online player whose skin will be used
     * @param name       the display name shown above the NPC's head, or empty for none
     * @return a new NPC instance ready to be spawned
     */
    @NonNull
    NPCService.NPC createNPC(@NonNull Player skinSource, @NonNull String name);

    /**
     * Creates a new NPC by fetching the skin from the Mojang API by player name.
     * <p>
     * <b>This method performs blocking HTTP requests to the Mojang API.</b>
     * Call it from an async context to avoid stalling the main thread.
     *
     * @param skinName the Minecraft player name whose skin will be fetched
     * @param name     the display name shown above the NPC's head, or empty for none
     * @return a new NPC instance ready to be spawned
     * @throws IllegalArgumentException if the player name is not found on Mojang servers
     * @throws RuntimeException         if the Mojang API request fails
     */
    @NonNull
    NPCService.NPC createNPC(@NonNull String skinName, @NonNull String name);

    /**
     * Shows the NPC to the specified viewer at the given location.
     * <p>
     * Sends player-info, spawn, skin-layers metadata, and head-rotation packets.
     * The tab list entry is automatically removed after a short delay so the client
     * has time to download the skin texture.
     *
     * @param viewer   the player who will see the NPC
     * @param npc      the NPC to display
     * @param location the world position and facing direction (yaw/pitch)
     */
    void spawn(@NonNull Player viewer, @NonNull NPCService.NPC npc, @NonNull Location location);

    /**
     * Shows the NPC to the specified viewers at the given location.
     *
     * @param viewers  the players who will see the NPC
     * @param npc      the NPC to display
     * @param location the world position and facing direction (yaw/pitch)
     * @see #spawn(Player, NPCService.NPC, Location)
     */
    void spawn(@NonNull Collection<Player> viewers, @NonNull NPCService.NPC npc, @NonNull Location location);

    /**
     * Removes the NPC from the specified viewer's client.
     * Sends an entity destroy packet to the viewer.
     *
     * @param viewer the player who will no longer see the NPC
     * @param npc    the NPC to hide
     */
    void despawn(@NonNull Player viewer, @NonNull NPCService.NPC npc);

    /**
     * Removes the NPC from the specified viewers' clients.
     *
     * @param viewers the players who will no longer see the NPC
     * @param npc     the NPC to hide
     * @see #despawn(Player, NPCService.NPC)
     */
    void despawn(@NonNull Collection<Player> viewers, @NonNull NPCService.NPC npc);

    /**
     * Teleports the NPC to an absolute position for the specified viewer.
     * <p>
     * Uses the entity teleport packet, which supports any distance. After the
     * teleport, a head-look packet is sent to update the NPC's head rotation.
     *
     * @param viewer      the player who will see the NPC move
     * @param npc         the NPC to teleport
     * @param destination the target position and facing direction (yaw/pitch)
     */
    void teleport(@NonNull Player viewer, @NonNull NPCService.NPC npc, @NonNull Location destination);

    /**
     * Teleports the NPC to an absolute position for the specified viewers.
     *
     * @param viewers     the players who will see the NPC move
     * @param npc         the NPC to teleport
     * @param destination the target position and facing direction (yaw/pitch)
     * @see #teleport(Player, NPCService.NPC, Location)
     */
    void teleport(@NonNull Collection<Player> viewers, @NonNull NPCService.NPC npc, @NonNull Location destination);

    /**
     * Moves the NPC with a relative movement packet for the specified viewer.
     * <p>
     * Uses the entity relative-move-and-rotation packet when the delta is within
     * 8 blocks per axis. If the distance exceeds that limit, automatically falls
     * back to a teleport packet. After the move, a head-look packet is sent to
     * update the NPC's head rotation.
     *
     * @param viewer the player who will see the NPC move
     * @param npc    the NPC to move
     * @param from   the NPC's current position (as known by this viewer's client)
     * @param to     the target position and facing direction (yaw/pitch)
     */
    void move(@NonNull Player viewer, @NonNull NPCService.NPC npc,
              @NonNull Location from, @NonNull Location to);

    /**
     * Moves the NPC with a relative movement packet for the specified viewers.
     *
     * @param viewers the players who will see the NPC move
     * @param npc     the NPC to move
     * @param from    the NPC's current position (as known by these viewers' clients)
     * @param to      the target position and facing direction (yaw/pitch)
     * @see #move(Player, NPCService.NPC, Location, Location)
     */
    void move(@NonNull Collection<Player> viewers, @NonNull NPCService.NPC npc,
              @NonNull Location from, @NonNull Location to);

    /**
     * Updates an equipment slot on the NPC for the specified viewer.
     * <p>
     * Sends an equipment packet to display the item in the given slot.
     * No state is stored on the NPC — the caller is responsible for tracking
     * which equipment each viewer sees.
     *
     * @param viewer the player who will see the equipment change
     * @param npc    the NPC to equip
     * @param slot   the equipment slot to update
     * @param item   the Bukkit item to display, or {@code null} to clear the slot
     */
    void setEquipment(@NonNull Player viewer, @NonNull NPCService.NPC npc,
                      @NonNull EquipmentSlot slot, ItemStack item);

    /**
     * Updates an equipment slot on the NPC for the specified viewers.
     *
     * @param viewers the players who will see the equipment change
     * @param npc     the NPC to equip
     * @param slot    the equipment slot to update
     * @param item    the Bukkit item to display, or {@code null} to clear the slot
     * @see #setEquipment(Player, NPCService.NPC, EquipmentSlot, ItemStack)
     */
    void setEquipment(@NonNull Collection<Player> viewers, @NonNull NPCService.NPC npc,
                      @NonNull EquipmentSlot slot, ItemStack item);

}
