package ru.ashesha.buildBattleAI.arena;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.bukkit.block.BlockFace;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.entity.hologram.HologramService;
import ru.ashesha.buildBattleAI.entity.npc.NPCService;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable state tracker for an in-progress arena creation wizard.
 * <p>
 * Unlike a linear step-based wizard, this session is a <b>non-linear
 * data bag</b> — all settings start as {@code null} and can be filled
 * in any order. The admin sees a single panel showing all settings
 * and clicks buttons to configure each one independently.
 * <p>
 * The session is considered {@linkplain #isComplete() complete} when
 * every required setting has been filled: player count, lobby, and
 * all per-plot fields (spawn, corner1, corner2, camera1, camera2, camera3).
 * <p>
 * Visual markers (holograms at set positions, NPCs at camera positions)
 * are tracked here for cleanup when the session ends. These fields are
 * managed exclusively by {@link ArenaManager}.
 * <p>
 * Package-private — only {@link ArenaManager} accesses sessions directly.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
class ArenaSetupSession {

    /** UUID of the player running the setup wizard. */
    @NonNull
    private final UUID playerId;

    /** Name of the arena being created. */
    @NonNull
    private final String arenaName;

    /** Name of the temporary void world for setup. */
    @NonNull
    private final String worldName;

    // ── return location (to teleport back after setup) ─────────────────

    /** Name of the world the player was in before setup started. */
    @NonNull
    private final String returnWorld;

    /** Pre-setup player coordinates. */
    private final double returnX, returnY, returnZ;

    /** Pre-setup player rotation. */
    private final float returnYaw, returnPitch;

    /** Whether the player could fly before setup started. */
    private final boolean wasFlying;

    // ── configurable settings (all nullable until set) ─────────────────

    /** Maximum players / plot count (2–8). {@code null} = not yet chosen. */
    @Setter
    private Integer maxPlayers;

    /** Lobby position where players wait. {@code null} = not yet set. */
    @Setter
    private Arena.Position lobby;

    /** Spectator position (optional). {@code null} = not set, defaults to lobby. */
    @Setter
    private Arena.Position spectator;

    /** Minimum players to start (optional, default 2). {@code null} = not yet set. */
    @Setter
    private Integer minPlayers;

    /** Seconds per build attempt (optional, default 150). {@code null} = not yet set. */
    @Setter
    private Integer buildTime;

    /** Total game duration in seconds (optional, default 300). {@code null} = not yet set. */
    @Setter
    private Integer gameTime;

    /** Countdown duration in seconds (optional, default 5). {@code null} = not yet set. */
    @Setter
    private Integer countdownTime;

    /** Per-plot setup data, keyed by 1-based plot index. */
    private final Map<Integer, PlotSetupData> plots = new HashMap<>();

    /**
     * 1-based index of the plot tab currently being edited.
     * <p>
     * The setup panel renders global settings always visible plus a tab bar
     * with one button per plot; clicking a tab swaps which plot section is
     * shown without losing the other plots' state ("browser tab" feel).
     * <p>
     * {@code null} until {@link #maxPlayers} has been chosen; defaults to
     * {@code 1} as soon as the player count is selected. Clamped back to
     * {@code 1} if it would exceed the current {@link #maxPlayers}.
     */
    @Setter
    private Integer activePlotTab;

    // ── visual markers (managed by ArenaManager for setup feedback) ────

    /** Hologram marker at the lobby position. */
    @Setter
    private HologramService.Hologram lobbyHologram;

    /** Hologram marker at the spectator position. */
    @Setter
    private HologramService.Hologram spectatorHologram;

    /**
     * Returns the setup data for the given plot, creating it if absent.
     *
     * @param index 1-based plot index
     * @return the mutable plot data
     */
    PlotSetupData getOrCreatePlot(int index) {
        return plots.computeIfAbsent(index, k -> new PlotSetupData());
    }

    /**
     * Removes plot data for indices above the given count.
     * Called when the player reduces the player count.
     * <p>
     * <b>Note:</b> visual markers for removed plots must be despawned
     * by the caller before invoking this method — this method only
     * discards the data objects.
     *
     * @param maxPlots the new maximum plot count
     */
    void trimPlotsAbove(int maxPlots) {
        Iterator<Map.Entry<Integer, PlotSetupData>> it = plots.entrySet().iterator();
        while (it.hasNext())
            if (it.next().getKey() > maxPlots)
                it.remove();
    }

    /**
     * Checks whether all required settings have been configured.
     * <p>
     * Required: {@link #maxPlayers}, {@link #lobby}, and for each
     * plot 1..maxPlayers: spawn, corner1, corner2, camera1, camera2,
     * camera3, picture corner 1, picture corner 2, picture face
     * (with a valid coplanar 1×1 or 2×2 layout and a face matching the plane).
     *
     * @return {@code true} if the arena can be created
     */
    boolean isComplete() {
        if (maxPlayers == null || lobby == null)
            return false;
        for (int i = 1; i <= maxPlayers; i++) {
            PlotSetupData plot = plots.get(i);
            if (plot == null || !plot.isComplete())
                return false;
        }
        return true;
    }

    // ── inner types ────────────────────────────────────────────────────

    /**
     * Mutable data holder for a single plot during setup.
     * All fields start {@code null} and are set independently
     * in any order via the non-linear panel UI.
     * <p>
     * Visual marker references (holograms, NPCs) are tracked here
     * so {@link ArenaManager} can despawn them when positions change
     * or the session ends.
     */
    @Getter
    @Setter
    @Accessors(fluent = true)
    static class PlotSetupData {

        /** Player spawn position. {@code null} = not yet set. */
        private Arena.Position spawn;

        /** First build zone corner (block coords). {@code null} = not yet set. */
        private int[] corner1;

        /** Opposite build zone corner (block coords). {@code null} = not yet set. */
        private int[] corner2;

        /** Renderer camera 1 position and angle. {@code null} = not yet set. */
        private Arena.Position camera1;

        /** Renderer camera 2 position and angle. {@code null} = not yet set. */
        private Arena.Position camera2;

        /** Renderer camera 3 position and angle. {@code null} = not yet set. */
        private Arena.Position camera3;

        /**
         * First picture-region corner (block coords) for the in-world
         * preview surface. {@code null} = not yet set.
         */
        private int[] pictureCorner1;

        /**
         * Opposite picture-region corner (block coords). For a 1×1 region
         * this equals {@link #pictureCorner1}. {@code null} = not yet set.
         */
        private int[] pictureCorner2;

        /**
         * Cardinal direction the picture faces. {@code null} until the user
         * picks from the face buttons. Reset to {@code null} automatically
         * when a corner change makes the previous face incompatible with
         * the new plane.
         */
        private BlockFace pictureFace;

        // ── visual markers (managed by ArenaManager) ───────────────────

        /** Hologram at the spawn position. */
        private HologramService.Hologram spawnHologram;

        /** Hologram at corner 1 position. */
        private HologramService.Hologram corner1Hologram;

        /** Hologram at corner 2 position. */
        private HologramService.Hologram corner2Hologram;

        /** NPC marker for camera 1. */
        private NPCService.NPC cameraNpc1;

        /** NPC marker for camera 2. */
        private NPCService.NPC cameraNpc2;

        /** NPC marker for camera 3. */
        private NPCService.NPC cameraNpc3;

        /** Hologram at picture corner 1 position. */
        private HologramService.Hologram pictureCorner1Hologram;

        /** Hologram at picture corner 2 position. */
        private HologramService.Hologram pictureCorner2Hologram;

        /**
         * Checks whether all required plot fields are set and the picture
         * region is geometrically valid (coplanar, size 1×1 or 2×2) with a
         * face that matches the determined plane.
         *
         * @return {@code true} if the plot is fully configured
         */
        boolean isComplete() {
            if (spawn == null || corner1 == null || corner2 == null
                    || camera1 == null || camera2 == null || camera3 == null)
                return false;
            if (pictureCorner1 == null || pictureCorner2 == null || pictureFace == null)
                return false;
            // Geometry must be valid AND the chosen face must match the plane.
            return pictureGeometryStatus() == PictureGeometry.VALID
                    && isFaceAllowed(pictureFace);
        }

        /**
         * Classifies the current picture corners against the allowed
         * shapes (independent of the chosen face).
         *
         * @return the geometry status; never {@code null}
         */
        PictureGeometry pictureGeometryStatus() {
            if (pictureCorner1 == null || pictureCorner2 == null)
                return PictureGeometry.MISSING;
            int dx = Math.abs(pictureCorner2[0] - pictureCorner1[0]);
            int dy = Math.abs(pictureCorner2[1] - pictureCorner1[1]);
            int dz = Math.abs(pictureCorner2[2] - pictureCorner1[2]);
            if (dx > 0 && dz > 0)
                return PictureGeometry.NOT_COPLANAR;
            if (dx == 0 && dy == 0 && dz == 0)
                return PictureGeometry.VALID;
            if (dx == 0 && dy == 1 && dz == 1)
                return PictureGeometry.VALID;
            if (dx == 1 && dy == 1 && dz == 0)
                return PictureGeometry.VALID;
            return PictureGeometry.INVALID_SIZE;
        }

        /**
         * Returns whether the corners describe a 1×1 region (corners
         * coincide). Only meaningful when both corners are set.
         *
         * @return {@code true} when both corners refer to the same block
         */
        boolean isPictureOneByOne() {
            if (pictureCorner1 == null || pictureCorner2 == null)
                return false;
            return pictureCorner1[0] == pictureCorner2[0]
                    && pictureCorner1[1] == pictureCorner2[1]
                    && pictureCorner1[2] == pictureCorner2[2];
        }

        /**
         * Returns whether the corners describe a 2×2 region in the XY plane
         * (i.e. share the same Z coordinate but differ in X and Y).
         *
         * @return {@code true} when face must be NORTH or SOUTH
         */
        boolean isPictureXYPlane() {
            if (pictureCorner1 == null || pictureCorner2 == null)
                return false;
            int dx = Math.abs(pictureCorner2[0] - pictureCorner1[0]);
            int dy = Math.abs(pictureCorner2[1] - pictureCorner1[1]);
            int dz = Math.abs(pictureCorner2[2] - pictureCorner1[2]);
            return dx == 1 && dy == 1 && dz == 0;
        }

        /**
         * Returns whether the corners describe a 2×2 region in the YZ plane.
         *
         * @return {@code true} when face must be EAST or WEST
         */
        boolean isPictureYZPlane() {
            if (pictureCorner1 == null || pictureCorner2 == null)
                return false;
            int dx = Math.abs(pictureCorner2[0] - pictureCorner1[0]);
            int dy = Math.abs(pictureCorner2[1] - pictureCorner1[1]);
            int dz = Math.abs(pictureCorner2[2] - pictureCorner1[2]);
            return dx == 0 && dy == 1 && dz == 1;
        }

        /**
         * Returns whether the given face is allowed by the current picture
         * geometry. Used by the panel to reset an incompatible face after
         * the user edits a corner.
         *
         * @param face the face to test
         * @return {@code true} if the face matches the current plane
         */
        boolean isFaceAllowed(BlockFace face) {
            if (face == null)
                return false;
            switch (pictureGeometryStatus()) {
                case VALID:
                    if (isPictureOneByOne())
                        return face == BlockFace.NORTH || face == BlockFace.SOUTH
                                || face == BlockFace.EAST || face == BlockFace.WEST;
                    if (isPictureXYPlane())
                        return face == BlockFace.NORTH || face == BlockFace.SOUTH;
                    if (isPictureYZPlane())
                        return face == BlockFace.EAST || face == BlockFace.WEST;
                    return false;
                default:
                    return false;
            }
        }
    }

    /**
     * Classification of the picture corner pair's geometric validity.
     * Independent of the chosen {@link BlockFace}.
     */
    enum PictureGeometry {

        /** At least one corner has not been set yet. */
        MISSING,

        /** Corners differ in both X and Z — not in a single plane. */
        NOT_COPLANAR,

        /** Corners are coplanar but the dimensions are not 1×1 or 2×2. */
        INVALID_SIZE,

        /** Corners form a valid 1×1 or 2×2 region. */
        VALID
    }
}
