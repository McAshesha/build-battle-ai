package ru.ashesha.buildBattleAI.core.message;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.util.MessageUtils;

import java.util.Collection;

/**
 * Sub-service responsible for sending action bar messages via packets.
 * <p>
 * Action bar messages are displayed above the player's hotbar and fade
 * out automatically after a short duration.
 */
@RequiredArgsConstructor
public class BarService {

    @NonNull private final BuildBattleAI plugin;

    /**
     * Sends an action bar message to a single player.
     *
     * @param recipient the target player
     * @param message   the message text (supports {@code &} color codes)
     */
    public void sendActionBar(@NonNull Player recipient, @NonNull String message) {
        plugin.getContext().sendPacket(recipient, new WrapperPlayServerActionBar(MessageUtils.toComponent(message)));
    }

    /**
     * Sends an action bar message to multiple players.
     *
     * @param recipients the target players
     * @param message    the message text (supports {@code &} color codes)
     */
    public void sendActionBar(@NonNull Collection<? extends Player> recipients, @NonNull String message) {
        WrapperPlayServerActionBar packet = new WrapperPlayServerActionBar(MessageUtils.toComponent(message));
        for (Player recipient : recipients) {
            plugin.getContext().sendPacket(recipient, packet);
        }
    }
}
