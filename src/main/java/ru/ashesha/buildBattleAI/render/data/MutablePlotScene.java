package ru.ashesha.buildBattleAI.render.data;

import com.cryptomorin.xseries.XMaterial;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.render.BlockPalette;

import java.util.Arrays;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Per-plot scene mirror updated incrementally by Bukkit block events.
 * <p>
 * Acts as a {@link SceneData} that the renderer reads directly, eliminating
 * the per-render-tick {@code RenderService.capture(region)} cost on the main
 * thread. One instance is created per occupied plot when a game session enters
 * the {@code PLAYING} state and is wiped on each scoring event.
 *
 * <h3>Concurrency contract</h3>
 * <ul>
 *   <li><b>Writers</b> ({@link #setBlock}/{@link #clearBlock}) — main thread
 *       only. JLS §17.7 atomic-store guarantees for {@code short}, {@code byte},
 *       and reference writes ensure no torn reads. No lock is taken.</li>
 *   <li><b>Readers</b> — the async render task acquires {@link #readLock()}
 *       once around the entire {@code CpuRenderer.render(...)} call. Per-cell
 *       reads from inside the renderer do not take any further lock.</li>
 *   <li><b>{@link #clearAll}</b> — takes the write-lock so partial clearing
 *       is never visible to an in-flight render.</li>
 * </ul>
 * <p>Memory visibility between concurrent writes and an in-flight render is
 * best-effort; writes propagate to subsequent render tasks via the read-lock
 * acquire-release synchronization. This is acceptable for the 5-second render
 * cadence: a block placed mid-render appears in the next render.
 */
public final class MutablePlotScene implements SceneData {

    private static final XMaterial[] MATERIAL_VALUES = XMaterial.values();
    private static final short AIR_ORDINAL = (short) XMaterial.AIR.ordinal();

    /** Hard cap on any single axis — mirrors {@code RendererUtils.MAX_REGION_AXIS}. */
    static final int MAX_AXIS = 512;

    // Geometry — immutable after construction.
    @Getter @Accessors(fluent = true) private final int minX;
    @Getter @Accessors(fluent = true) private final int minY;
    @Getter @Accessors(fluent = true) private final int minZ;
    private final int sizeX, sizeY, sizeZ;
    @Getter @Accessors(fluent = true) private final int maxX;
    @Getter @Accessors(fluent = true) private final int maxY;
    @Getter @Accessors(fluent = true) private final int maxZ;
    private final int sizeYZ;
    private final boolean legacy;

    // Storage — array contents mutate on main thread; references are final.
    private final short[] ordinals;
    private final byte[] legacyBlockData; // != null iff legacy
    private final String[] blockStates;   // != null iff !legacy

    // Lock guards only clearAll() vs in-flight render reads.
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Creates an all-air mirror covering the given inclusive region.
     *
     * @param minX   minimum world X coordinate (inclusive)
     * @param minY   minimum world Y coordinate (inclusive)
     * @param minZ   minimum world Z coordinate (inclusive)
     * @param sizeX  number of blocks along the X axis (must be > 0 and <= MAX_AXIS)
     * @param sizeY  number of blocks along the Y axis (must be > 0 and <= MAX_AXIS)
     * @param sizeZ  number of blocks along the Z axis (must be > 0 and <= MAX_AXIS)
     * @param legacy {@code true} for 1.8–1.12 servers (use byte data); {@code false} for 1.13+ (use state strings)
     * @throws IllegalArgumentException if any size is non-positive or exceeds {@link #MAX_AXIS}
     */
    public MutablePlotScene(int minX, int minY, int minZ,
                            int sizeX, int sizeY, int sizeZ,
                            boolean legacy) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0)
            throw new IllegalArgumentException(
                    "Non-positive plot size: " + sizeX + "x" + sizeY + "x" + sizeZ);
        if (sizeX > MAX_AXIS || sizeY > MAX_AXIS || sizeZ > MAX_AXIS)
            throw new IllegalArgumentException(
                    "Plot axis exceeds MAX_AXIS=" + MAX_AXIS
                            + ": " + sizeX + "x" + sizeY + "x" + sizeZ);
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.maxX = minX + sizeX - 1;
        this.maxY = minY + sizeY - 1;
        this.maxZ = minZ + sizeZ - 1;
        this.sizeYZ = sizeY * sizeZ;
        this.legacy = legacy;

        int total = sizeX * sizeY * sizeZ;
        this.ordinals = new short[total];
        // XMaterial.AIR.ordinal() is NOT 0 — must fill explicitly (ordinal 0 is ACACIA_BOAT).
        Arrays.fill(this.ordinals, AIR_ORDINAL);
        this.legacyBlockData = legacy ? new byte[total] : null;
        this.blockStates = legacy ? null : new String[total];
    }

    /**
     * Constructs a mirror sized to the inclusive cuboid spanned by the plot's two corners.
     *
     * @param plot   the arena plot whose corner coordinates define the region
     * @param legacy {@code true} for 1.8–1.12 servers; {@code false} for 1.13+
     * @return a new all-air {@code MutablePlotScene} covering the plot's build zone
     */
    public static MutablePlotScene forPlot(@NonNull Arena.PlotData plot, boolean legacy) {
        int minX = Math.min(plot.corner1X(), plot.corner2X());
        int minY = Math.min(plot.corner1Y(), plot.corner2Y());
        int minZ = Math.min(plot.corner1Z(), plot.corner2Z());
        int maxX = Math.max(plot.corner1X(), plot.corner2X());
        int maxY = Math.max(plot.corner1Y(), plot.corner2Y());
        int maxZ = Math.max(plot.corner1Z(), plot.corner2Z());
        return new MutablePlotScene(minX, minY, minZ,
                maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, legacy);
    }

    /**
     * Translates world coordinates into the flat array index using X-major layout.
     * <p>
     * Layout: {@code index = lx * sizeY * sizeZ + ly * sizeZ + lz}
     * where {@code lx = wx - minX}, etc.
     *
     * @param wx world X coordinate
     * @param wy world Y coordinate
     * @param wz world Z coordinate
     * @return the flat array index, or {@code -1} if the coordinates are out of bounds
     */
    private int indexOf(int wx, int wy, int wz) {
        int lx = wx - minX, ly = wy - minY, lz = wz - minZ;
        if (lx < 0 || lx >= sizeX || ly < 0 || ly >= sizeY || lz < 0 || lz >= sizeZ)
            return -1;
        return lx * sizeYZ + ly * sizeZ + lz;
    }

    // ── SceneData ──

    /**
     * Returns the block material at the given world coordinates.
     * Returns {@link XMaterial#AIR} for coordinates outside this scene's bounds.
     *
     * @param wx world X coordinate
     * @param wy world Y coordinate
     * @param wz world Z coordinate
     * @return the block material, never {@code null}
     */
    @Override
    public XMaterial getBlockType(int wx, int wy, int wz) {
        int idx = indexOf(wx, wy, wz);
        if (idx < 0)
            return XMaterial.AIR;
        // & 0xFFFF prevents sign-extension when converting signed short to array index.
        return MATERIAL_VALUES[ordinals[idx] & 0xFFFF];
    }

    /**
     * Returns the legacy block data byte for the given coordinates.
     * Only meaningful on 1.8–1.12 servers ({@code legacy == true}).
     * Returns {@code 0} for out-of-bounds coordinates or when not in legacy mode.
     *
     * @param wx world X coordinate
     * @param wy world Y coordinate
     * @param wz world Z coordinate
     * @return legacy data byte, or {@code 0} if unavailable
     */
    @Override
    public byte getLegacyBlockData(int wx, int wy, int wz) {
        if (legacyBlockData == null)
            return 0;
        int idx = indexOf(wx, wy, wz);
        if (idx < 0)
            return 0;
        return legacyBlockData[idx];
    }

    /**
     * Returns {@code true} when this mirror was constructed in legacy mode and
     * holds per-cell byte data values (1.8–1.12 servers).
     *
     * @return {@code true} iff legacy block data is available
     */
    @Override
    public boolean hasLegacyBlockData() {
        return legacyBlockData != null;
    }

    /**
     * Returns the block state string for the given coordinates, or {@code null}
     * if the cell is stateless, out-of-bounds, or this mirror is in legacy mode.
     * <p>
     * Only stateful materials (as determined by
     * {@link ru.ashesha.buildBattleAI.render.BlockPalette#needsBlockState}) retain
     * their state string; all other cells store {@code null} here.
     *
     * @param wx world X coordinate
     * @param wy world Y coordinate
     * @param wz world Z coordinate
     * @return block state string, or {@code null}
     */
    @Override
    public String getBlockState(int wx, int wy, int wz) {
        if (blockStates == null)
            return null;
        int idx = indexOf(wx, wy, wz);
        if (idx < 0)
            return null;
        return blockStates[idx];
    }

    // ── Writes (main thread only, no lock) ────────────────────────────

    /**
     * Records a block placement on a 1.13+ server.
     * <p>
     * Main thread only. Out-of-bounds coordinates are silently ignored.
     * The state string is retained only when
     * {@link ru.ashesha.buildBattleAI.render.BlockPalette#needsBlockState(XMaterial)}
     * reports the material as stateful — matches the
     * {@code FlatScene.fromSnapshot} optimisation.
     *
     * @param wx          world X coordinate
     * @param wy          world Y coordinate
     * @param wz          world Z coordinate
     * @param material    the placed block's material
     * @param stateString the full block-state string (e.g. {@code "minecraft:oak_stairs[facing=north,half=bottom]"})
     * @throws IllegalStateException if called on a legacy-mode mirror
     */
    public void setBlock(int wx, int wy, int wz, @NonNull XMaterial material, String stateString) {
        if (legacy)
            throw new IllegalStateException("setBlock(String) called on legacy mirror");
        int idx = indexOf(wx, wy, wz);
        if (idx < 0)
            return;
        ordinals[idx] = (short) material.ordinal();
        // Store state only if the renderer would actually consult it.
        if (BlockPalette.needsBlockState(material))
            blockStates[idx] = stateString;
        else
            blockStates[idx] = null;
    }

    /**
     * Records a block placement on a 1.8–1.12 server.
     * <p>
     * Main thread only. Out-of-bounds coordinates are silently ignored.
     *
     * @param wx         world X coordinate
     * @param wy         world Y coordinate
     * @param wz         world Z coordinate
     * @param material   the placed block's material
     * @param legacyData the legacy block data byte
     * @throws IllegalStateException if called on a non-legacy-mode mirror
     */
    public void setBlock(int wx, int wy, int wz, @NonNull XMaterial material, byte legacyData) {
        if (!legacy)
            throw new IllegalStateException("setBlock(byte) called on non-legacy mirror");
        int idx = indexOf(wx, wy, wz);
        if (idx < 0)
            return;
        ordinals[idx] = (short) material.ordinal();
        legacyBlockData[idx] = legacyData;
    }

    /**
     * Records a block break — resets the cell to AIR and drops any per-cell metadata.
     * <p>
     * Main thread only. Out-of-bounds coordinates are silently ignored.
     *
     * @param wx world X coordinate
     * @param wy world Y coordinate
     * @param wz world Z coordinate
     */
    public void clearBlock(int wx, int wy, int wz) {
        int idx = indexOf(wx, wy, wz);
        if (idx < 0)
            return;
        ordinals[idx] = AIR_ORDINAL;
        if (legacy)
            legacyBlockData[idx] = 0;
        else
            blockStates[idx] = null;
    }

    // ── Bulk operation — exclusive with in-flight renders ─────────────

    /**
     * Wipes the mirror back to all-air.
     * <p>
     * Acquires the write-lock to ensure no in-flight render observes a
     * partially-cleared scene. The render task holds the corresponding
     * read-lock for the duration of its render call, so this method blocks
     * for at most one render's runtime if a render is in flight.
     */
    public void clearAll() {
        Lock w = lock.writeLock();
        w.lock();
        try {
            Arrays.fill(ordinals, AIR_ORDINAL);
            if (legacy)
                Arrays.fill(legacyBlockData, (byte) 0);
            else
                Arrays.fill(blockStates, null);
        } finally {
            w.unlock();
        }
    }

    /**
     * Returns the shared read-lock. The render task must hold this around
     * the entire {@code render(...)} call so a concurrent {@link #clearAll}
     * cannot interleave with the renderer's reads.
     */
    public Lock readLock() {
        return lock.readLock();
    }
}
