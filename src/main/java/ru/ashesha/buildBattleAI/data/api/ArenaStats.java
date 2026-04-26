package ru.ashesha.buildBattleAI.data.api;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * Persistent per-arena statistics and metadata.
 * <p>
 * Tracks cumulative runtime statistics for a named arena. This is
 * intentionally separate from the arena's structural configuration
 * (world, coordinates, plot layout) managed by
 * {@link ru.ashesha.buildBattleAI.config.api.BBAIConfigService} via
 * arena YAML files. The {@link #name} field links the two: it matches
 * the arena name used in {@code ConfigService.getArenaConfig(name)}.
 * <p>
 * Implements {@link Serializable} for Apache Ignite binary serialization
 * and is Gson-friendly for local JSON file storage.
 *
 * @see BBAIDataService
 */
@Getter
@Setter
@Accessors(fluent = true)
public class ArenaStats implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The arena name (immutable primary key, matches ConfigService arena name). */
    private final String name;

    /** Total number of games played in this arena. */
    private int totalGames;

    /** Total number of unique players who have played in this arena. */
    private int totalPlayers;

    /** Highest score ever achieved in this arena. */
    private int highestScore;

    /** UUID string of the player who holds the highest score, or {@code null}. */
    private String highestScorePlayer;

    /** Timestamp (epoch milliseconds) of the last game played in this arena. */
    private long lastGameTime;

    /**
     * Creates arena stats with default (zero) counters.
     *
     * @param name the arena name
     */
    public ArenaStats(@NonNull String name) {
        this.name = name;
    }
}
