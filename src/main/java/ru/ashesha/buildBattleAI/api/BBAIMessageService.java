package ru.ashesha.buildBattleAI.api;

import lombok.NonNull;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collection;

/**
 * Service for sending packet-based messages to players.
 * <p>
 * All messages are sent via PacketEvents rather than the Bukkit API, enabling
 * support for rich text components with click actions, hover tooltips, and
 * version-independent packet construction.
 * <p>
 * Text parameters support legacy {@code &} color codes (e.g., {@code &a} for green).
 *
 * @see BBAIChatMessage
 * @see BBAITitleTimes
 */
public interface BBAIMessageService {

    /**
     * Sends a plain-text chat message to a single player.
     *
     * @param recipient the target player
     * @param message   the message text (supports {@code &} color codes)
     */
    void sendChat(@NonNull Player recipient, @NonNull String message);

    /**
     * Sends a plain-text chat message to multiple players.
     *
     * @param recipients the target players
     * @param message    the message text (supports {@code &} color codes)
     */
    void sendChat(@NonNull Collection<? extends Player> recipients, @NonNull String message);

    /**
     * Sends a rich chat message to a single player.
     *
     * @param recipient the target player
     * @param message   the rich message with segments, click actions, and tooltips
     */
    void sendChat(@NonNull Player recipient, @NonNull BBAIChatMessage message);

    /**
     * Sends a rich chat message to multiple players.
     *
     * @param recipients the target players
     * @param message    the rich message with segments, click actions, and tooltips
     */
    void sendChat(@NonNull Collection<? extends Player> recipients, @NonNull BBAIChatMessage message);

    /**
     * Sends an action bar message displayed above the hotbar.
     *
     * @param recipient the target player
     * @param message   the message text (supports {@code &} color codes)
     */
    void sendActionBar(@NonNull Player recipient, @NonNull String message);

    /**
     * Sends an action bar message to multiple players.
     *
     * @param recipients the target players
     * @param message    the message text (supports {@code &} color codes)
     */
    void sendActionBar(@NonNull Collection<? extends Player> recipients, @NonNull String message);

    /**
     * Sends a title and subtitle overlay to a single player.
     *
     * @param recipient the target player
     * @param title     the main title text, or {@code null} to skip
     * @param subtitle  the subtitle text, or {@code null} to skip
     * @param times     fade-in, stay, and fade-out durations in ticks
     */
    void sendTitle(@NonNull Player recipient, String title, String subtitle, BBAITitleTimes times);

    /**
     * Sends a title and subtitle overlay to multiple players.
     *
     * @param recipients the target players
     * @param title      the main title text, or {@code null} to skip
     * @param subtitle   the subtitle text, or {@code null} to skip
     * @param times      fade-in, stay, and fade-out durations in ticks
     */
    void sendTitle(@NonNull Collection<? extends Player> recipients, String title, String subtitle, BBAITitleTimes times);

    /**
     * Sets the player list (tab) header and footer for a single player.
     *
     * @param recipient the target player
     * @param header    the header text (supports {@code &} color codes and {@code \n})
     * @param footer    the footer text (supports {@code &} color codes and {@code \n})
     */
    void sendTab(@NonNull Player recipient, String header, String footer);

    /**
     * Updates a player's display name in the player list (tab) for the specified viewers.
     *
     * @param target         the player whose list name to change
     * @param playerListName the new display name (supports {@code &} color codes),
     *                       or {@code null} to reset to the default name
     * @param viewers        the players who will see the updated name
     */
    void sendPlayerListName(@NonNull Player target, String playerListName, @NonNull Collection<? extends Player> viewers);

    /** Varargs convenience overload for {@link #sendChat(Collection, String)}. */
    default void sendChat(String message, Player... recipients) {
        sendChat(Arrays.asList(recipients), message);
    }

    /** Varargs convenience overload for {@link #sendChat(Collection, BBAIChatMessage)}. */
    default void sendChat(BBAIChatMessage message, Player... recipients) {
        sendChat(Arrays.asList(recipients), message);
    }

    /** Varargs convenience overload for {@link #sendActionBar(Collection, String)}. */
    default void sendActionBar(String message, Player... recipients) {
        sendActionBar(Arrays.asList(recipients), message);
    }

    /** Varargs convenience overload for {@link #sendTitle(Collection, String, String, BBAITitleTimes)}. */
    default void sendTitle(String title, String subtitle, BBAITitleTimes times, Player... recipients) {
        sendTitle(Arrays.asList(recipients), title, subtitle, times);
    }
}
