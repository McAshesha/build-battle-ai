package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Integration test — covers risk EVAL-001 from the test-coverage spec.
 * <p>
 * Invariant: when {@link RenderQueue#offer(EvalJob)} fails because the
 * underlying bounded queue is at capacity, the dedup {@code pending} map
 * entry that was provisionally inserted MUST be rolled back. Without
 * this rollback a subsequent offer for the same player ID would be
 * silently dropped as "already pending", masking the failure forever.
 * <p>
 * Why integration (not unit): the rollback path involves a coordinated
 * two-step ({@code pending.put} -> {@code queue.offer}) with a conditional
 * remove using {@code ConcurrentHashMap.remove(k, v)}. The bug surface is
 * in how the two collaborators interact, not in either one alone.
 * <p>
 * Threading: this test exercises the contract from a single thread.
 * EVAL-012 (multi-consumer correctness under concurrent take/offer)
 * lives in the stress tier and gets its own test in Phase 5.
 */
@Tag("integration")
class RenderQueueDedupConcurrencyIT {

    private static EvalJob jobFor(UUID playerId) {
        return EvalJob.builder()
                .arenaName("arena-1")
                .playerId(playerId)
                .playerName("p-" + playerId)
                .plotIndex(0)
                .themeIndex(0)
                .expectedTheme("theme")
                .mirror(mock(MutablePlotScene.class))
                .cameraX(0).cameraY(0).cameraZ(0)
                .cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(System.nanoTime())
                .build();
    }

    @Test
    @DisplayName("EVAL-001: offer() failure rolls back pending so the same player can re-offer")
    void offerFailureRollsBackDedup() {
        RenderQueue queue = new RenderQueue(1);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertTrue(queue.offer(jobFor(a)), "first offer should succeed (queue empty)");
        assertEquals(1, queue.size());

        EvalJob firstB = jobFor(b);
        assertFalse(queue.offer(firstB),
                "second offer for player B should fail (queue is full)");
        assertEquals(1, queue.size(), "queue size must remain at capacity");

        try {
            EvalJob drained = queue.take();
            assertEquals(a, drained.playerId(), "take must return player A's job first");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("take interrupted unexpectedly");
        }
        assertEquals(0, queue.size());

        EvalJob secondB = jobFor(b);
        assertTrue(queue.offer(secondB),
                "after rollback, player B must be re-offerable");
        assertEquals(1, queue.size());

        try {
            EvalJob drained = queue.take();
            assertSame(secondB, drained,
                    "the queued job must be the SECOND B-job (the new one), "
                            + "not a phantom stale entry from the first failed offer");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("take interrupted unexpectedly");
        }
    }

    @Test
    @DisplayName("EVAL-001: newer offer for same player marks the old stale and dedup is reused")
    void newOfferMarksOldStaleAndDoesNotDuplicate() {
        RenderQueue queue = new RenderQueue(4);
        UUID id = UUID.randomUUID();
        EvalJob first = jobFor(id);
        EvalJob second = jobFor(id);

        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second),
                "newer job for same player must be acceptable (dedup replaces, not rejects)");

        assertTrue(first.isStale(),
                "first job must be marked stale once a newer job lands for the same player");
        assertFalse(second.isStale(),
                "newer job must remain non-stale");

        try {
            EvalJob drained = queue.take();
            assertSame(second, drained,
                    "take() must skip the stale first job and return the new one");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("take interrupted unexpectedly");
        }
    }
}
