package ru.ashesha.buildBattleAI.util;

import lombok.experimental.UtilityClass;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared utility for allocating synthetic entity IDs across all packet-based
 * entity services (NPCs, holograms, item-frame pictures, etc.).
 * <p>
 * A single monotonic counter ensures IDs never collide between services.
 * The counter starts from a high range ({@code Integer.MAX_VALUE / 2}) to
 * minimize risk of collision with real server-tracked entities.
 */
@UtilityClass
public class EntityUtils {

    /**
     * Shared, monotonically increasing counter for synthetic entity IDs.
     * Thread-safe via {@link AtomicInteger}.
     */
    private final AtomicInteger ENTITY_ID_COUNTER = new AtomicInteger(Integer.MAX_VALUE / 2);

    /**
     * Returns the next unique synthetic entity ID.
     * <p>
     * Safe to call from any thread. Each call returns a strictly increasing
     * value, guaranteeing no two entities (regardless of type) share an ID.
     *
     * @return a unique entity ID
     */
    public int nextEntityId() {
        return ENTITY_ID_COUNTER.getAndIncrement();
    }

}
