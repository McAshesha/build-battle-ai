package ru.ashesha.buildBattleAI.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.listeners.base.PluginListener;

/**
 * Listens for player join events and logs them for debugging purposes.
 * <p>
 * This listener will be extended to handle lobby management, arena assignment,
 * and player state restoration once the game session logic is implemented.
 */
public class PlayerJoinListener extends PluginListener {

    public PlayerJoinListener(BuildBattleAI plugin) {
        super(plugin);
    }

    /**
     * Logs the player name and UUID when they join the server.
     *
     * @param event the player join event
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getLogger().info("Player joined: " + player.getName() + " (UUID: " + player.getUniqueId() + ")");
    }
}
