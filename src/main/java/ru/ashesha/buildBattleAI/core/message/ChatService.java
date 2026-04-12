package ru.ashesha.buildBattleAI.core.message;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.chat.ChatTypes;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessageLegacy;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessage_v1_16;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.util.MessageUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Sub-service responsible for sending chat messages via packets.
 * <p>
 * Supports both plain-text messages (with {@code &} color codes) and rich
 * {@link ChatMessage} instances with click actions and hover tooltips.
 * The underlying packet format is resolved once at construction based on
 * the server version.
 */
public class ChatService {

    private final BuildBattleAI plugin;

    /**
     * Version-resolved factory for creating chat message packets.
     */
    private final ChatPacketFactory chatPacketFactory;

    /**
     * Creates the chat service and resolves the version-appropriate packet factory.
     *
     * @param plugin  the plugin instance
     * @param version the current server version
     */
    public ChatService(@NonNull BuildBattleAI plugin, @NonNull ServerVersion version) {
        this.plugin = plugin;
        this.chatPacketFactory = resolveChatFactory(version);
    }

    // ── inner types ─────────────────────────────────────────────────────────

    /**
     * Sends a plain-text chat message to a single player.
     *
     * @param recipient the target player
     * @param message   the message text (supports {@code &} color codes)
     */
    public void sendChat(@NonNull Player recipient, @NonNull String message) {
        sendChatPacket(recipient, MessageUtils.toComponent(message));
    }

    /**
     * Sends a plain-text chat message to multiple players.
     *
     * @param recipients the target players
     * @param message    the message text (supports {@code &} color codes)
     */
    public void sendChat(@NonNull Collection<? extends Player> recipients, @NonNull String message) {
        Component component = MessageUtils.toComponent(message);
        for (Player recipient : recipients)
            sendChatPacket(recipient, component);
    }

    /**
     * Sends a rich chat message to a single player.
     *
     * @param recipient the target player
     * @param message   the rich message with segments, click actions, and tooltips
     */
    public void sendChat(@NonNull Player recipient, @NonNull ChatMessage message) {
        sendChatPacket(recipient, toComponent(message));
    }

    // ── public API ──────────────────────────────────────────────────────────

    /**
     * Sends a rich chat message to multiple players.
     *
     * @param recipients the target players
     * @param message    the rich message with segments, click actions, and tooltips
     */
    public void sendChat(@NonNull Collection<? extends Player> recipients, @NonNull ChatMessage message) {
        Component component = toComponent(message);
        for (Player recipient : recipients)
            sendChatPacket(recipient, component);
    }

    /**
     * Resolves the correct chat packet factory for the running server version.
     * <ul>
     *     <li>1.19+ — uses {@code SystemChatMessage} (separate system/overlay flag)</li>
     *     <li>1.16–1.18 — uses {@code ChatMessage_v1_16} with sender UUID</li>
     *     <li>&lt;1.16 — uses {@code ChatMessageLegacy} (no sender UUID)</li>
     * </ul>
     */
    @SuppressWarnings("deprecation")
    private ChatPacketFactory resolveChatFactory(ServerVersion version) {
        if (version.isNewerThanOrEquals(ServerVersion.V_1_19))
            return (component, overlay) -> new WrapperPlayServerSystemChatMessage(
                    overlay,
                    component
            );
        if (version.isNewerThanOrEquals(ServerVersion.V_1_16))
            return (component, overlay) -> new WrapperPlayServerChatMessage(
                    new ChatMessage_v1_16(
                            component,
                            overlay ? ChatTypes.GAME_INFO : ChatTypes.SYSTEM,
                            new UUID(0L, 0L)
                    ));
        return (component, overlay) -> new WrapperPlayServerChatMessage(
                new ChatMessageLegacy(
                        component,
                        overlay ? ChatTypes.GAME_INFO : ChatTypes.SYSTEM
                ));
    }

    /**
     * Creates and sends a chat packet using the version-resolved factory.
     */
    private void sendChatPacket(Player player, Component component) {
        plugin.getContext().sendPacket(player, chatPacketFactory.create(component, false));
    }

