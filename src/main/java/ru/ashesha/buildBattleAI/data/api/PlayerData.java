package ru.ashesha.buildBattleAI.data.api;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.UUID;

/**
 * Persistent per-player statistics and metadata.
 * <p>
 * Mutable value object: game logic reads fields, updates them, then calls
 * {@link BBAIDataService#savePlayer(PlayerData)} to persist changes. The
 * {@link #uuid} field is the immutable primary key used for storage lookups.
 * <p>
 * Implements {@link Serializable} for Apache Ignite binary serialization
 * and is Gson-friendly for local JSON file storage (no transient fields
 * that carry business state, no circular references).
 *
 * @see BBAIDataService
 */
@Getter
@Setter
@Accessors(fluent = true)
public class PlayerData implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The player's Minecraft UUID (immutable primary key). */
    private final UUID uuid;

    /** The player's last known display name (updated on each login). */
    private String name;

    /** Total number of games won. */
    private int wins;

    /** Total number of games lost. */
    private int losses;

    /** Total number of games played (wins + losses + draws/disconnects). */
    private int gamesPlayed;

    /** Cumulative score across all games. */
    private long totalScore;

    /** Highest score achieved in a single game. */
    private int bestScore;

    /** Timestamp (epoch milliseconds) of the player's first recorded join. */
    private long firstJoin;

    /** Timestamp (epoch milliseconds) of the player's last completed game. */
    private long lastPlayed;

    /**
     * Player's chosen UI language code (e.g. {@code "en"}, {@code "ru"}), or
     * {@code null} to use the server's default language. Set via the
     * {@code /bbai lang} command.
     */
    private String language;

    /**
     * Creates player data with sensible defaults for a first-time player.
     * The {@link #firstJoin} and {@link #lastPlayed} fields are set to the
     * current system time; all counters start at zero.
     *
     * @param uuid the player's Minecraft UUID
     * @param name the player's current display name
     */
    public PlayerData(@NonNull UUID uuid, @NonNull String name) {
        this.uuid = uuid;
        this.name = name;
        this.firstJoin = System.currentTimeMillis();
        this.lastPlayed = this.firstJoin;
    }
}
