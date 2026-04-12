package ru.ashesha.buildBattleAI.core;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import lombok.NonNull;
import lombok.experimental.Delegate;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.core.message.BarService;
import ru.ashesha.buildBattleAI.core.message.ChatService;
import ru.ashesha.buildBattleAI.core.message.NameService;
import ru.ashesha.buildBattleAI.core.message.TabService;
import ru.ashesha.buildBattleAI.core.message.TitleService;

/**
 * PacketEvents-based implementation of {@link BBAIMessageService}.
 * <p>
 * Delegates all messaging responsibilities to specialized sub-services located
 * in the {@code core.message} package. Each sub-service handles a specific
 * category of packet-based messages:
 * <ul>
 *     <li>{@link ChatService} — chat messages (plain-text and rich)</li>
 *     <li>{@link BarService} — action bar messages</li>
 *     <li>{@link TitleService} — title and subtitle overlays</li>
 *     <li>{@link TabService} — player list header and footer</li>
 *     <li>{@link NameService} — player list display name updates</li>
 * </ul>
 * <p>
 * Lombok's {@code @Delegate} generates forwarding methods so the public API
 * of {@link BBAIMessageService} remains unchanged for callers.
 */
class MessageService implements BBAIMessageService {

    /** Sub-service for sending chat messages. */
    @Delegate private final ChatService chatService;

    /** Sub-service for sending action bar messages. */
    @Delegate private final BarService barService;

    /** Sub-service for sending title/subtitle overlays. */
    @Delegate private final TitleService titleService;

    /** Sub-service for sending player list header/footer. */
    @Delegate private final TabService tabService;

    /** Sub-service for updating player list display names. */
    @Delegate private final NameService nameService;

    /**
     * Creates the message service and initializes all sub-services.
     * Version-dependent packet factories are resolved once based on
     * the current server version reported by PacketEvents.
     *
     * @param plugin the plugin instance
     */
    MessageService(@NonNull BuildBattleAI plugin) {
        ServerVersion version = PacketEvents.getAPI().getServerManager().getVersion();
        this.chatService = new ChatService(plugin, version);
        this.barService = new BarService(plugin, version);
        this.titleService = new TitleService(plugin, version);
        this.tabService = new TabService(plugin);
        this.nameService = new NameService(plugin, version);
    }
}
