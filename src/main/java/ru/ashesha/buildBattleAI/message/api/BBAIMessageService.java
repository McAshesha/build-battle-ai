package ru.ashesha.buildBattleAI.message.api;

import lombok.NonNull;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.message.micro.BoardMicroService;
import ru.ashesha.buildBattleAI.message.micro.ChatMicroService;

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
 * @see ChatMicroService.ChatMessage
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
    void sendChat(@NonNull Player recipient, @NonNull ChatMicroService.ChatMessage message);

    /**
     * Sends a rich chat message to multiple players.
     *
     * @param recipients the target players
     * @param message    the rich message with segments, click actions, and tooltips
     */
    void sendChat(@NonNull Collection<? extends Player> recipients, @NonNull ChatMicroService.ChatMessage message);

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
     * @param fadeIn    fade-in duration in ticks
     * @param stay      stay duration in ticks
     * @param fadeOut   fade-out duration in ticks
     */
    void sendTitle(@NonNull Player recipient, String title, String subtitle, int fadeIn, int stay, int fadeOut);

    /**
     * Sends a title and subtitle overlay to multiple players.
     *
     * @param recipients the target players
     * @param title      the main title text, or {@code null} to skip
     * @param subtitle   the subtitle text, or {@code null} to skip
     * @param fadeIn     fade-in duration in ticks
     * @param stay       stay duration in ticks
     * @param fadeOut    fade-out duration in ticks
     */
    void sendTitle(@NonNull Collection<? extends Player> recipients, String title, String subtitle, int fadeIn, int stay, int fadeOut);

    /**
     * Sets the player list (tab) header and footer for a single player.
     *
     * @param recipient the target player
     * @param header    the header text (supports {@code &} color codes and {@code \n})
     * @param footer    the footer text (supports {@code &} color codes and {@code \n})
     */
    void sendTab(@NonNull Player recipient, String header, String footer);

    /**
     * Sets the player list (tab) header and footer for multiple players.
     *
     * @param recipients the target players
     * @param header     the header text (supports {@code &} color codes and {@code \n})
     * @param footer     the footer text (supports {@code &} color codes and {@code \n})
     */
    void sendTab(@NonNull Collection<? extends Player> recipients, String header, String footer);

    /**
     * Updates a player's display name in the player list (tab) for a single viewer.
     *
     * @param target         the player whose list name to change
     * @param playerListName the new display name (supports {@code &} color codes),
     *                       or {@code null} to reset to the default name
     * @param viewer         the player who will see the updated name
     */
    void sendPlayerListName(@NonNull Player target, String playerListName, @NonNull Player viewer);

    /**
     * Updates a player's display name in the player list (tab) for the specified viewers.
     *
     * @param target         the player whose list name to change
     * @param playerListName the new display name (supports {@code &} color codes),
     *                       or {@code null} to reset to the default name
     * @param viewers        the players who will see the updated name
     */
    void sendPlayerListName(@NonNull Player target, String playerListName, @NonNull Collection<? extends Player> viewers);

    // ── Scoreboard (Board) ─────────────────────────────────────────────

    /**
     * Creates a new sidebar scoreboard for the given player and displays it
     * immediately. The returned {@link BoardMicroService.Board} is not tracked
     * by this service — the caller must store the reference and manage its
     * lifecycle (including calling {@link BoardMicroService.Board#remove} when done).
     *
     * @param player the target player
     * @param title  the scoreboard title (supports {@code &} color codes)
     * @return the newly created board
     */
    BoardMicroService.Board createBoard(@NonNull Player player, @NonNull String title);

    /**
     * Creates a new sidebar scoreboard for multiple players and displays it
     * immediately. The returned {@link BoardMicroService.Board} is shared across
     * all recipients — the caller must store the reference and manage its lifecycle.
     *
     * @param players the target players
     * @param title   the scoreboard title (supports {@code &} color codes)
     * @return the newly created board
     */
    BoardMicroService.Board createBoard(@NonNull Collection<? extends Player> players, @NonNull String title);

}
