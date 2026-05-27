package ru.ashesha.buildBattleAI.arena;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
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
     * plot 1..maxPlayers: spawn, corner1, corner2, camera1, camera2, camera3.
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

        /**
         * Checks whether all required plot fields are set.
         *
         * @return {@code true} if spawn, corner1, corner2, and all 3 cameras are non-null
         */
        boolean isComplete() {
            return spawn != null && corner1 != null && corner2 != null
                    && camera1 != null && camera2 != null && camera3 != null;
        }
    }
}
