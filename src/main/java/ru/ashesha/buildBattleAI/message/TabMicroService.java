package ru.ashesha.buildBattleAI.message;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerListHeaderAndFooter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.util.MessageUtils;

import java.util.Collection;

/**
 * Sub-service responsible for sending player list (tab) header and footer via packets.
 */
@RequiredArgsConstructor
public class TabMicroService {

    @NonNull
    private final BuildBattleAI plugin;

    /**
     * Sets the player list (tab) header and footer for a single player.
     *
     * @param recipient the target player
     * @param header    the header text (supports {@code &} color codes and {@code \n})
     * @param footer    the footer text (supports {@code &} color codes and {@code \n})
     */
    public void sendTab(@NonNull Player recipient, String header, String footer) {
        plugin.getContext().sendPacket(recipient, new WrapperPlayServerPlayerListHeaderAndFooter(
                MessageUtils.toColorComponent(header),
                MessageUtils.toColorComponent(footer)
        ));
    }

    /**
     * Sets the player list (tab) header and footer for multiple players.
     *
     * @param recipients the target players
     * @param header     the header text (supports {@code &} color codes and {@code \n})
     * @param footer     the footer text (supports {@code &} color codes and {@code \n})
     */
    public void sendTab(@NonNull Collection<? extends Player> recipients, String header, String footer) {
        WrapperPlayServerPlayerListHeaderAndFooter packet = new WrapperPlayServerPlayerListHeaderAndFooter(
                MessageUtils.toColorComponent(header),
                MessageUtils.toColorComponent(footer)
        );
        for (Player recipient : recipients)
            plugin.getContext().sendPacket(recipient, packet);
    }
}
