package ru.ashesha.buildBattleAI.arena.api;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;

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
    public static class Position {

        private final double x, y, z;
        private final float yaw, pitch;

        /**
         * Creates a position.
         *
         * @param x     X coordinate
         * @param y     Y coordinate
         * @param z     Z coordinate
         * @param yaw   horizontal rotation (degrees)
         * @param pitch vertical rotation (degrees)
         */
        public Position(double x, double y, double z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    /**
     * Defines one player's build plot: spawn point, cuboid build zone
     * (two opposite corners), and camera angle for the AI renderer.
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
         */
        public PlotData(@NonNull Position spawn,
                        int corner1X, int corner1Y, int corner1Z,
                        int corner2X, int corner2Y, int corner2Z,
                        @NonNull List<Position> cameras) {
            this.spawn = spawn;
            this.corner1X = corner1X;
            this.corner1Y = corner1Y;
            this.corner1Z = corner1Z;
            this.corner2X = corner2X;
            this.corner2Y = corner2Y;
            this.corner2Z = corner2Z;
            this.cameras = Collections.unmodifiableList(cameras);
        }
    }
}
