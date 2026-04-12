package ru.ashesha.buildBattleAI.core;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.chat.ChatTypes;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessageLegacy;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessage_v1_16;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import lombok.NonNull;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.api.BBAIChatMessage;
import ru.ashesha.buildBattleAI.core.api.BBAIMessageService;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

/**
 * PacketEvents-based implementation of {@link BBAIMessageService}.
 * <p>
 * Sends all messages as raw packets via PacketEvents, bypassing Bukkit's chat API.
 * This enables rich text components with click events, hover tooltips, and precise
 * control over title timing and player list entries.
 * <p>
 * Version-dependent packet construction for chat, titles, and player list updates
 * is resolved once at startup via lambda factories (see {@link ChatPacketFactory},
 * {@link TitleSender}, {@link PlayerListPacketFactory}), avoiding runtime version checks.
 */
class MessageService implements BBAIMessageService {

    /** Serializer for converting legacy {@code &}-prefixed color codes into Adventure components. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final BuildBattleAI plugin;

    /** Version-resolved factory for creating chat message packets. */
    private final ChatPacketFactory chatPacketFactory;

    /** Version-resolved sender for title/subtitle packets. */
    private final TitleSender titleSender;

    /** Version-resolved factory for creating player list (tab) update packets. */
    private final PlayerListPacketFactory playerListPacketFactory;

    /**
     * Creates the message service and resolves all version-dependent packet factories
     * based on the current server version reported by PacketEvents.
     *
     * @param plugin the plugin instance
     */
    MessageService(@NonNull BuildBattleAI plugin) {
        this.plugin = plugin;
        ServerVersion version = PacketEvents.getAPI().getServerManager().getVersion();
        this.chatPacketFactory = resolveChatFactory(version);
        this.titleSender = resolveTitleSender(version);
        this.playerListPacketFactory = resolvePlayerListFactory(version);
    }

    // ── sendChat ─────────────────────────────────────────────────────────────

    @Override
    public void sendChat(@NonNull Player recipient, @NonNull String message) {
        sendChatPacket(recipient, toComponent(message), false);
    }

    @Override
    public void sendChat(@NonNull Collection<? extends Player> recipients, @NonNull String message) {
        Component component = toComponent(message);
        for (Player recipient : recipients) {
            sendChatPacket(recipient, component, false);
        }
    }

    @Override
    public void sendChat(@NonNull Player recipient, @NonNull BBAIChatMessage message) {
        sendChatPacket(recipient, toComponent(message), false);
    }

    @Override
    public void sendChat(@NonNull Collection<? extends Player> recipients, @NonNull BBAIChatMessage message) {
        Component component = toComponent(message);
        for (Player recipient : recipients)
            sendChatPacket(recipient, component, false);
    }

    // ── sendActionBar ────────────────────────────────────────────────────────

    @Override
    public void sendActionBar(@NonNull Player recipient, @NonNull String message) {
        plugin.getContext().sendPacket(recipient, new WrapperPlayServerActionBar(toComponent(message)));
    }

    @Override
    public void sendActionBar(@NonNull Collection<? extends Player> recipients, @NonNull String message) {
        WrapperPlayServerActionBar packet = new WrapperPlayServerActionBar(toComponent(message));
        for (Player recipient : recipients) {
            plugin.getContext().sendPacket(recipient, packet);
        }
    }

    // ── sendTitle ────────────────────────────────────────────────────────────

    @Override
    public void sendTitle(@NonNull Player recipient, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        sendTitleSequence(recipient, title, subtitle, fadeIn, stay, fadeOut);
    }

    @Override
    public void sendTitle(@NonNull Collection<? extends Player> recipients, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        for (Player recipient : recipients) {
            sendTitleSequence(recipient, title, subtitle, fadeIn, stay, fadeOut);
        }
    }

    // ── sendTab ──────────────────────────────────────────────────────────────

    @Override
    public void sendTab(@NonNull Player recipient, String header, String footer) {
        plugin.getContext().sendPacket(recipient, new WrapperPlayServerPlayerListHeaderAndFooter(
                toComponent(header),
                toComponent(footer)
        ));
    }

    // ── sendPlayerListName ───────────────────────────────────────────────────

