package ru.ashesha.buildBattleAI.arena.api;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.bukkit.block.BlockFace;

import java.util.Collections;
import java.util.List;

/**
 * Immutable data representation of a build-battle arena.
 * <p>
 * Each arena owns a dedicated void world and contains:
 * <ul>
 *     <li>Global positions — lobby (where players wait) and spectator (where
 *         players go during results display, defaults to lobby if unset)</li>
 *     <li>Game parameters — player count, build time, game time, countdown</li>
 *     <li>Per-slot {@link PlotData plot definitions} — spawn point, cuboid
 *         build zone, and camera angle for AI capture</li>
 * </ul>
 * Arena instances are created during the interactive setup wizard or
 * deserialized from YAML on startup.
 *
 * @see BBAIArenaManager
 */
@Getter
@Accessors(fluent = true)
public class Arena {

    /** Unique arena name (matches the YAML filename without extension). */
    @NonNull
    private final String name;

    /** Name of the dedicated void world for this arena. */
    @NonNull
    private final String worldName;

    /** Maximum number of concurrent players (and plots), 2–8. */
    private final int maxPlayers;

    /** Whether the arena is active and should be loaded on startup. */
    @Setter
    private boolean enabled;

    /** Where players wait before the game starts. Never {@code null}. */
    @NonNull
    private final Position lobby;

    /**
     * Where players/spectators go during the results phase.
     * May be {@code null} — game logic should fall back to {@link #lobby}.
     */
    private final Position spectator;

    /** Minimum players required to start a game. Defaults to 2. */
    private final int minPlayers;

    /** Duration of each building phase in seconds. Defaults to 150. */
    private final int buildTime;

    /** Total game session duration in seconds. Defaults to 300. */
    private final int gameTime;

    /** Countdown duration before game start in seconds. Defaults to 5. */
    private final int countdownTime;

    /** Per-slot build zones and camera positions (indexed 0..maxPlayers-1). */
    @NonNull
    private final List<PlotData> plots;

    /**
     * Creates a new arena definition.
     *
     * @param name       unique arena name
     * @param worldName  dedicated world name (convention: {@code bbai_<name>})
     * @param maxPlayers maximum players (2–8)
     * @param enabled    whether the arena loads on startup
     * @param lobby      lobby spawn position (required)
     * @param spectator  spectator/results position (nullable, defaults to lobby)
     * @param minPlayers    minimum players to start (default 2)
     * @param buildTime     build phase duration in seconds (default 150)
     * @param gameTime      total game session duration in seconds (default 300)
     * @param countdownTime countdown before start in seconds (default 5)
     * @param plots         plot definitions (one per player slot)
     */
    public Arena(@NonNull String name, @NonNull String worldName, int maxPlayers,
                 boolean enabled, @NonNull Position lobby, Position spectator,
                 int minPlayers, int buildTime, int gameTime, int countdownTime,
                 @NonNull List<PlotData> plots) {
        this.name = name;
        this.worldName = worldName;
        this.maxPlayers = maxPlayers;
        this.enabled = enabled;
        this.lobby = lobby;
        this.spectator = spectator;
        this.minPlayers = minPlayers;
        this.buildTime = buildTime;
        this.gameTime = gameTime;
        this.countdownTime = countdownTime;
        this.plots = Collections.unmodifiableList(plots);
    }

    /**
     * Returns the effective spectator position — the explicit spectator
     * if set, otherwise falls back to the lobby.
     *
     * @return non-null spectator position
     */
    public Position effectiveSpectator() {
        return spectator != null ? spectator : lobby;
    }

    // ── inner types ─────────────────────────────────────────────────────

    /**
     * A precise world position with rotation, used for spawn points,
     * camera angles, lobby, and spectator locations.
     */
    @Getter
    @Accessors(fluent = true)
    @AllArgsConstructor
    public static class Position {

