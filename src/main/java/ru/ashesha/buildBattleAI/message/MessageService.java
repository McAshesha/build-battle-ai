package ru.ashesha.buildBattleAI.message;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.chat.ChatTypes;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessageLegacy;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessage_v1_16;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.api.BBAIChatMessage;
import ru.ashesha.buildBattleAI.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.api.BBAITitleTimes;

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
public class MessageService implements BBAIMessageService {

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
    public MessageService(BuildBattleAI plugin) {
        this.plugin = plugin;
        ServerVersion version = PacketEvents.getAPI().getServerManager().getVersion();
        this.chatPacketFactory = resolveChatFactory(version);
        this.titleSender = resolveTitleSender(version);
        this.playerListPacketFactory = resolvePlayerListFactory(version);
    }

    // ── sendChat ─────────────────────────────────────────────────────────────

    @Override
    public void sendChat(Player recipient, String message) {
        if (recipient == null) return;
        sendChatPacket(recipient, toComponent(message), false);
    }

    @Override
    public void sendChat(Collection<? extends Player> recipients, String message) {
        if (recipients == null) return;
        Component component = toComponent(message);
        for (Player recipient : recipients) {
            sendChatPacket(recipient, component, false);
        }
    }

    @Override
    public void sendChat(Player recipient, BBAIChatMessage message) {
        if (recipient == null || message == null) return;
        sendChatPacket(recipient, toComponent(message), false);
    }

    @Override
    public void sendChat(Collection<? extends Player> recipients, BBAIChatMessage message) {
        if (recipients == null || message == null) return;
        Component component = toComponent(message);
        for (Player recipient : recipients) {
            sendChatPacket(recipient, component, false);
        }
    }

    // ── sendActionBar ────────────────────────────────────────────────────────

    @Override
    public void sendActionBar(Player recipient, String message) {
        if (recipient == null) return;
        sendPacket(recipient, new WrapperPlayServerActionBar(toComponent(message)));
    }

    @Override
    public void sendActionBar(Collection<? extends Player> recipients, String message) {
        if (recipients == null) return;
        WrapperPlayServerActionBar packet = new WrapperPlayServerActionBar(toComponent(message));
        for (Player recipient : recipients) {
            sendPacket(recipient, packet);
        }
    }

    // ── sendTitle ────────────────────────────────────────────────────────────

    @Override
    public void sendTitle(Player recipient, String title, String subtitle, BBAITitleTimes times) {
        if (recipient == null) return;
        sendTitleSequence(recipient, title, subtitle, times == null ? BBAITitleTimes.DEFAULT : times);
    }

    @Override
    public void sendTitle(Collection<? extends Player> recipients, String title, String subtitle, BBAITitleTimes times) {
        if (recipients == null) return;
        BBAITitleTimes effectiveTimes = times == null ? BBAITitleTimes.DEFAULT : times;
        for (Player recipient : recipients) {
            sendTitleSequence(recipient, title, subtitle, effectiveTimes);
        }
    }

    // ── sendTab ──────────────────────────────────────────────────────────────

    @Override
    public void sendTab(Player recipient, String header, String footer) {
        if (recipient == null) return;
        sendPacket(recipient, new WrapperPlayServerPlayerListHeaderAndFooter(
                toComponent(header), toComponent(footer)));
    }

    // ── sendPlayerListName ───────────────────────────────────────────────────

