package ru.ashesha.buildBattleAI.listeners;

import lombok.NonNull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.ashesha.buildBattleAI.BuildBattleAI;

/**
 * Handles cleanup when a player disconnects during an active arena
 * setup session.
 * <p>
 * When the creating admin leaves the server mid-wizard, the temporary
 * void world is deleted and the session is discarded without sending
 * any chat feedback (the player is already gone).
 */
public class ArenaSetupListener extends ListenerService.PluginListener {

    /**
     * Creates the arena setup listener.
     *
     * @param plugin the plugin instance
     */
    public ArenaSetupListener(@NonNull BuildBattleAI plugin) {
        super(plugin);
    }

    /**
     * Cancels any active setup session when the player disconnects.
     * The temporary void world is cleaned up by
     * {@link ru.ashesha.buildBattleAI.arena.api.BBAIArenaManager#cancelSetupSession}.
     *
     * @param event the quit event
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (plugin.getContext().getArenaManager().hasSetupSession(event.getPlayer().getUniqueId()))
            plugin.getContext().getArenaManager().cancelSetupSession(event.getPlayer().getUniqueId());
    }
}