        /** X world coordinate. */
        private final double x;
        /** Y world coordinate. */
        private final double y;
        /** Z world coordinate. */
        private final double z;
        /** Horizontal rotation (degrees). */
        private final float yaw;
        /** Vertical rotation (degrees). */
        private final float pitch;
    }

    /**
     * Defines one player's build plot: spawn point, cuboid build zone
     * (two opposite corners), camera angles for the AI renderer, and
     * the picture region where the rendered preview is displayed in-world.
     */
    @Getter
    @Accessors(fluent = true)
    public static class PlotData {

        /** Where the player teleports to start building. */
        @NonNull
        private final Position spawn;

        /** Build zone boundary corners (block coordinates). */
        private final int corner1X, corner1Y, corner1Z;
        private final int corner2X, corner2Y, corner2Z;

        /**
         * Camera positions and angles for the renderer capture.
         * Each arena plot has exactly 3 cameras, providing multiple
         * viewing angles for the AI classifier.
         */
        @NonNull
        private final List<Position> cameras;

        /**
         * The 1×1 or 2×2 in-world picture region used to display the
         * renderer's output. The region is always flat (lying in either
         * the XY or YZ plane) and faces one of the four cardinal directions.
         */
        @NonNull
        private final PictureRegion picture;

        /**
         * Creates a plot definition.
         *
         * @param spawn    player spawn point
         * @param corner1X first corner X (block coordinate)
         * @param corner1Y first corner Y (block coordinate)
         * @param corner1Z first corner Z (block coordinate)
         * @param corner2X opposite corner X (block coordinate)
         * @param corner2Y opposite corner Y (block coordinate)
         * @param corner2Z opposite corner Z (block coordinate)
         * @param cameras  renderer camera positions and angles (exactly 3)
         * @param picture  picture region where the rendered preview is shown
         */
        public PlotData(@NonNull Position spawn,
                        int corner1X, int corner1Y, int corner1Z,
                        int corner2X, int corner2Y, int corner2Z,
                        @NonNull List<Position> cameras,
                        @NonNull PictureRegion picture) {
            this.spawn = spawn;
            this.corner1X = corner1X;
            this.corner1Y = corner1Y;
            this.corner1Z = corner1Z;
            this.corner2X = corner2X;
            this.corner2Y = corner2Y;
            this.corner2Z = corner2Z;
            this.cameras = Collections.unmodifiableList(cameras);
            this.picture = picture;
        }
    }

    /**
     * Flat 1×1 or 2×2 picture region attached to a wall.
     * <p>
     * The region is defined by two block-aligned corners that must be
     * coplanar (sharing either the same X or the same Z coordinate). The
     * accepted dimensions are exactly 1×1 (both corners refer to the same
     * block) or 2×2 (corners differ by one in both the horizontal and the
     * vertical axes of the plane).
     * <p>
     * The {@link #face} indicates the cardinal direction the picture faces.
     * For 2×2 regions in the XY plane it must be {@code NORTH} or
     * {@code SOUTH}; in the YZ plane it must be {@code EAST} or
     * {@code WEST}. For 1×1 regions the plane is ambiguous so any of the
     * four cardinal directions is permitted.
     */
    @Getter
    @Accessors(fluent = true)
    public static class PictureRegion {

        /** First corner (block coordinate). */
        private final int corner1X, corner1Y, corner1Z;

        /** Opposite corner (block coordinate). For 1×1 regions equal to corner 1. */
        private final int corner2X, corner2Y, corner2Z;

        /**
         * Cardinal direction the picture faces. Always one of
         * {@code NORTH}, {@code SOUTH}, {@code EAST}, {@code WEST}.
         */
        @NonNull
        private final BlockFace face;

