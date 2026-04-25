package ru.ashesha.buildBattleAI.entity.hologram;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.entity.hologram.api.BBAIHologramService;
import ru.ashesha.buildBattleAI.util.MessageUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PacketEvents-based implementation of {@link BBAIHologramService}.
 * <p>
 * Holograms are composed of stacked invisible armor stands, each displaying
 * a custom name as a floating text line. The service is <b>stateless</b>:
 * holograms store only their entity IDs — no location, no text. All positional
 * and textual state is the caller's responsibility, consistent with the NPC
 * service pattern.
 * <p>
 * The service resolves all version-dependent packet details once at startup:
 * <ul>
 *     <li>Spawn packet — unified {@code SpawnEntity} (1.19+)
 *         vs dedicated {@code SpawnLivingEntity} equivalent (pre-1.19,
 *         handled by PacketEvents internal translation)</li>
 *     <li>Custom name metadata type — {@code OPTIONAL_ADV_COMPONENT} (1.13+)
 *         vs {@code STRING} (pre-1.13)</li>
 *     <li>Custom name visible type — {@code BOOLEAN} (1.9+)
 *         vs {@code BYTE} (1.8)</li>
 *     <li>Armor stand flags index — shifts across major versions
 *         as new metadata entries are prepended by Mojang</li>
 * </ul>
 */
@RequiredArgsConstructor
public class HologramService implements BBAIHologramService, PluginService {

    /** Vertical distance between hologram lines in blocks. */
    static final double LINE_SPACING = 0.3;

    /** The plugin instance used for packet sending. */
    @NonNull
    private final BuildBattleAI plugin;

    /**
     * Monotonically increasing counter for synthetic entity IDs.
     * Starts from a high range (offset from {@link ru.ashesha.buildBattleAI.entity.npc.NPCService})
     * to minimize collision risk.
     */
    private final AtomicInteger entityIdCounter = new AtomicInteger(Integer.MAX_VALUE / 4);

    // ── version-resolved factories and constants ────────────────────────────

    /** Factory for creating armor stand spawn packets. Resolved in {@link #enable()}. */
    private SpawnFactory spawnFactory;

    /** Factory for creating custom name metadata entries. Resolved in {@link #enable()}. */
    private NameFactory nameFactory;

    /** Factory for creating "custom name visible" metadata entries. Resolved in {@link #enable()}. */
    private NameVisibleFactory nameVisibleFactory;

    /**
     * Version-dependent entity metadata index for the armor stand flags byte.
     * Encodes small (0x01), no base plate (0x08), and optionally marker (0x10).
     * Resolved in {@link #enable()}.
     */
    private int armorStandFlagsIndex;

    /**
     * Armor stand flags byte value. Includes small + no base plate on all versions,
     * plus marker on 1.16.2+ for a zero-size hitbox. Resolved in {@link #enable()}.
     */
    private byte armorStandFlagsValue;

    /**
     * Resolves all version-dependent factories from the server version.
     * Deferred from the constructor because services are created inside
     * {@link ru.ashesha.buildBattleAI.core.PluginContext}'s constructor,
     * before the context reference is published.
     */
    @Override
    public void enable() {
        ServerVersion version = plugin.getContext().getServerVersion();
        this.spawnFactory = resolveSpawnFactory();
        this.nameFactory = resolveNameFactory(version);
        this.nameVisibleFactory = resolveNameVisibleFactory(version);
        this.armorStandFlagsIndex = resolveArmorStandFlagsIndex(version);
        // Small (0x01) + No Base Plate (0x08); add Marker (0x10) on 1.16.2+
        this.armorStandFlagsValue = version.isNewerThanOrEquals(ServerVersion.V_1_16_2)
                ? (byte) 0x19
                : (byte) 0x09;
    }

    /**
     * No explicit cleanup needed — the service holds no runtime resources
     * beyond resolved factories (plain objects, not threads or connections).
     */
    @Override
    public void shutdown() {
        // Intentionally empty — no runtime resources to release.
    }

    // ── public API ──────────────────────────────────────────────────────────

    @Override
    @NonNull
    public Hologram createHologram(int lineCount) {
        if (lineCount < 1)
            throw new IllegalArgumentException("Hologram must have at least one line");

        int[] entityIds = new int[lineCount];
        for (int i = 0; i < lineCount; i++)
            entityIds[i] = entityIdCounter.getAndIncrement();

        return new Hologram(entityIds);
    }

    @Override
    public void spawn(@NonNull Player viewer, @NonNull Hologram hologram,
                      @NonNull Location location, @NonNull List<String> lines) {
        spawn(Collections.singletonList(viewer), hologram, location, lines);
    }

