package ru.ashesha.buildBattleAI.core;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import lombok.NonNull;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.api.BBAINPCService;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PacketEvents-based implementation of {@link BBAINPCService}.
 * <p>
 * Resolves all version-dependent packet constructors once at startup so that
 * no runtime version checks occur in hot paths:
 * <ul>
 *     <li>Player info add/remove — {@code PlayerInfoUpdate} (1.19.3+) vs legacy {@code PlayerInfo}</li>
 *     <li>Player info remove — dedicated {@code PlayerInfoRemove} packet (1.19.3+)
 *         vs {@code PlayerInfo} with {@code REMOVE_PLAYER} action</li>
 *     <li>Entity spawn — unified {@code SpawnEntity} (1.20.2+)
 *         vs dedicated {@code SpawnPlayer}</li>
 *     <li>Skin metadata index — varies across major Minecraft versions (10–17)</li>
 * </ul>
 */
public class NPCService implements BBAINPCService {

    /** The plugin instance used for scheduling and packet sending. */
    private final BuildBattleAI plugin;

    /**
     * Monotonically increasing counter for synthetic entity IDs.
     * Starts from a high range to minimize risk of collision with real server entities.
     */
    private final AtomicInteger entityIdCounter = new AtomicInteger(Integer.MAX_VALUE / 2);

    // ── version-resolved factories and constants ────────────────────────────

    /** Factory for creating "add player to tab list" packets. */
    private final PlayerInfoAddFactory playerInfoAddFactory;

    /** Factory for creating "remove player from tab list" packets. */
    private final PlayerInfoRemoveFactory playerInfoRemoveFactory;

    /** Factory for creating entity spawn packets. */
    private final SpawnFactory spawnFactory;

    /**
     * Version-dependent entity metadata index for the "displayed skin parts" byte.
     * All seven skin layers (cape, jacket, left/right sleeve, left/right pants leg, hat)
     * are encoded as bit flags in a single byte at this index.
     */
    private final int skinLayersIndex;

    /**
     * Creates the NPC service and resolves all version-dependent packet factories.
     * Called once during plugin startup from {@link PluginContext#enable()}.
     *
     * @param plugin the plugin instance
     */
    public NPCService(@NonNull BuildBattleAI plugin) {
        this.plugin = plugin;
        ServerVersion version = PacketEvents.getAPI().getServerManager().getVersion();
        this.playerInfoAddFactory = resolvePlayerInfoAddFactory(version);
        this.playerInfoRemoveFactory = resolvePlayerInfoRemoveFactory(version);
        this.spawnFactory = resolveSpawnFactory(version);
        this.skinLayersIndex = resolveSkinLayersIndex(version);
    }

    // ── public API ──────────────────────────────────────────────────────────

    @Override
    @NonNull
    public NPC createNPC(@NonNull String name, @NonNull Location location,
                         @NonNull String texture, @NonNull String signature) {
        int entityId = entityIdCounter.getAndIncrement();
        UserProfile profile = new UserProfile(UUID.randomUUID(), name);
        profile.setTextureProperties(Collections.singletonList(
                new TextureProperty("textures", texture, signature)
        ));
        return new NPC(entityId, profile, location.clone());
    }

    @Override
    @NonNull
    public NPC createNPC(@NonNull Player skinSource, @NonNull String name, @NonNull Location location) {
        int entityId = entityIdCounter.getAndIncrement();
        UserProfile sourceProfile = plugin.getContext().getUserProfile(skinSource);

        List<TextureProperty> textures = sourceProfile.getTextureProperties();
        if (textures == null || textures.isEmpty()) {
            // On older server versions (notably 1.8), PacketEvents may not expose
            // skin texture properties from the player's profile — fall back to
            // fetching from Mojang API by the player's name.
            String[] skin = fetchSkinByName(skinSource.getName());
            textures = Collections.singletonList(
                    new TextureProperty("textures", skin[0], skin[1])
            );
        }

        UserProfile npcProfile = new UserProfile(UUID.randomUUID(), name);
        npcProfile.setTextureProperties(textures);
        return new NPC(entityId, npcProfile, location.clone());
    }

    @Override
    @NonNull
    public NPC createNPC(@NonNull String skinName, @NonNull String name, @NonNull Location location) {
        String[] skin = fetchSkinByName(skinName);
        return createNPC(name, location, skin[0], skin[1]);
    }

    @Override
    public void spawn(@NonNull Player viewer, @NonNull NPC npc) {
        spawn(Collections.singletonList(viewer), npc);
    }

