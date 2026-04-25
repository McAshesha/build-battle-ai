package ru.ashesha.buildBattleAI.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityUtils} — the shared entity ID allocator.
 * <p>
 * Verifies that IDs are monotonically increasing, unique, and start from
 * a high range to avoid collisions with real server entities.
 */
class EntityUtilsTest {

    @Test
    void consecutiveIdsAreUnique() {
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < 100; i++)
            assertTrue(ids.add(EntityUtils.nextEntityId()), "Duplicate ID at iteration " + i);
    }

    @Test
    void idsAreMonotonicallyIncreasing() {
        int prev = EntityUtils.nextEntityId();
        for (int i = 0; i < 50; i++) {
            int next = EntityUtils.nextEntityId();
            assertTrue(next > prev, "ID " + next + " is not greater than " + prev);
            prev = next;
        }
    }

    @Test
    void idsAreInHighRange() {
        int id = EntityUtils.nextEntityId();
        // Must be well above typical server entity IDs (which start near 0)
        assertTrue(id > 1_000_000, "Entity ID " + id + " is too low; risk of collision with server entities");
    }

}