        /**
         * Creates a picture region after validating coplanarity, dimensions,
         * and face/plane compatibility.
         *
         * @param corner1X first corner X (block coordinate)
         * @param corner1Y first corner Y (block coordinate)
         * @param corner1Z first corner Z (block coordinate)
         * @param corner2X opposite corner X (block coordinate)
         * @param corner2Y opposite corner Y (block coordinate)
         * @param corner2Z opposite corner Z (block coordinate)
         * @param face     cardinal facing direction
         * @throws IllegalArgumentException if the geometry or face is invalid
         */
        public PictureRegion(int corner1X, int corner1Y, int corner1Z,
                             int corner2X, int corner2Y, int corner2Z,
                             @NonNull BlockFace face) {
            this.corner1X = corner1X;
            this.corner1Y = corner1Y;
            this.corner1Z = corner1Z;
            this.corner2X = corner2X;
            this.corner2Y = corner2Y;
            this.corner2Z = corner2Z;
            this.face = face;
            validate();
        }

        /**
         * Returns the picture width in blocks (1 or 2).
         * <p>
         * Width is measured along the horizontal axis of the picture's
         * plane: along X for XY-plane regions (NORTH/SOUTH faces) and
         * along Z for YZ-plane regions (EAST/WEST faces).
         *
         * @return 1 or 2
         */
        public int width() {
            if (face == BlockFace.EAST || face == BlockFace.WEST)
                return Math.abs(corner2Z - corner1Z) + 1;
            return Math.abs(corner2X - corner1X) + 1;
        }

        /**
         * Returns the picture height in blocks (1 or 2), measured along Y.
         *
         * @return 1 or 2
         */
        public int height() {
            return Math.abs(corner2Y - corner1Y) + 1;
        }

        /**
         * Returns the block X coordinate of the anchor (minimum X corner)
         * required by {@code BBAIPictureService#spawn}.
         *
         * @return anchor X
         */
        public int anchorX() {
            return Math.min(corner1X, corner2X);
        }

        /**
         * Returns the block Y coordinate of the anchor (minimum Y corner).
         *
         * @return anchor Y
         */
        public int anchorY() {
            return Math.min(corner1Y, corner2Y);
        }

        /**
         * Returns the block Z coordinate of the anchor (minimum Z corner).
         *
         * @return anchor Z
         */
        public int anchorZ() {
            return Math.min(corner1Z, corner2Z);
        }

        /**
         * Validates coplanarity, dimensions, and face/plane compatibility.
         * Called from the constructor; throws on any violation.
         */
        private void validate() {
            int dx = Math.abs(corner2X - corner1X);
            int dy = Math.abs(corner2Y - corner1Y);
            int dz = Math.abs(corner2Z - corner1Z);

            // 1×1 — corners coincide; all 4 cardinal faces are valid.
            if (dx == 0 && dy == 0 && dz == 0) {
                if (!isCardinal(face))
                    throw new IllegalArgumentException(
                            "picture face must be NORTH, SOUTH, EAST, or WEST (got "
                                    + face + ")");
                return;
            }

            // 2×2 in YZ-plane — picture faces EAST or WEST.
            if (dx == 0 && dy == 1 && dz == 1) {
                if (face != BlockFace.EAST && face != BlockFace.WEST)
                    throw new IllegalArgumentException(
                            "picture face for a YZ-plane 2×2 region must be EAST or WEST (got "
                                    + face + ")");
                return;
            }

            // 2×2 in XY-plane — picture faces NORTH or SOUTH.
            if (dx == 1 && dy == 1 && dz == 0) {
                if (face != BlockFace.NORTH && face != BlockFace.SOUTH)
                    throw new IllegalArgumentException(
                            "picture face for an XY-plane 2×2 region must be NORTH or SOUTH (got "
                                    + face + ")");
                return;
            }

            throw new IllegalArgumentException(
                    "picture region must be 1×1 (corners coincide) or 2×2 (coplanar in XY or YZ); got "
                            + "dx=" + dx + ", dy=" + dy + ", dz=" + dz);
        }

        /** Returns whether the given face is one of the four cardinal wall faces. */
        private static boolean isCardinal(BlockFace face) {
            return face == BlockFace.NORTH || face == BlockFace.SOUTH
                    || face == BlockFace.EAST || face == BlockFace.WEST;
        }
    }
}
