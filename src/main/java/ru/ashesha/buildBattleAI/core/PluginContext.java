package ru.ashesha.buildBattleAI.core;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.ArenaManager;
import ru.ashesha.buildBattleAI.arena.api.BBAIArenaManager;
import ru.ashesha.buildBattleAI.core.api.BBAIMLService;
import ru.ashesha.buildBattleAI.core.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.game.GameManager;
import ru.ashesha.buildBattleAI.commands.TestMLCommand;
import ru.ashesha.buildBattleAI.commands.TestNPCCommand;
import ru.ashesha.buildBattleAI.core.api.BBAINPCService;
import ru.ashesha.buildBattleAI.game.api.BBAIGameManager;

/**
 * Centralized startup and shutdown coordinator for the plugin.
 * <p>
 * Owns all manager instances and orchestrates their lifecycle in the correct order:
 * <ol>
 *     <li>Arena manager — loads arena definitions</li>
 *     <li>Game manager — prepares game session handling</li>
 *     <li>Message service — resolves version-dependent packet factories</li>
 *     <li>NPC service — resolves version-dependent NPC packet factories</li>
 *     <li>ML service — REST proxy to the ML classification microservice</li>
 *     <li>Commands and listeners — registered last, after all services are ready</li>
 * </ol>
 * Shutdown occurs in reverse dependency order (game manager before arena manager).
 */
@RequiredArgsConstructor
public class PluginContext {

    @NonNull
    private final BuildBattleAI plugin;

    @Getter
    private BBAIArenaManager arenaManager;
    @Getter
    private BBAIGameManager gameManager;
    @Getter
    private BBAIMessageService messageService;
    @Getter
    private BBAINPCService npcService;
    @Getter
    private BBAIMLService mlService;
    @Getter
    private CommandService commandService;
    @Getter
    private ListenerService listenerService;
    @Getter
    private RenderService renderService;

    /**
     * Initializes all plugin subsystems, registers commands and event listeners.
     * Called once from {@link BuildBattleAI#onEnable()}.
     */
    public void enable() {
        // Initialize managers in dependency order
        arenaManager = new ArenaManager(plugin);
        gameManager = new GameManager(plugin);

        messageService = new MessageService(plugin);
        npcService = new NPCService(plugin);
        mlService = new MLService(plugin);
        renderService = new RenderService(plugin);

        // Register commands and listeners
        commandService = new CommandService(plugin);
        commandService.register(new TestNPCCommand(plugin));
        commandService.register(new TestMLCommand(plugin));

        listenerService = new ListenerService(plugin);
    }

    /**
     * Shuts down all plugin subsystems in reverse dependency order.
     * Called from {@link BuildBattleAI#onDisable()}.
     */
    public void disable() {
        listenerService.shutdown();
        commandService.shutdown();
        renderService.shutdown();
        mlService.shutdown();
        npcService.shutdown();
        gameManager.shutdown();
        arenaManager.shutdown();
    }

    /**
     * Resolves the PacketEvents {@link UserProfile} for the given player
     * by looking up their network channel.
     *
     * @param player the player to resolve
     * @return the player's user profile
     */
    public UserProfile getUserProfile(@NonNull Player player) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        return user.getProfile();
    }

    /**
     * Sends a packet to a player via their PacketEvents network channel.
     * Silently returns if the channel is unavailable (e.g. player disconnecting).
     * Exceptions are caught and logged rather than propagated.
     *
     * @param player the target player
     * @param packet the packet to send
     */
    public void sendPacket(@NonNull Player player, @NonNull PacketWrapper<?> packet) {
        try {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            user.sendPacket(packet);
        } catch (Throwable e) {
            plugin.getLogger().warning("Failed to send " + packet.getClass().getSimpleName()
                    + " to " + player.getName() + ": " + e.getMessage());
        }
    }
}
