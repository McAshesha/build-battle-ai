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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
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
import java.util.List;
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
    public NPC createNPC(@NonNull String name,
                         @NonNull String texture, @NonNull String signature) {
        int entityId = entityIdCounter.getAndIncrement();
        UserProfile profile = new UserProfile(UUID.randomUUID(), name);
        profile.setTextureProperties(Collections.singletonList(
                new TextureProperty("textures", texture, signature)
        ));
        return new NPC(entityId, profile);
    }

    @Override
    @NonNull
    public NPC createNPC(@NonNull Player skinSource, @NonNull String name) {
        int entityId = entityIdCounter.getAndIncrement();
        UserProfile sourceProfile = plugin.getContext().getUserProfile(skinSource);

        List<TextureProperty> textures = sourceProfile.getTextureProperties();
        if (textures.isEmpty()) {
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
        return new NPC(entityId, npcProfile);
    }

    @Override
    @NonNull
    public NPC createNPC(@NonNull String skinName, @NonNull String name) {
        String[] skin = fetchSkinByName(skinName);
        return createNPC(name, skin[0], skin[1]);
    }

    @Override
    public void spawn(@NonNull Player viewer, @NonNull NPC npc, @NonNull Location location) {
        spawn(Collections.singletonList(viewer), npc, location);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public void spawn(@NonNull Collection<Player> viewers, @NonNull NPC npc, @NonNull Location location) {
        if (viewers.isEmpty()) return;

        UserProfile profile = npc.profile;
        int entityId = npc.entityId;

        // 1. Add NPC profile to the tab list so the client downloads the skin texture
        PacketWrapper<?> infoAdd = playerInfoAddFactory.create(profile);
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, infoAdd);

        // 2. Spawn the player entity at the given location
        PacketWrapper<?> spawn = spawnFactory.create(entityId, profile.getUUID(), location);
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, spawn);

        // 3. Enable all skin layers (cape, jacket, sleeves, pants, hat = 0x7F)
        EntityData skinLayers = new EntityData(skinLayersIndex, EntityDataTypes.BYTE, (byte) 0x7F);
        WrapperPlayServerEntityMetadata metadata = new WrapperPlayServerEntityMetadata(
                entityId, Collections.singletonList(skinLayers)
        );
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, metadata);

        // 4. Set head rotation to match the spawn yaw
        WrapperPlayServerEntityHeadLook headLook = new WrapperPlayServerEntityHeadLook(entityId, location.getYaw());
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, headLook);

        // 5. Remove the NPC from the tab list after 20 ticks (1 second) —
        //    gives the client time to download the skin before we clean up the tab entry.
        //    Async is safe here because PacketEvents packet sending is thread-safe.
        PacketWrapper<?> infoRemove = playerInfoRemoveFactory.create(profile);
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            for (Player viewer : viewers)
                if (viewer.isOnline())
                    plugin.getContext().sendPacket(viewer, infoRemove);
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
    public void teleport(@NonNull Player viewer, @NonNull NPC npc, @NonNull Location destination) {
        teleport(Collections.singletonList(viewer), npc, destination);
    }

    @Override
    public void teleport(@NonNull Collection<Player> viewers, @NonNull NPC npc,
                         @NonNull Location destination) {
        if (viewers.isEmpty()) return;

        int entityId = npc.entityId;

        // Send absolute-position teleport packet
        WrapperPlayServerEntityTeleport teleportPacket = new WrapperPlayServerEntityTeleport(
                entityId,
                new Vector3d(destination.getX(), destination.getY(), destination.getZ()),
                destination.getYaw(), destination.getPitch(), true
        );
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, teleportPacket);

        // Update head rotation to match the new facing direction
        WrapperPlayServerEntityHeadLook headLook =
                new WrapperPlayServerEntityHeadLook(entityId, destination.getYaw());
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, headLook);
    }

    @Override
    public void move(@NonNull Player viewer, @NonNull NPC npc,
                     @NonNull Location from, @NonNull Location to) {
        move(Collections.singletonList(viewer), npc, from, to);
    }

    @Override
    public void move(@NonNull Collection<Player> viewers, @NonNull NPC npc,
                     @NonNull Location from, @NonNull Location to) {
        if (viewers.isEmpty()) return;

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();

        // Fall back to teleport if delta exceeds the relative-move limit of 8 blocks
        if (Math.abs(dx) > 8 || Math.abs(dy) > 8 || Math.abs(dz) > 8) {
            teleport(viewers, npc, to);
            return;
        }

        int entityId = npc.entityId;

        // Send relative-move-and-rotation packet
        WrapperPlayServerEntityRelativeMoveAndRotation movePacket =
                new WrapperPlayServerEntityRelativeMoveAndRotation(
                        entityId, dx, dy, dz, to.getYaw(), to.getPitch(), true
                );
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, movePacket);

        // Update head rotation to match the new facing direction
        WrapperPlayServerEntityHeadLook headLook =
                new WrapperPlayServerEntityHeadLook(entityId, to.getYaw());
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, headLook);
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
        sendEquipmentPacket(viewers, npc, slot, item);
    }

    @Override
    public void shutdown() {
        // Scheduled tab-removal tasks are bound to the plugin's BukkitScheduler
        // and are canceled automatically when the plugin is disabled.
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

            if (conn.getResponseCode() != 200)
                throw new IllegalArgumentException("Player not found: " + name);

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

            if (conn.getResponseCode() != 200)
                throw new RuntimeException("Failed to fetch profile for UUID: " + uuid);

            //noinspection deprecation
            JsonObject profileResponse = new JsonParser()
                    .parse(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonArray properties = profileResponse.getAsJsonArray("properties");
            conn.disconnect();

            // Find the "textures" property containing the skin data
            for (int i = 0; i < properties.size(); i++) {
                JsonObject prop = properties.get(i).getAsJsonObject();
                if ("textures".equals(prop.get("name").getAsString()))
                    return new String[]{
                            prop.get("value").getAsString(),
                            prop.get("signature").getAsString()
                    };
            }

            throw new RuntimeException("No textures property found for player: " + name);
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
     * The server never tracks these entities; all visibility, position, and equipment
     * state is managed by the caller and communicated via packets sent per-viewer.
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
         * Package-private constructor — instances are created by {@link NPCService}.
         *
         * @param entityId the synthetic entity ID
         * @param profile  the user profile with skin textures
         */
        NPC(int entityId, UserProfile profile) {
            this.entityId = entityId;
            this.profile = profile;
        }

        /**
         * Returns the synthetic entity ID used in packets for this NPC.
         *
         * @return the entity ID
         */
        public int getId() {
            return entityId;
        }
    }
}
