package ru.ashesha.buildBattleAI.core;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.core.message.*;

/**
 * PacketEvents-based implementation of {@link BBAIMessageService}.
 * <p>
 * Delegates all messaging responsibilities to specialized micro-services located
 * in the {@code core.message} package. Each micro-service handles a specific
 * category of packet-based messages:
 * <ul>
 *     <li>{@link ChatMicroService} — chat messages (plain-text and rich)</li>
 *     <li>{@link BarMicroService} — action bar messages</li>
 *     <li>{@link TitleMicroService} — title and subtitle overlays</li>
 *     <li>{@link TabMicroService} — player list header and footer</li>
 *     <li>{@link NameMicroService} — player list display name updates</li>
 * </ul>
 * <p>
 * Lombok's {@code @Delegate} generates forwarding methods so the public API
 * of {@link BBAIMessageService} remains unchanged for callers.
 * <p>
 * Implements {@link PluginService} independently of {@link BBAIMessageService}
 * so lifecycle control stays internal to the plugin and never leaks to API
 * consumers. Micro-services are instantiated inside {@link #enable()} because
 * their version-dependent packet factories need {@link PluginContext#getServerVersion()},
 * which each micro-service resolves for itself via the plugin context — safe
 * only once the plugin has published its context, i.e. after this service
 * has been constructed.
 */
@RequiredArgsConstructor
class MessageService implements BBAIMessageService, PluginService {

    /** The plugin instance, forwarded to each micro-service during {@link #enable()}. */
    @NonNull
    private final BuildBattleAI plugin;

    /**
     * Micro-service for sending chat messages. Instantiated in {@link #enable()}.
     */
    @Delegate
    private ChatMicroService chatMicroService;

    /**
     * Micro-service for sending action bar messages. Instantiated in {@link #enable()}.
     */
    @Delegate
    private BarMicroService barMicroService;

    /**
     * Micro-service for sending title/subtitle overlays. Instantiated in {@link #enable()}.
     */
    @Delegate
    private TitleMicroService titleMicroService;

    /**
     * Micro-service for sending player list header/footer. Instantiated in {@link #enable()}.
     */
    @Delegate
    private TabMicroService tabMicroService;

    /**
     * Micro-service for updating player list display names. Instantiated in {@link #enable()}.
     */
    @Delegate
    private NameMicroService nameMicroService;

    /**
     * Builds every micro-service. Each one resolves its own version-dependent
     * packet factory by calling {@link PluginContext#getServerVersion()} in its
     * constructor — safe at this point because the plugin has already
     * published its context.
     */
    @Override
    public void enable() {
        this.chatMicroService = new ChatMicroService(plugin);
        this.barMicroService = new BarMicroService(plugin);
        this.titleMicroService = new TitleMicroService(plugin);
        this.tabMicroService = new TabMicroService(plugin);
        this.nameMicroService = new NameMicroService(plugin);
    }

    /**
     * No-op — the message service holds no runtime resources (no threads,
     * no scheduled tasks, no open connections). Kept for {@link PluginService}
     * conformance so the service slots into the standard lifecycle pipeline.
     */
    @Override
    public void shutdown() {
        // Intentionally empty — no resources to release.
    }
}
