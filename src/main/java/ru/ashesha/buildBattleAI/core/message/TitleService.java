package ru.ashesha.buildBattleAI.core.message;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleSubtitle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleText;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleTimes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTitle;
import net.kyori.adventure.text.Component;
import lombok.NonNull;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.util.MessageUtils;

import java.util.Collection;

/**
 * Sub-service responsible for sending title and subtitle overlays via packets.
 * <p>
 * The underlying packet format differs between server versions:
 * 1.17+ uses separate packets for times, title text, and subtitle text,
 * while older versions use a combined title packet with action enum.
 * The correct format is resolved once at construction.
 */
public class TitleService {

    private final BuildBattleAI plugin;

    /** Version-resolved sender for title/subtitle packets. */
    private final TitleSender titleSender;

    /**
     * Creates the title service and resolves the version-appropriate sender.
     *
     * @param plugin  the plugin instance
     * @param version the current server version
     */
    public TitleService(@NonNull BuildBattleAI plugin, @NonNull ServerVersion version) {
        this.plugin = plugin;
        this.titleSender = resolveTitleSender(version);
    }

    // ── public API ──────────────────────────────────────────────────────────

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
    public void sendTitle(@NonNull Player recipient, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        sendTitleSequence(recipient, title, subtitle, fadeIn, stay, fadeOut);
    }

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
    public void sendTitle(@NonNull Collection<? extends Player> recipients, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        for (Player recipient : recipients) {
            sendTitleSequence(recipient, title, subtitle, fadeIn, stay, fadeOut);
        }
    }

    // ── version-resolved factory ────────────────────────────────────────────

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

    // ── packet builder ──────────────────────────────────────────────────────

    /**
     * Sends the full title sequence: timing packet first, then title and subtitle.
     * Converts raw text strings to Adventure components before delegation.
     */
    private void sendTitleSequence(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        titleSender.send(
                player,
                MessageUtils.toComponent(title),
                MessageUtils.toComponent(subtitle),
                fadeIn, stay, fadeOut
        );
    }

    // ── version-dispatched functional interface ─────────────────────────────

    /**
     * Abstraction for sending title packets, resolved once at startup to match
     * the server's packet format (split packets in 1.17+ vs combined in older versions).
     */
    @FunctionalInterface
    private interface TitleSender {
        void send(Player player, Component title, Component subtitle, int fadeIn, int stay, int fadeOut);
    }
}