    @Override
    public void spawn(@NonNull Collection<Player> viewers, @NonNull Hologram hologram,
                      @NonNull Location location, @NonNull List<String> lines) {
        if (viewers.isEmpty())
            return;

        int[] entityIds = hologram.entityIds;
        if (lines.size() != entityIds.length)
            throw new IllegalArgumentException("Expected " + entityIds.length
                    + " lines, got " + lines.size());

        for (int i = 0; i < entityIds.length; i++) {
            int entityId = entityIds[i];
            double y = computeLineY(location.getY(), i, entityIds.length);

            // 1. Spawn invisible armor stand at the line's position
            PacketWrapper<?> spawn = spawnFactory.create(
                    entityId, location.getX(), y, location.getZ()
            );
            for (Player viewer : viewers)
                plugin.getContext().sendPacket(viewer, spawn);

            // 2. Send metadata: invisible, small, no base plate, custom name
            List<EntityData<?>> metadata = buildSpawnMetadata(lines.get(i));
            WrapperPlayServerEntityMetadata metaPacket =
                    new WrapperPlayServerEntityMetadata(entityId, metadata);
            for (Player viewer : viewers)
                plugin.getContext().sendPacket(viewer, metaPacket);
        }
    }

    @Override
    public void despawn(@NonNull Player viewer, @NonNull Hologram hologram) {
        despawn(Collections.singletonList(viewer), hologram);
    }