    @Override
    public void sendPlayerListName(Player target, String playerListName, Collection<? extends Player> viewers) {
        if (target == null || viewers == null) return;
        Component displayName = playerListName == null ? null : toComponent(playerListName);
        PacketWrapper<?> packet = createPlayerListNamePacket(target, displayName);
        if (packet == null) return;
        for (Player viewer : viewers) {
            sendPacket(viewer, packet);
        }
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
            return (component, overlay) ->
                    new WrapperPlayServerSystemChatMessage(overlay, component);
        }
        if (version.isNewerThanOrEquals(ServerVersion.V_1_16)) {
            return (component, overlay) -> new WrapperPlayServerChatMessage(
                    new ChatMessage_v1_16(component,
                            overlay ? ChatTypes.GAME_INFO : ChatTypes.SYSTEM,
                            new UUID(0L, 0L)));
        }
        return (component, overlay) -> new WrapperPlayServerChatMessage(
                new ChatMessageLegacy(component,
                        overlay ? ChatTypes.GAME_INFO : ChatTypes.SYSTEM));
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
            return (player, title, subtitle, times) -> {
                sendPacket(player, new WrapperPlayServerSetTitleTimes(
                        times.getFadeIn(), times.getStay(), times.getFadeOut()));
                if (title != null) {
                    sendPacket(player, new WrapperPlayServerSetTitleText(title));
                }
                if (subtitle != null) {
                    sendPacket(player, new WrapperPlayServerSetTitleSubtitle(subtitle));
                }
            };
        }
        return (player, title, subtitle, times) -> {
            sendPacket(player, new WrapperPlayServerTitle(
                    WrapperPlayServerTitle.TitleAction.SET_TIMES_AND_DISPLAY,
                    (Component) null, null, null,
                    times.getFadeIn(), times.getStay(), times.getFadeOut()));
            if (title != null) {
                sendPacket(player, new WrapperPlayServerTitle(
                        WrapperPlayServerTitle.TitleAction.SET_TITLE,
                        title, null, null, 0, 0, 0));
            }
            if (subtitle != null) {
                sendPacket(player, new WrapperPlayServerTitle(
                        WrapperPlayServerTitle.TitleAction.SET_SUBTITLE,
                        null, subtitle, null, 0, 0, 0));
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
                WrapperPlayServerPlayerInfoUpdate.PlayerInfo info =
                        new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                                profile, true, 0, gameMode, displayName, null);
                return new WrapperPlayServerPlayerInfoUpdate(
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME,
                        Collections.singletonList(info));
            };
        }
        return (profile, gameMode, displayName) -> {
            WrapperPlayServerPlayerInfo.PlayerData data =
                    new WrapperPlayServerPlayerInfo.PlayerData(
                            displayName, profile, gameMode, 0);
            return new WrapperPlayServerPlayerInfo(
                    WrapperPlayServerPlayerInfo.Action.UPDATE_DISPLAY_NAME,
                    Collections.singletonList(data));
        };
    }

    // ── packet builders ──────────────────────────────────────────────────────

    /** Creates and sends a chat packet using the version-resolved factory. */
    private void sendChatPacket(Player player, Component component, boolean overlay) {
        sendPacket(player, chatPacketFactory.create(component, overlay));
    }

    /**
     * Sends the full title sequence: timing packet first, then title and subtitle.
     * Converts raw text strings to Adventure components before delegation.
     */
    private void sendTitleSequence(Player player, String title, String subtitle, BBAITitleTimes times) {
        titleSender.send(player,
                title == null ? null : toComponent(title),
                subtitle == null ? null : toComponent(subtitle),
                times);
    }

    /**
     * Builds a player list name update packet for the target player.
     * Resolves the target's PacketEvents {@link User} via their network channel.
     *
     * @return the constructed packet, or {@code null} if the player's channel is unavailable
     */
    private PacketWrapper<?> createPlayerListNamePacket(Player target, Component displayName) {
        Object channel = PacketEvents.getAPI().getPlayerManager().getChannel(target.getUniqueId());
        if (channel == null) return null;
        User targetUser = PacketEvents.getAPI().getPlayerManager().getUser(channel);
        if (targetUser == null) return null;
        return playerListPacketFactory.create(
                targetUser.getProfile(),
                toPacketEventsGameMode(target.getGameMode()),
                displayName);
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
                if (click != null) part = part.clickEvent(click);
            }
            if (segment.getHoverText() != null) {
                part = part.hoverEvent(HoverEvent.showText(toComponent(segment.getHoverText())));
            }
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
        if (gameMode == null) return GameMode.SURVIVAL;
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

    // ── send helper ──────────────────────────────────────────────────────────

    /**
     * Sends a packet to a player via their PacketEvents network channel.
     * Silently returns if the player or packet is null, or if the channel is unavailable.
     * Exceptions are caught and logged rather than propagated.
     */
    private void sendPacket(Player player, PacketWrapper<?> packet) {
        if (player == null || packet == null) return;
        try {
            Object channel = PacketEvents.getAPI().getPlayerManager().getChannel(player.getUniqueId());
            if (channel == null) return;
            User user = PacketEvents.getAPI().getPlayerManager().getUser(channel);
            if (user == null) return;
            user.sendPacket(packet);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send " + packet.getClass().getSimpleName()
                    + " to " + player.getName() + ": " + e.getMessage());
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
        void send(Player player, Component title, Component subtitle, BBAITitleTimes times);
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