    @Override
    public void sendPlayerListName(@NonNull Player target, String playerListName, @NonNull Collection<? extends Player> viewers) {
        Component displayName = toComponent(playerListName);
        PacketWrapper<?> packet = createPlayerListNamePacket(target, displayName);
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, packet);
    }

    // ── version-resolved factories ──────────────────────────────────────────

    /**
     * Resolves the correct chat packet factory for the running server version.
     * <ul>
     *     <li>1.19+ — uses {@code SystemChatMessage} (separate system/overlay flag)</li>
     *     <li>1.16–1.18 — uses {@code ChatMessage_v1_16} with sender UUID</li>
     *     <li>&lt;1.16 — uses {@code ChatMessageLegacy} (no sender UUID)</li>
     * </ul>
     */
    private ChatPacketFactory resolveChatFactory(ServerVersion version) {
        if (version.isNewerThanOrEquals(ServerVersion.V_1_19)) {
            return (component, overlay) -> new WrapperPlayServerSystemChatMessage(
                    overlay,
                    component
            );
        }
        if (version.isNewerThanOrEquals(ServerVersion.V_1_16)) {
            return (component, overlay) -> new WrapperPlayServerChatMessage(
                    new ChatMessage_v1_16(
                            component,
                            overlay ? ChatTypes.GAME_INFO : ChatTypes.SYSTEM,
                            new UUID(0L, 0L)
                    ));
        }
        return (component, overlay) -> new WrapperPlayServerChatMessage(
                new ChatMessageLegacy(
                        component,
                        overlay ? ChatTypes.GAME_INFO : ChatTypes.SYSTEM
                ));
    }

    /**
     * Resolves the correct title sender for the running server version.
     * <ul>
     *     <li>1.17+ — uses separate packets for times, title text, and subtitle text</li>
     *     <li>&lt;1.17 — uses the combined {@code WrapperPlayServerTitle} with action enum</li>
     * </ul>
     */
    private TitleSender resolveTitleSender(ServerVersion version) {
        if (version.isNewerThanOrEquals(ServerVersion.V_1_17)) {
            return (player, title, subtitle, fadeIn, stay, fadeOut) -> {
                plugin.getContext().sendPacket(player, new WrapperPlayServerSetTitleTimes(fadeIn, stay, fadeOut));
                if (title != null)
                    plugin.getContext().sendPacket(player, new WrapperPlayServerSetTitleText(title));
                if (subtitle != null)
                    plugin.getContext().sendPacket(player, new WrapperPlayServerSetTitleSubtitle(subtitle));
            };
        }
        return (player, title, subtitle, fadeIn, stay, fadeOut) -> {
            plugin.getContext().sendPacket(player, new WrapperPlayServerTitle(
                    WrapperPlayServerTitle.TitleAction.SET_TIMES_AND_DISPLAY,
                    (Component) null,
                    null,
                    null,
                    fadeIn,
                    stay,
                    fadeOut
            ));
            if (title != null) {
                plugin.getContext().sendPacket(player, new WrapperPlayServerTitle(
                        WrapperPlayServerTitle.TitleAction.SET_TITLE,
                        title,
                        null,
                        null,
                        0,
                        0,
                        0));
            }
            if (subtitle != null) {
                plugin.getContext().sendPacket(player, new WrapperPlayServerTitle(
                        WrapperPlayServerTitle.TitleAction.SET_SUBTITLE,
                        null,
                        subtitle,
                        null,
                        0,
                        0,
                        0
                ));
            }
        };
    }

    /**
     * Resolves the correct player list packet factory for the running server version.
     * <ul>
     *     <li>1.19.3+ — uses {@code PlayerInfoUpdate} with granular action flags</li>
     *     <li>&lt;1.19.3 — uses the legacy {@code PlayerInfo} packet</li>
     * </ul>
     */
    private PlayerListPacketFactory resolvePlayerListFactory(ServerVersion version) {
        if (version.isNewerThanOrEquals(ServerVersion.V_1_19_3)) {
            return (profile, gameMode, displayName) -> {
                WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                        profile,
                        true,
                        0,
                        gameMode,
                        displayName,
                        null
                );
                return new WrapperPlayServerPlayerInfoUpdate(
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME,
                        Collections.singletonList(info)
                );
            };
        }
        return (profile, gameMode, displayName) -> {
            WrapperPlayServerPlayerInfo.PlayerData data = new WrapperPlayServerPlayerInfo.PlayerData(
                    displayName,
                    profile,
                    gameMode,
                    0
            );
            return new WrapperPlayServerPlayerInfo(
                    WrapperPlayServerPlayerInfo.Action.UPDATE_DISPLAY_NAME,
                    Collections.singletonList(data)
            );
        };
    }

    // ── packet builders ──────────────────────────────────────────────────────

    /** Creates and sends a chat packet using the version-resolved factory. */
    private void sendChatPacket(Player player, Component component, boolean overlay) {
        plugin.getContext().sendPacket(player, chatPacketFactory.create(component, overlay));
    }

    /**
     * Sends the full title sequence: timing packet first, then title and subtitle.
     * Converts raw text strings to Adventure components before delegation.
     */
    private void sendTitleSequence(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        titleSender.send(
                player,
                toComponent(title),
                toComponent(subtitle),
                fadeIn, stay, fadeOut
        );
    }

    /**
     * Builds a player list name update packet for the target player.
     * Resolves the target's PacketEvents {@link UserProfile} via {@link PluginContext#getUserProfile(Player)}.
     *
     * @return the constructed packet, or {@code null} if the player's channel is unavailable
     */
    private PacketWrapper<?> createPlayerListNamePacket(Player target, Component displayName) {
        return playerListPacketFactory.create(
                plugin.getContext().getUserProfile(target),
                toPacketEventsGameMode(target.getGameMode()),
                displayName
        );
    }

    // ── component converters ─────────────────────────────────────────────────

    /**
     * Deserializes a legacy {@code &}-coded string into an Adventure {@link Component}.
     * Treats {@code null} as an empty string.
     */
    private Component toComponent(String text) {
        return LEGACY.deserialize(text == null ? "" : text);
    }

    /**
     * Converts a {@link BBAIChatMessage} into an Adventure {@link Component},
     * applying click events and hover tooltips from each segment.
     */
    private Component toComponent(BBAIChatMessage message) {
        net.kyori.adventure.text.TextComponent.Builder builder = Component.text();
        for (BBAIChatMessage.Segment segment : message.getSegments()) {
            Component part = toComponent(segment.getText());

            if (segment.getClickAction() != null && segment.getClickValue() != null) {
                ClickEvent click = toClickEvent(segment);
                if (click != null)
                    part = part.clickEvent(click);
            }

            if (segment.getHoverText() != null)
                part = part.hoverEvent(HoverEvent.showText(toComponent(segment.getHoverText())));

            builder.append(part);
        }
        return builder.build();
    }

    /** Maps a {@link BBAIChatMessage.ClickAction} to an Adventure {@link ClickEvent}. */
    private ClickEvent toClickEvent(BBAIChatMessage.Segment segment) {
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

    /** Converts a Bukkit {@link org.bukkit.GameMode} to the PacketEvents equivalent. */
    private GameMode toPacketEventsGameMode(org.bukkit.GameMode gameMode) {
        if (gameMode == null)
            return GameMode.SURVIVAL;
        switch (gameMode) {
            case CREATIVE:
                return GameMode.CREATIVE;
            case ADVENTURE:
                return GameMode.ADVENTURE;
            case SPECTATOR:
                return GameMode.SPECTATOR;
            default:
                return GameMode.SURVIVAL;
        }
    }

    // ── version-dispatched functional interfaces ────────────────────────────

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
     * Abstraction for sending title packets, resolved once at startup to match
     * the server's packet format (split packets in 1.17+ vs combined in older versions).
     */
    @FunctionalInterface
    private interface TitleSender {
        void send(Player player, Component title, Component subtitle, int fadeIn, int stay, int fadeOut);
    }

    /**
     * Factory for creating player list (tab) name update packets.
     * Resolved once at startup based on whether the server uses the modern
     * {@code PlayerInfoUpdate} packet (1.19.3+) or the legacy {@code PlayerInfo} packet.
     */
    @FunctionalInterface
    private interface PlayerListPacketFactory {
        PacketWrapper<?> create(UserProfile profile, GameMode gameMode, Component displayName);
    }
}
