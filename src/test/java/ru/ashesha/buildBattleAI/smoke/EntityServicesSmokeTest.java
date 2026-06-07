package ru.ashesha.buildBattleAI.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.util.EntityUtils;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test — covers risk THIN-ENT from the test-coverage spec.
 * <p>
 * Invariant: {@link EntityUtils#nextEntityId()} returns strictly
 * monotonically increasing positive integers and never collides, even
 * when called from NPC / Hologram / Picture services that all share
 * the same allocator. Wraparound to a negative value (after 2^31-1
 * allocations) would silently break packet entity routing.
 * <p>
 * Why smoke (not unit): this guards the shared allocator's
 * cross-service contract. A unit test of any single service can pass
 * even after the allocator has been replaced per-service.
 */
@Tag("smoke")
class EntityServicesSmokeTest {

    /** Number of IDs to allocate. Chosen so the test stays under 100 ms
     *  on a modest runner while still being big enough to catch a unit
     *  test that uses a tiny counter range. */
    private static final int ALLOCATIONS = 10_000;

    @Test
    @DisplayName("EntityUtils.nextEntityId() yields 10k strictly-increasing positive ids without duplicates")
    void allocatorIsMonotonicAndUnique() {
        int previous = EntityUtils.nextEntityId();
        assertTrue(previous > 0,
                "first allocation must be positive (was " + previous + ")");

        Set<Integer> seen = new HashSet<>(ALLOCATIONS * 2);
        seen.add(previous);

        for (int i = 1; i < ALLOCATIONS; i++) {
            int next = EntityUtils.nextEntityId();
            assertTrue(next > previous,
                    "allocator must be strictly increasing: previous=" + previous
                            + " next=" + next + " (iteration " + i + ")");
            assertTrue(next > 0,
                    "allocator must stay positive — wraparound or seeded-from-zero "
                            + "would break packet routing; got " + next);
            assertTrue(seen.add(next),
                    "allocator must not yield duplicates within a single JVM; "
                            + "duplicate " + next + " at iteration " + i);
            previous = next;
        }
    }
}