    @Override
    public void spawn(@NonNull Collection<Player> viewers, @NonNull NPC npc) {
        if (viewers.isEmpty()) return;

        Location loc = npc.location;
        UserProfile profile = npc.profile;
        int entityId = npc.entityId;

        // 1. Add NPC profile to the tab list so the client downloads the skin texture
        PacketWrapper<?> infoAdd = playerInfoAddFactory.create(profile);
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, infoAdd);

        // 2. Spawn the player entity at the NPC location
        PacketWrapper<?> spawn = spawnFactory.create(entityId, profile.getUUID(), loc);
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, spawn);

        // 3. Enable all skin layers (cape, jacket, sleeves, pants, hat = 0x7F)
        EntityData skinLayers = new EntityData(skinLayersIndex, EntityDataTypes.BYTE, (byte) 0x7F);
        WrapperPlayServerEntityMetadata metadata = new WrapperPlayServerEntityMetadata(
                entityId, Collections.singletonList(skinLayers)
        );
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, metadata);

        // 4. Set head rotation to match the NPC's yaw
        WrapperPlayServerEntityHeadLook headLook = new WrapperPlayServerEntityHeadLook(entityId, loc.getYaw());
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, headLook);

        // 5. Send stored equipment so newly spawned viewers see the current gear
        for (Map.Entry<EquipmentSlot, ItemStack> entry : npc.equipment.entrySet()) {
            sendEquipmentPacket(viewers, npc, entry.getKey(), entry.getValue());
        }

        // 6. Remove the NPC from the tab list after 20 ticks (1 second) —
        //    gives the client time to download the skin before we clean up the tab entry.
        //    Async is safe here because PacketEvents packet sending is thread-safe.
        PacketWrapper<?> infoRemove = playerInfoRemoveFactory.create(profile);
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            for (Player viewer : viewers) {
                if (viewer.isOnline())
                    plugin.getContext().sendPacket(viewer, infoRemove);
            }
        }, 20L);
    }

    @Override
    public void despawn(@NonNull Player viewer, @NonNull NPC npc) {
        despawn(Collections.singletonList(viewer), npc);
    }

    @Override
    public void despawn(@NonNull Collection<Player> viewers, @NonNull NPC npc) {
        if (viewers.isEmpty()) return;

        WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(npc.entityId);
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, destroy);
    }

    @Override
    public void setEquipment(@NonNull Player viewer, @NonNull NPC npc,
                             @NonNull EquipmentSlot slot, ItemStack item) {
        setEquipment(Collections.singletonList(viewer), npc, slot, item);
    }

    @Override
    public void setEquipment(@NonNull Collection<Player> viewers, @NonNull NPC npc,
                             @NonNull EquipmentSlot slot, ItemStack item) {
        if (viewers.isEmpty()) return;

        // Update the NPC's stored equipment state
        if (item == null) {
            npc.equipment.remove(slot);
        } else {
            npc.equipment.put(slot, item.clone());
        }

        sendEquipmentPacket(viewers, npc, slot, item);
    }

    @Override
    public void shutdown() {
        // Scheduled tab-removal tasks are bound to the plugin's BukkitScheduler
        // and are cancelled automatically when the plugin is disabled.
    }

    // ── internal helpers ────────────────────────────────────────────────────

    /**
     * Sends an equipment packet to viewers without modifying stored equipment state.
     *
     * @param viewers the players to send the equipment packet to
     * @param npc     the NPC whose equipment is being displayed
     * @param slot    the equipment slot to update
     * @param item    the Bukkit item to display, or {@code null} for an empty slot
     */
    private void sendEquipmentPacket(Collection<Player> viewers, NPC npc,
                                     EquipmentSlot slot, ItemStack item) {
        com.github.retrooper.packetevents.protocol.item.ItemStack peItem = item == null
                ? com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY
                : SpigotConversionUtil.fromBukkitItemStack(item);

        List<Equipment> equipmentList = Collections.singletonList(new Equipment(slot, peItem));
        WrapperPlayServerEntityEquipment packet = new WrapperPlayServerEntityEquipment(
                npc.entityId, equipmentList
        );
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, packet);
    }

    /**
     * Fetches skin texture and signature from the Mojang API for the given player name.
     * <p>
     * Performs two sequential HTTP requests:
     * <ol>
     *     <li>Resolves the player name to a UUID via the Mojang username API</li>
     *     <li>Fetches the signed profile (with skin textures) via the session server</li>
     * </ol>
     * <b>This method is blocking</b> — call from an async context.
     *
     * @param name the Minecraft player name to look up
     * @return a two-element array: {@code [texture, signature]}
     * @throws IllegalArgumentException if the player name is not found
     * @throws RuntimeException         if the Mojang API request fails
     */
    private static String[] fetchSkinByName(String name) {
        try {
            // Step 1: Resolve player name to UUID
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "https://api.mojang.com/users/profiles/minecraft/" + name
            ).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() != 200) {
                throw new IllegalArgumentException("Player not found: " + name);
            }

            //noinspection deprecation — instance parse() is required for Gson 2.2.4 (Spigot 1.8)
            JsonObject nameResponse = new JsonParser()
                    .parse(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String uuid = nameResponse.get("id").getAsString();
            conn.disconnect();

            // Step 2: Fetch signed profile with skin textures
            conn = (HttpURLConnection) new URL(
                    "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false"
            ).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() != 200) {
                throw new RuntimeException("Failed to fetch profile for UUID: " + uuid);
            }

            //noinspection deprecation
            JsonObject profileResponse = new JsonParser()
                    .parse(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonArray properties = profileResponse.getAsJsonArray("properties");
            conn.disconnect();

            // Find the "textures" property containing the skin data
            for (int i = 0; i < properties.size(); i++) {
                JsonObject prop = properties.get(i).getAsJsonObject();
                if ("textures".equals(prop.get("name").getAsString())) {
                    return new String[]{
                            prop.get("value").getAsString(),
                            prop.get("signature").getAsString()
                    };
                }
            }

            throw new RuntimeException("No textures property found for player: " + name);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch skin for player: " + name, e);
        }
    }

    // ── version-resolved factory builders ───────────────────────────────────

    /**
     * Resolves the factory for adding NPC profiles to the tab list.
     * <ul>
     *     <li>1.19.3+ — {@code WrapperPlayServerPlayerInfoUpdate} with
     *         {@code ADD_PLAYER} and {@code UPDATE_LISTED} actions</li>
     *     <li>&lt;1.19.3 — legacy {@code WrapperPlayServerPlayerInfo}
     *         with {@code ADD_PLAYER} action</li>
     * </ul>
     */
    private PlayerInfoAddFactory resolvePlayerInfoAddFactory(ServerVersion version) {
        if (version.isNewerThanOrEquals(ServerVersion.V_1_19_3))
            return profile -> {
                WrapperPlayServerPlayerInfoUpdate.PlayerInfo info =
                        new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                                profile, true, 0, GameMode.SURVIVAL, null, null
                        );
                // ADD_PLAYER creates the profile entry; UPDATE_LISTED ensures the
                // client shows it in the tab list (needed for skin texture download)
                return new WrapperPlayServerPlayerInfoUpdate(
                        EnumSet.of(
                                WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED
                        ),
                        Collections.singletonList(info)
                );
            };
        return profile -> {
            WrapperPlayServerPlayerInfo.PlayerData data =
                    new WrapperPlayServerPlayerInfo.PlayerData(
                            null, profile, GameMode.SURVIVAL, 0
                    );
            return new WrapperPlayServerPlayerInfo(
                    WrapperPlayServerPlayerInfo.Action.ADD_PLAYER,
                    Collections.singletonList(data)
            );
        };
    }

    /**
     * Resolves the factory for removing NPC profiles from the tab list.
     * <ul>
     *     <li>1.19.3+ — dedicated {@code WrapperPlayServerPlayerInfoRemove} packet</li>
     *     <li>&lt;1.19.3 — legacy {@code WrapperPlayServerPlayerInfo}
     *         with {@code REMOVE_PLAYER} action</li>
     * </ul>
     */
    private PlayerInfoRemoveFactory resolvePlayerInfoRemoveFactory(ServerVersion version) {
        if (version.isNewerThanOrEquals(ServerVersion.V_1_19_3))
            return profile -> new WrapperPlayServerPlayerInfoRemove(profile.getUUID());
        return profile -> {
            WrapperPlayServerPlayerInfo.PlayerData data =
                    new WrapperPlayServerPlayerInfo.PlayerData(
                            null, profile, GameMode.SURVIVAL, 0
                    );
            return new WrapperPlayServerPlayerInfo(
                    WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER,
                    Collections.singletonList(data)
            );
        };
    }

    /**
     * Resolves the factory for spawning player entities.
     * <ul>
     *     <li>1.20.2+ — {@code WrapperPlayServerSpawnEntity} with {@code EntityTypes.PLAYER}
     *         (the dedicated spawn-player packet was merged into the general spawn packet)</li>
     *     <li>&lt;1.20.2 — dedicated {@code WrapperPlayServerSpawnPlayer}</li>
     * </ul>
     */
    private SpawnFactory resolveSpawnFactory(ServerVersion version) {
        if (version.isNewerThanOrEquals(ServerVersion.V_1_20_2))
            return (entityId, uuid, loc) -> new WrapperPlayServerSpawnEntity(
                    entityId, Optional.of(uuid), EntityTypes.PLAYER,
                    new Vector3d(loc.getX(), loc.getY(), loc.getZ()),
                    loc.getPitch(), loc.getYaw(), loc.getYaw(),
                    0, Optional.empty()
            );
        return (entityId, uuid, loc) -> new WrapperPlayServerSpawnPlayer(
                entityId, uuid,
                new com.github.retrooper.packetevents.protocol.world.Location(
                        loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()
                )
        );
    }

    /**
     * Resolves the entity metadata index for the "displayed skin parts" byte.
     * <p>
     * This index has shifted across major Minecraft versions as new metadata
     * fields were added before the skin-parts entry:
     * <pre>
     *   1.8.x      → index 10
     *   1.9.x      → index 12
     *   1.10–1.12  → index 13
     *   1.13.x     → index 15
     *   1.14–1.16  → index 16
     *   1.17+      → index 17
     * </pre>
     */
    private int resolveSkinLayersIndex(ServerVersion version) {
        if (version.isNewerThanOrEquals(ServerVersion.V_1_17))
            return 17;
        if (version.isNewerThanOrEquals(ServerVersion.V_1_14))
            return 16;
        if (version.isNewerThanOrEquals(ServerVersion.V_1_13))
            return 15;
        if (version.isNewerThanOrEquals(ServerVersion.V_1_10))
            return 13;
        if (version.isNewerThanOrEquals(ServerVersion.V_1_9))
            return 12;
        return 10;
    }

    // ── version-dispatched functional interfaces ────────────────────────────

    /**
     * Factory for creating "add player to tab list" packets.
     * Resolved once at startup based on the server version.
     */
    @FunctionalInterface
    private interface PlayerInfoAddFactory {
        PacketWrapper<?> create(UserProfile profile);
    }

    /**
     * Factory for creating "remove player from tab list" packets.
     * Resolved once at startup based on the server version.
     */
    @FunctionalInterface
    private interface PlayerInfoRemoveFactory {
        PacketWrapper<?> create(UserProfile profile);
    }

    /**
     * Factory for creating entity spawn packets.
     * Resolved once at startup based on the server version.
     */
    @FunctionalInterface
    private interface SpawnFactory {
        PacketWrapper<?> create(int entityId, UUID uuid, Location location);
    }

    // ── NPC inner class ─────────────────────────────────────────────────────

    /**
     * Packet-based fake-player NPC.
     * <p>
     * NPCs exist only in clients' rendering — they are not real server entities.
     * The server never tracks these entities; all visibility is controlled via
     * player-info, spawn, metadata, and destroy packets sent per-viewer.
     * <p>
     * Equipment is stored internally and sent automatically when the NPC
     * is spawned for new viewers via {@link NPCService#spawn}.
     * <p>
     * Create instances via the {@link BBAINPCService} create methods.
     */
    public static final class NPC {

        /** Synthetic entity ID used in all packets referencing this NPC. */
        private final int entityId;

        /**
         * PacketEvents user profile carrying the NPC's UUID, display name,
         * and skin texture properties.
         */
        private final UserProfile profile;

        /**
         * The world position and facing direction (yaw/pitch) of this NPC.
         * Stored as a defensive copy — mutations to the original location
         * do not affect the NPC.
         */
        private final Location location;

        /**
         * Equipment currently displayed on this NPC, keyed by slot.
         * Updated via {@link NPCService#setEquipment} and sent automatically
         * during {@link NPCService#spawn}.
         */
        private final Map<EquipmentSlot, ItemStack> equipment = new HashMap<>();

        /**
         * Package-private constructor — instances are created by {@link NPCService}.
         *
         * @param entityId the synthetic entity ID
         * @param profile  the user profile with skin textures
         * @param location the world position (already cloned by the caller)
         */
        NPC(int entityId, UserProfile profile, Location location) {
            this.entityId = entityId;
            this.profile = profile;
            this.location = location;
        }

        /**
         * Returns the synthetic entity ID used in packets for this NPC.
         *
         * @return the entity ID
         */
        public int getId() {
            return entityId;
        }

        /**
         * Returns a defensive copy of the NPC's world position and facing direction.
         * <p>
         * The returned {@link Location} is a clone — mutations do not affect
         * the NPC's actual position.
         *
         * @return a cloned copy of the NPC's location
         */
        public Location getLocation() {
            return location.clone();
        }

        /**
         * Returns an unmodifiable view of the NPC's current equipment.
         * <p>
         * The map is keyed by {@link EquipmentSlot} and contains the Bukkit
         * {@link ItemStack} displayed in each slot. Empty slots are absent
         * from the map.
         *
         * @return unmodifiable map of the NPC's equipment
         */
        public Map<EquipmentSlot, ItemStack> getEquipment() {
            return Collections.unmodifiableMap(equipment);
        }
    }
}