    @Override
    public void despawn(@NonNull Collection<Player> viewers, @NonNull Hologram hologram) {
        if (viewers.isEmpty())
            return;

        WrapperPlayServerDestroyEntities destroy =
                new WrapperPlayServerDestroyEntities(hologram.entityIds);
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, destroy);
    }

    @Override
    public void updateLine(@NonNull Player viewer, @NonNull Hologram hologram,
                           int lineIndex, @NonNull String text) {
        updateLine(Collections.singletonList(viewer), hologram, lineIndex, text);
    }

    @Override
    public void updateLine(@NonNull Collection<Player> viewers, @NonNull Hologram hologram,
                           int lineIndex, @NonNull String text) {
        if (viewers.isEmpty())
            return;
        if (lineIndex < 0 || lineIndex >= hologram.entityIds.length)
            throw new IndexOutOfBoundsException("Line index " + lineIndex
                    + " out of range [0, " + hologram.entityIds.length + ")");

        int entityId = hologram.entityIds[lineIndex];
        List<EntityData<?>> metadata = buildNameMetadata(text);
        WrapperPlayServerEntityMetadata packet =
                new WrapperPlayServerEntityMetadata(entityId, metadata);
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, packet);
    }

    @Override
    public void updateLines(@NonNull Player viewer, @NonNull Hologram hologram,
                            @NonNull Location location, @NonNull List<String> lines) {
        updateLines(Collections.singletonList(viewer), hologram, location, lines);
    }

    @Override
    public void updateLines(@NonNull Collection<Player> viewers, @NonNull Hologram hologram,
                            @NonNull Location location, @NonNull List<String> lines) {
        if (viewers.isEmpty())
            return;
        if (lines.isEmpty())
            throw new IllegalArgumentException("Hologram must have at least one line");

        int oldCount = hologram.entityIds.length;
        int newCount = lines.size();
        int[] oldIds = hologram.entityIds;

        // Shrink: despawn excess armor stands before resizing the ID array
        if (newCount < oldCount) {
            int[] removeIds = new int[oldCount - newCount];
            System.arraycopy(oldIds, newCount, removeIds, 0, oldCount - newCount);
            WrapperPlayServerDestroyEntities destroy =
                    new WrapperPlayServerDestroyEntities(removeIds);
            for (Player viewer : viewers)
                plugin.getContext().sendPacket(viewer, destroy);
            hologram.entityIds = Arrays.copyOf(oldIds, newCount);
        }

        // Expand: allocate new entity IDs for additional lines
        if (newCount > oldCount) {
            int[] newIds = Arrays.copyOf(oldIds, newCount);
            for (int i = oldCount; i < newCount; i++)
                newIds[i] = entityIdCounter.getAndIncrement();
            hologram.entityIds = newIds;
        }

        int[] currentIds = hologram.entityIds;
        boolean countChanged = oldCount != newCount;

        for (int i = 0; i < newCount; i++) {
            String text = lines.get(i);

            if (i < oldCount) {
                // Existing line: update name metadata
                List<EntityData<?>> metadata = buildNameMetadata(text);
                WrapperPlayServerEntityMetadata metaPacket =
                        new WrapperPlayServerEntityMetadata(currentIds[i], metadata);
                for (Player viewer : viewers)
                    plugin.getContext().sendPacket(viewer, metaPacket);

                // Reposition if the total line count changed (Y offsets shift)
                if (countChanged) {
                    double y = computeLineY(location.getY(), i, newCount);
                    WrapperPlayServerEntityTeleport tp = new WrapperPlayServerEntityTeleport(
                            currentIds[i],
                            new Vector3d(location.getX(), y, location.getZ()),
                            0f, 0f, false
                    );
                    for (Player viewer : viewers)
                        plugin.getContext().sendPacket(viewer, tp);
                }
            } else {
                // New line: spawn armor stand with full metadata
                double y = computeLineY(location.getY(), i, newCount);
                PacketWrapper<?> spawn = spawnFactory.create(
                        currentIds[i], location.getX(), y, location.getZ()
                );
                for (Player viewer : viewers)
                    plugin.getContext().sendPacket(viewer, spawn);

                List<EntityData<?>> metadata = buildSpawnMetadata(text);
                WrapperPlayServerEntityMetadata metaPacket =
                        new WrapperPlayServerEntityMetadata(currentIds[i], metadata);
                for (Player viewer : viewers)
                    plugin.getContext().sendPacket(viewer, metaPacket);
            }
        }
    }

    @Override
    public void teleport(@NonNull Player viewer, @NonNull Hologram hologram,
                         @NonNull Location destination) {
        teleport(Collections.singletonList(viewer), hologram, destination);
    }

    @Override
    public void teleport(@NonNull Collection<Player> viewers, @NonNull Hologram hologram,
                         @NonNull Location destination) {
        if (viewers.isEmpty())
            return;

        for (int i = 0; i < hologram.entityIds.length; i++) {
            double y = computeLineY(destination.getY(), i, hologram.entityIds.length);

            WrapperPlayServerEntityTeleport teleportPacket = new WrapperPlayServerEntityTeleport(
                    hologram.entityIds[i],
                    new Vector3d(destination.getX(), y, destination.getZ()),
                    0f, 0f, false
            );
            for (Player viewer : viewers)
                plugin.getContext().sendPacket(viewer, teleportPacket);
        }
    }

    // ── internal helpers ────────────────────────────────────────────────────

    /**
     * Computes the Y coordinate for a specific line in the hologram.
     * Lines are stacked upward from the anchor: the topmost line (index 0)
     * is highest, and each subsequent line is {@link #LINE_SPACING} blocks lower.
     *
     * @param anchorY   the anchor Y coordinate
     * @param lineIndex the zero-based line index (0 = top)
     * @param lineCount total number of lines
     * @return the Y coordinate for this line's armor stand
     */
    static double computeLineY(double anchorY, int lineIndex, int lineCount) {
        // Stack lines above the anchor point: top line is highest
        return anchorY + (lineCount - 1 - lineIndex) * LINE_SPACING;
    }

    /**
     * Builds the full metadata list for spawning a hologram line.
     * Includes: invisible flag, custom name, custom name visible, armor stand flags.
     *
     * @param text the display text for this line
     * @return the entity metadata entries
     */
    private List<EntityData<?>> buildSpawnMetadata(String text) {
        List<EntityData<?>> data = new ArrayList<>(4);

        // Entity flags byte — 0x20 = invisible
        data.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20));

        // Custom name and visibility
        data.add(nameFactory.create(text));
        data.add(nameVisibleFactory.create(true));

        // Armor stand flags: small + no base plate (+ marker on 1.16.2+)
        data.add(new EntityData<>(armorStandFlagsIndex, EntityDataTypes.BYTE, armorStandFlagsValue));

        return data;
    }

    /**
     * Builds metadata for updating just the name of a hologram line.
     *
     * @param text the new display text
     * @return the entity metadata entries for the name update
     */
    private List<EntityData<?>> buildNameMetadata(String text) {
        return Collections.singletonList(nameFactory.create(text));
    }

    // ── version-resolved factory builders ───────────────────────────────────

    /**
     * Resolves the factory for spawning armor stand entities.
     * <p>
     * PacketEvents translates {@code WrapperPlayServerSpawnEntity} to the
     * appropriate wire packet for the server version (including the now-removed
     * {@code SpawnLivingEntity} packet on pre-1.19 servers).
     */
    private SpawnFactory resolveSpawnFactory() {
        // PacketEvents handles the SpawnEntity → SpawnLivingEntity translation
        // internally for armor stands on pre-1.19 servers.
        return (entityId, x, y, z) -> new WrapperPlayServerSpawnEntity(
                entityId, Optional.of(UUID.randomUUID()), EntityTypes.ARMOR_STAND,
                new Vector3d(x, y, z),
                0f, 0f, 0f, 0, Optional.empty()
        );
    }

    /**
     * Resolves the factory for creating custom name metadata entries.
     * <ul>
     *     <li>1.13+ — {@code OPTIONAL_ADV_COMPONENT} at index 2, wrapping an
     *         Adventure {@link Component} via {@link MessageUtils#toColorComponent}</li>
     *     <li>&lt;1.13 — {@code STRING} at index 2, translated via
     *         {@link MessageUtils#translateColors}</li>
     * </ul>
     */
    private NameFactory resolveNameFactory(ServerVersion version) {
        if (version.isNewerThanOrEquals(ServerVersion.V_1_13))
            return text -> new EntityData<>(2, EntityDataTypes.OPTIONAL_ADV_COMPONENT,
                    Optional.of(MessageUtils.toColorComponent(text)));
        return text -> new EntityData<>(2, EntityDataTypes.STRING, MessageUtils.translateColors(text));
    }

    /**
     * Resolves the factory for creating "custom name visible" metadata entries.
     * <ul>
     *     <li>1.9+ — {@code BOOLEAN} at index 3</li>
     *     <li>1.8 — {@code BYTE} at index 3 ({@code 0} or {@code 1})</li>
     * </ul>
     */
    private NameVisibleFactory resolveNameVisibleFactory(ServerVersion version) {
        if (version.isNewerThanOrEquals(ServerVersion.V_1_9))
            return visible -> new EntityData<>(3, EntityDataTypes.BOOLEAN, visible);
        return visible -> new EntityData<>(3, EntityDataTypes.BYTE, (byte) (visible ? 1 : 0));
    }

    /**
     * Resolves the entity metadata index for the armor stand flags byte.
     * <p>
     * This index shifts across major versions as Mojang adds new metadata
     * entries to the {@code Entity} and {@code LivingEntity} base classes:
     * <pre>
     *   1.8–1.9     → index 10
     *   1.10–1.13   → index 11  (No Gravity added to Entity)
     *   1.14        → index 13  (Pose added to Entity, Bed Position to LivingEntity)
     *   1.15–1.16   → index 14  (Stinger Count added to LivingEntity)
     *   1.17+       → index 15  (Freeze Ticks added to Entity)
     * </pre>
     */
    private int resolveArmorStandFlagsIndex(ServerVersion version) {
        if (version.isNewerThanOrEquals(ServerVersion.V_1_17))
            return 15;
        if (version.isNewerThanOrEquals(ServerVersion.V_1_15))
            return 14;
        if (version.isNewerThanOrEquals(ServerVersion.V_1_14))
            return 13;
        if (version.isNewerThanOrEquals(ServerVersion.V_1_10))
            return 11;
        return 10;
    }

    // ── version-dispatched functional interfaces ────────────────────────────

    /**
     * Factory for creating armor stand spawn packets.
     * Resolved once at startup based on the server version.
     */
    @FunctionalInterface
    private interface SpawnFactory {
        PacketWrapper<?> create(int entityId, double x, double y, double z);
    }

    /**
     * Factory for creating custom name metadata entries.
     * Handles the STRING (pre-1.13) vs OPTIONAL_ADV_COMPONENT (1.13+) split.
     */
    @FunctionalInterface
    private interface NameFactory {
        EntityData create(String text);
    }

    /**
     * Factory for creating "custom name visible" metadata entries.
     * Handles the BYTE (1.8) vs BOOLEAN (1.9+) split.
     */
    @FunctionalInterface
    private interface NameVisibleFactory {
        EntityData create(boolean visible);
    }

    // ── Hologram data class ─────────────────────────────────────────────────

    /**
     * Packet-based hologram composed of stacked invisible armor stands.
     * <p>
     * Holograms are <b>stateless</b>: they store only their synthetic entity IDs,
     * not their location or line text. This matches the NPC service pattern where
     * all positional and content state is the caller's responsibility.
     * <p>
     * The entity ID array is mutable to support dynamic line count changes
     * via {@link BBAIHologramService#updateLines} — armor stands can be added
     * or removed without recreating the hologram.
     * <p>
     * Create instances via {@link BBAIHologramService#createHologram(int)}.
     */
    public static final class Hologram {

        /** Synthetic entity IDs for each line's armor stand, indexed top-to-bottom. */
        int[] entityIds;

        /**
         * Package-private constructor — instances are created by {@link HologramService}.
         *
         * @param entityIds the synthetic entity IDs (one per line)
         */
        Hologram(int[] entityIds) {
            this.entityIds = entityIds;
        }

        /**
         * Returns the number of lines in this hologram.
         *
         * @return the line count
         */
        public int getLineCount() {
            return entityIds.length;
        }

        /**
         * Returns the entity ID of a specific line's armor stand.
         *
         * @param lineIndex zero-based line index (0 = top)
         * @return the entity ID
         */
        public int getEntityId(int lineIndex) {
            return entityIds[lineIndex];
        }
    }
}
