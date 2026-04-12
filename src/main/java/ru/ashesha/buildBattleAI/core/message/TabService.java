package ru.ashesha.buildBattleAI.core.message;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerListHeaderAndFooter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.util.MessageUtils;

/**
 * Sub-service responsible for sending player list (tab) header and footer via packets.
 */
@RequiredArgsConstructor
public class TabService {

    @NonNull private final BuildBattleAI plugin;

    /**
     * Sets the player list (tab) header and footer for a single player.
     *
     * @param recipient the target player
     * @param header    the header text (supports {@code &} color codes and {@code \n})
     * @param footer    the footer text (supports {@code &} color codes and {@code \n})
     */
    public void sendTab(@NonNull Player recipient, String header, String footer) {
        plugin.getContext().sendPacket(recipient, new WrapperPlayServerPlayerListHeaderAndFooter(
                MessageUtils.toComponent(header),
                MessageUtils.toComponent(footer)
        ));
    }
}
