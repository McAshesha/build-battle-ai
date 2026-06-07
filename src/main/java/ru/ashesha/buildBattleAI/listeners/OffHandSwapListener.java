package ru.ashesha.buildBattleAI.listeners;

import lombok.NonNull;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.game.api.BBAIGameManager;
import ru.ashesha.buildBattleAI.game.feedback.SkipThemeItem;

/**
 * Listener for the 1.9+ off-hand swap event ({@link PlayerSwapHandItemsEvent}).
 * <p>
 * Kept in a separate class from {@link GameListener} because the event class
 * does not exist on 1.8: when Bukkit's plugin manager scans a listener class
 * for {@code @EventHandler} methods, it resolves the parameter types eagerly
 * and a missing class aborts registration of the <i>entire</i> listener — a
 * single 1.9+ handler in {@link GameListener} would knock out all of its
 * other handlers on a 1.8 server.
 * <p>
 * Registered from {@link ru.ashesha.buildBattleAI.core.PluginContext} only
 * after a {@code ServerVersion.V_1_9} gate, so on 1.8 this class is loaded
 * but never registered, and Bukkit never inspects its handler signatures.
 */
public class OffHandSwapListener extends ListenerService.PluginListener {

    /**
     * Creates the off-hand swap listener.
     *
     * @param plugin the plugin instance
     */
    public OffHandSwapListener(@NonNull BuildBattleAI plugin) {
        super(plugin);
    }

    /**
     * Prevents players from moving the skip-theme feather to the off-hand
     * via the F key — keeps the hotbar layout stable for the duration of
     * the game.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        BBAIGameManager gm = plugin.getContext().getGameManager();
        if (!gm.isInGame(player.getUniqueId()))
            return;
        if (SkipThemeItem.isSkipItem(event.getMainHandItem())
                || SkipThemeItem.isSkipItem(event.getOffHandItem()))
            event.setCancelled(true);
    }
}
