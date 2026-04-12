package ru.ashesha.buildBattleAI.core.message;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import net.kyori.adventure.text.Component;
import lombok.NonNull;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.util.MessageUtils;

import java.util.Collection;
import java.util.Collections;

/**
 * Sub-service responsible for updating player display names in the player list (tab) via packets.
 * <p>
 * The underlying packet format differs between server versions:
 * 1.19.3+ uses {@code PlayerInfoUpdate} with granular action flags,
 * while older versions use the legacy {@code PlayerInfo} packet.
 * The correct format is resolved once at construction.
 */
public class NameService {

    private final BuildBattleAI plugin;

    /** Version-resolved factory for creating player list (tab) update packets. */
    private final PlayerListPacketFactory playerListPacketFactory;

    /**
     * Creates the name service and resolves the version-appropriate packet factory.
     *
     * @param plugin  the plugin instance
     * @param version the current server version
     */
    public NameService(@NonNull BuildBattleAI plugin, @NonNull ServerVersion version) {
        this.plugin = plugin;
        this.playerListPacketFactory = resolvePlayerListFactory(version);
    }

    // ── public API ──────────────────────────────────────────────────────────

    /**
     * Updates a player's display name in the player list (tab) for a single viewer.
     *
     * @param target         the player whose list name to change
     * @param playerListName the new display name (supports {@code &} color codes),
     *                       or {@code null} to reset to the default name
     * @param viewer         the player who will see the updated name
     */
    public void sendPlayerListName(@NonNull Player target, String playerListName, @NonNull Player viewer) {
        Component displayName = MessageUtils.toComponent(playerListName);
        plugin.getContext().sendPacket(viewer, createPlayerListNamePacket(target, displayName));
    }

    /**
     * Updates a player's display name in the player list (tab) for the specified viewers.
     *
     * @param target         the player whose list name to change
     * @param playerListName the new display name (supports {@code &} color codes),
     *                       or {@code null} to reset to the default name
     * @param viewers        the players who will see the updated name
     */
    public void sendPlayerListName(@NonNull Player target, String playerListName, @NonNull Collection<? extends Player> viewers) {
        Component displayName = MessageUtils.toComponent(playerListName);
        PacketWrapper<?> packet = createPlayerListNamePacket(target, displayName);
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, packet);
    }

    // ── version-resolved factory ────────────────────────────────────────────

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

    // ── packet builder ──────────────────────────────────────────────────────

    /**
     * Builds a player list name update packet for the target player.
     * Resolves the target's PacketEvents {@link UserProfile} via
     * {@link ru.ashesha.buildBattleAI.core.PluginContext#getUserProfile(Player)}.
     *
     * @return the constructed packet
     */
    private PacketWrapper<?> createPlayerListNamePacket(Player target, Component displayName) {
        return playerListPacketFactory.create(
                plugin.getContext().getUserProfile(target),
                toPacketEventsGameMode(target.getGameMode()),
                displayName
        );
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

    // ── version-dispatched functional interface ─────────────────────────────

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
