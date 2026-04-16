package ru.ashesha.buildBattleAI.core.message;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.chat.ChatTypes;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessageLegacy;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import lombok.NonNull;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.util.MessageUtils;

import java.util.Collection;

/**
 * Sub-service responsible for sending action bar messages via packets.
 * <p>
 * Action bar messages are displayed above the player's hotbar and fade
 * out automatically after a short duration.
 * <p>
 * Multi-version: on 1.8–1.10 servers, where no dedicated action bar packet exists,
 * messages are sent via the chat packet with {@code GAME_INFO} position. On 1.11+,
 * the dedicated {@link WrapperPlayServerActionBar} wrapper is used.
 */
public class BarMicroService {

    private final BuildBattleAI plugin;

    /**
     * Version-resolved factory for creating action bar packets.
     */
    private final BarPacketFactory barPacketFactory;

    /**
     * Creates the bar micro-service and resolves the version-appropriate packet factory.
     * <p>
     * The server version is obtained from
     * {@link ru.ashesha.buildBattleAI.core.PluginContext#getServerVersion()},
     * so this constructor must only be invoked after the plugin context has
     * been published — i.e. from inside a {@code PluginService.enable()} call.
     *
     * @param plugin the plugin instance
     */
    public BarMicroService(@NonNull BuildBattleAI plugin) {
        this.plugin = plugin;
        this.barPacketFactory = resolveBarFactory(plugin.getContext().getServerVersion());
    }

    /**
     * Sends an action bar message to a single player.
     *
     * @param recipient the target player
     * @param message   the message text (supports {@code &} color codes)
     */
    public void sendActionBar(@NonNull Player recipient, @NonNull String message) {
        plugin.getContext().sendPacket(recipient, barPacketFactory.create(MessageUtils.toComponent(message)));
    }

    /**
     * Sends an action bar message to multiple players.
     *
     * @param recipients the target players
     * @param message    the message text (supports {@code &} color codes)
     */
    public void sendActionBar(@NonNull Collection<? extends Player> recipients, @NonNull String message) {
        PacketWrapper<?> packet = barPacketFactory.create(MessageUtils.toComponent(message));
        for (Player recipient : recipients)
            plugin.getContext().sendPacket(recipient, packet);
    }

    // ── version-resolved factory ────────────────────────────────────────────

    /**
     * Resolves the correct action bar packet factory for the running server version.
     * <ul>
     *     <li>1.11+ — uses {@link WrapperPlayServerActionBar} (dedicated packet)</li>
     *     <li>1.8–1.10 — uses {@link WrapperPlayServerChatMessage} with
     *         {@code GAME_INFO} position (action bar via chat packet)</li>
     * </ul>
     */
    @SuppressWarnings("deprecation")
    private BarPacketFactory resolveBarFactory(ServerVersion version) {
        if (version.isNewerThanOrEquals(ServerVersion.V_1_11))
            return WrapperPlayServerActionBar::new;
        // 1.8–1.10: no dedicated action bar packet; use chat with GAME_INFO position
        return component -> new WrapperPlayServerChatMessage(
                new ChatMessageLegacy(component, ChatTypes.GAME_INFO));
    }

    // ── version-dispatched functional interface ─────────────────────────────

    /**
     * Factory for creating version-appropriate action bar packets.
     */
    @FunctionalInterface
    private interface BarPacketFactory {
        PacketWrapper<?> create(Component component);
    }
}