    /**
     * Converts a {@link ChatMessage} into an Adventure {@link Component},
     * applying click events and hover tooltips from each segment.
     * Iterates over a defensive copy of the segment list.
     */
    private Component toComponent(ChatMessage message) {
        net.kyori.adventure.text.TextComponent.Builder builder = Component.text();
        for (Segment segment : new ArrayList<>(message.getSegments())) {
            Component part = MessageUtils.toComponent(segment.getText());

            if (segment.getClickAction() != null && segment.getClickValue() != null) {
                ClickEvent click = toClickEvent(segment);
                if (click != null)
                    part = part.clickEvent(click);
            }

            if (segment.getHoverText() != null)
                part = part.hoverEvent(HoverEvent.showText(MessageUtils.toComponent(segment.getHoverText())));

            builder.append(part);
        }
        return builder.build();
    }

    // ── version-resolved factory ────────────────────────────────────────────

    /**
     * Maps a {@link ClickAction} to an Adventure {@link ClickEvent}.
     */
    private ClickEvent toClickEvent(Segment segment) {
        switch (segment.getClickAction()) {
            case OPEN_URL:
                return ClickEvent.openUrl(segment.getClickValue());
            case RUN_COMMAND:
                return ClickEvent.runCommand(segment.getClickValue());
            case SUGGEST_COMMAND:
                return ClickEvent.suggestCommand(segment.getClickValue());
            default:
                return null;
        }
    }

    // ── packet builder ──────────────────────────────────────────────────────

    /**
     * Actions that can be triggered when a player clicks on a message segment.
     */
    public enum ClickAction {
        /**
         * Opens a URL in the player's browser.
         */
        OPEN_URL,
        /**
         * Immediately executes a command as the player.
         */
        RUN_COMMAND,
        /**
         * Inserts a command into the player's chat input without executing it.
         */
        SUGGEST_COMMAND
    }

    // ── component converters ────────────────────────────────────────────────

    /**
     * Factory for creating version-appropriate chat message packets.
     * The {@code overlay} flag controls whether the message is shown in the action bar
     * overlay slot ({@code true}) or the regular chat area ({@code false}).
     */
    @FunctionalInterface
    private interface ChatPacketFactory {
        PacketWrapper<?> create(Component component, boolean overlay);
    }

    /**
     * A single segment of a rich chat message. Each segment contains display text
     * and optional interactive properties (click action and hover tooltip).
     */
    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static class Segment {

        /**
         * The display text for this segment (supports {@code &} color codes).
         */
        @NonNull
        private final String text;
        /**
         * The action triggered on click, or {@code null} for no click behavior.
         */
        private final ClickAction clickAction;
        /**
         * The value associated with the click action (URL, command, etc.), or {@code null}.
         */
        private final String clickValue;
        /**
         * The tooltip text shown on hover, or {@code null} for no tooltip.
         */
        private final String hoverText;
    }

    // ── version-dispatched functional interface ─────────────────────────────

    /**
     * Mutable rich chat message composed of {@link Segment}s.
     * Each segment carries display text and optional interactive properties
     * (click action, hover tooltip). Instances are built via the fluent
     * {@code append()} API and passed directly to
     * {@link ChatService#sendChat(Player, ChatMessage)}.
     */
    public static class ChatMessage {

        /**
         * Internal segment list. Accessible only from the enclosing {@link ChatService}.
         */
        @Getter(AccessLevel.PRIVATE)
        private final List<Segment> segments = new ArrayList<>();

        /**
         * Appends a plain-text segment with no interactive properties.
         *
         * @param text the display text
         * @return this message for chaining
         */
        public ChatMessage append(@NonNull String text) {
            return append(text, null, null, null);
        }

        /**
         * Appends a text segment with a hover tooltip but no click action.
         *
         * @param text      the display text
         * @param hoverText the tooltip shown on hover
         * @return this message for chaining
         */
        public ChatMessage append(@NonNull String text, String hoverText) {
            return append(text, null, null, hoverText);
        }

        /**
         * Appends a clickable text segment with no hover tooltip.
         *
         * @param text        the display text
         * @param clickAction the action triggered on click
         * @param clickValue  the value for the click action (URL or command)
         * @return this message for chaining
         */
        public ChatMessage append(@NonNull String text, ClickAction clickAction, String clickValue) {
            return append(text, clickAction, clickValue, null);
        }

        /**
         * Appends a fully configured text segment with click action and hover tooltip.
         *
         * @param text        the display text
         * @param clickAction the action triggered on click, or {@code null}
         * @param clickValue  the value for the click action, or {@code null}
         * @param hoverText   the tooltip shown on hover, or {@code null}
         * @return this message for chaining
         */
        public ChatMessage append(@NonNull String text, ClickAction clickAction, String clickValue, String hoverText) {
            segments.add(new Segment(text, clickAction, clickValue, hoverText));
            return this;
        }
    }
}
