package ru.ashesha.buildBattleAI.core;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import lombok.Getter;
import lombok.NonNull;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.ArenaManager;
import ru.ashesha.buildBattleAI.arena.api.BBAIArenaManager;
import ru.ashesha.buildBattleAI.commands.ArenaCommand;
import ru.ashesha.buildBattleAI.commands.MLTestCommand;
import ru.ashesha.buildBattleAI.config.ConfigService;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.data.DataService;
import ru.ashesha.buildBattleAI.data.api.BBAIDataService;
import ru.ashesha.buildBattleAI.commands.CommandService;
import ru.ashesha.buildBattleAI.entity.hologram.HologramService;
import ru.ashesha.buildBattleAI.entity.hologram.api.BBAIHologramService;
import ru.ashesha.buildBattleAI.entity.npc.NPCService;
import ru.ashesha.buildBattleAI.entity.npc.api.BBAINPCService;
import ru.ashesha.buildBattleAI.entity.picture.PictureService;
import ru.ashesha.buildBattleAI.entity.picture.api.BBAIPictureService;
import ru.ashesha.buildBattleAI.game.GameManager;
import ru.ashesha.buildBattleAI.game.api.BBAIGameManager;
import ru.ashesha.buildBattleAI.listeners.ArenaSetupListener;
import ru.ashesha.buildBattleAI.listeners.GameListener;
import ru.ashesha.buildBattleAI.listeners.ListenerService;
import ru.ashesha.buildBattleAI.listeners.MLTestListener;
import ru.ashesha.buildBattleAI.message.MessageService;
import ru.ashesha.buildBattleAI.message.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.ml.MLService;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;
import ru.ashesha.buildBattleAI.render.RenderService;
import ru.ashesha.buildBattleAI.world.WorldService;
import ru.ashesha.buildBattleAI.world.api.BBAIWorldService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Centralized startup and shutdown coordinator for the plugin.
 * <p>
 * The context owns every plugin service as a {@code final} field. Services
 * are constructed once here; after that, a uniform {@link PluginService}
 * lifecycle is applied to all of them:
 * <ul>
 *     <li>{@link #enable()} calls {@link PluginService#enable()} on every
 *         service in construction order, then performs the plugin's
 *         business-layer bootstrap: registering commands through
 *         {@link CommandService}, listeners through {@link ListenerService},
 *         and any other start-up actions.</li>
 *     <li>{@link #shutdown()} tears everything down in the reverse order so
 *         dependent services see their dependencies still alive during
 *         their own cleanup.</li>
 *     <li>{@link #reload()} is a straight {@code shutdown} + {@code enable}
 *         and fully reinstates the plugin, leaving the server and every
 *         other plugin untouched.</li>
 * </ul>
 * The invariant is: after {@code shutdown} the plugin holds no running
 * resources and has not disturbed the server or other plugins; after a
 * subsequent {@code enable} the plugin is running exactly as it would after
 * a fresh server start.
 */
public class PluginContext {

    @NonNull
    private final BuildBattleAI plugin;

    @Getter
    private final BBAIConfigService configService;
    @Getter
    private final BBAIDataService dataService;
    @Getter
    private final BBAIWorldService worldService;
    @Getter
    private final BBAIArenaManager arenaManager;
    @Getter
    private final BBAIGameManager gameManager;
    @Getter
    private final BBAIMessageService messageService;
    @Getter
    private final BBAINPCService npcService;
    @Getter
    private final BBAIHologramService hologramService;
    @Getter
    private final BBAIPictureService pictureService;
    @Getter
    private final BBAIMLService mlService;
    @Getter
    private final CommandService commandService;
    @Getter
    private final ListenerService listenerService;
    @Getter
    private final RenderService renderService;

    /**
     * Ordered list of every service, used to drive the uniform lifecycle.
     * Construction order = enable order; reverse = shutdown order. Storing
     * this list up-front avoids hand-maintaining two mirrored sequences
     * and guarantees {@code enable}/{@code shutdown} are exact inverses.
     */
    private final List<PluginService> services;

    /**
     * Constructs every plugin service. No runtime resources are allocated
     * here — services are only <i>created</i>; each one's {@link PluginService#enable()}
     * is what actually brings it online and is invoked later by {@link #enable()}.
     * <p>
     * Safe to call from {@link BuildBattleAI#onLoad()} (after PacketEvents'
     * {@code load()}), which is the project convention — it lets services
     * resolve the server version eagerly in their constructors even though
     * no worker threads or connections are opened yet.
     *
     * @param plugin the plugin instance
     */
    public PluginContext(@NonNull BuildBattleAI plugin) {
        this.plugin = plugin;

        // Build each service — dependency order matches the enable sequence.
        // Config first (other services may read settings during their enable),
        // then managers (world / game state holders), then protocol-bound
        // services, then command / listener / render plumbing.
        ConfigService configServiceImpl = new ConfigService(plugin);
        DataService dataServiceImpl = new DataService(plugin);
        WorldService worldServiceImpl = new WorldService(plugin);
        ArenaManager arenaManagerImpl = new ArenaManager(plugin);
        GameManager gameManagerImpl = new GameManager(plugin);
        MessageService messageServiceImpl = new MessageService(plugin);
        NPCService npcServiceImpl = new NPCService(plugin);
        HologramService hologramServiceImpl = new HologramService(plugin);
        PictureService pictureServiceImpl = new PictureService(plugin);
        MLService mlServiceImpl = new MLService(plugin);
        RenderService renderServiceImpl = new RenderService(plugin);
        CommandService commandServiceImpl = new CommandService(plugin);
        ListenerService listenerServiceImpl = new ListenerService(plugin);

        this.configService = configServiceImpl;
        this.dataService = dataServiceImpl;
        this.worldService = worldServiceImpl;
        this.arenaManager = arenaManagerImpl;
        this.gameManager = gameManagerImpl;
        this.messageService = messageServiceImpl;
        this.npcService = npcServiceImpl;
        this.hologramService = hologramServiceImpl;
        this.pictureService = pictureServiceImpl;
        this.mlService = mlServiceImpl;
        this.renderService = renderServiceImpl;
        this.commandService = commandServiceImpl;
        this.listenerService = listenerServiceImpl;

        // Keep the list unmodifiable — the lifecycle path must not be mutated
        // at runtime; any new service is added by editing the constructor.
        this.services = Collections.unmodifiableList(Arrays.asList(
                configServiceImpl,
                dataServiceImpl,
                worldServiceImpl,
                arenaManagerImpl,
                gameManagerImpl,
                messageServiceImpl,
                npcServiceImpl,
                hologramServiceImpl,
                pictureServiceImpl,
                mlServiceImpl,
                renderServiceImpl,
                commandServiceImpl,
                listenerServiceImpl
        ));
    }

    /**
     * Brings the plugin online. Runs {@link PluginService#enable()} on every
     * service in construction order, then performs the business-layer
     * bootstrap — registering commands and (in the future) listeners,
     * wiring up game flow, loading arena configuration, etc.
     * <p>
     * Called from {@link BuildBattleAI#onEnable()} on startup and re-used
     * by {@link #reload()} during hot reload.
     */
    public void enable() {
        long start = System.currentTimeMillis();

        // Phase 1: each service acquires its own runtime resources.
        for (PluginService service : services)
            service.enable();

        // Phase 2: plugin business logic — command and listener registrations
        // belong here because they are owned by the plugin as a whole, not by
        // the command / listener services (those services only provide the
        // registration mechanism and the bulk-unregistration guarantees).
        commandService.register(new ArenaCommand(plugin));
        commandService.register(new MLTestCommand(plugin));
        listenerService.register(new ArenaSetupListener(plugin));
        listenerService.register(new GameListener(plugin));
        listenerService.register(new MLTestListener(plugin));

        long elapsed = System.currentTimeMillis() - start;
        plugin.getPluginLogger().debug("PluginContext enabled %d service(s) in %d ms.",
                services.size(), elapsed);
    }

    /**
     * Takes the plugin offline. Runs {@link PluginService#shutdown()} on every
     * service in reverse construction order so that late-initialized services
     * (commands, listeners, renderer) are gone before their dependencies
     * (managers, message/NPC services) are released.
     * <p>
     * After this call returns, the plugin has no background threads, no
     * registered commands or listeners, and has not touched the state of any
     * other plugin or the server itself.
     * <p>
     * Called from {@link BuildBattleAI#onDisable()} and re-used by
     * {@link #reload()} during hot reload.
     */
    public void shutdown() {
        plugin.getPluginLogger().debug("PluginContext shutting down %d service(s) in reverse order.",
                services.size());

        for (int i = services.size() - 1; i >= 0; i--)
            services.get(i).shutdown();
    }

    /**
     * Hot-reloads the plugin by running {@link #shutdown()} followed by
     * {@link #enable()}. After this call the plugin is in the same state as
     * it would be after a fresh server start — no other plugin or server
     * subsystem is disturbed in the process.
     */
    public void reload() {
        plugin.getPluginLogger().info("Reloading plugin — shutting down and re-enabling all services.");
        shutdown();
        enable();
    }

    /**
     * Returns the Minecraft server version resolved by PacketEvents.
     * <p>
     * Safe to call from {@link BuildBattleAI#onLoad()} onwards — the version
     * is resolved lazily from {@code Bukkit.getBukkitVersion()} on first
     * access and then cached inside the PacketEvents {@code ServerManager}.
     *
     * @return the running server's {@link ServerVersion}
     */
    public ServerVersion getServerVersion() {
        return PacketEvents.getAPI().getServerManager().getVersion();
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
            plugin.getPluginLogger().warn("Failed to send %s to %s: %s",
                    packet.getClass().getSimpleName(), player.getName(), e.getMessage());
        }
    }
}
