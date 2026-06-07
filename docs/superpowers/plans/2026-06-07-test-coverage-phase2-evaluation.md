# Test Coverage Expansion — Phase 2 (Integration: evaluation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 4 integration test classes covering risks EVAL-001/002/003/005/006/011 in the `evaluation/` module — concurrency, lifecycle, callback resilience, and buffer-ownership invariants of the render/ML pipeline.

**Architecture:** Bukkit-free in-JVM tests that wire real `RenderQueue` + `MlQueue` + `EvaluationMetrics` instances against mocked collaborators (`RenderService`, `BBAIMLService`, `PluginLogger`, `MutablePlotScene`, `GameSession`). Tests live in package `ru.ashesha.buildBattleAI.evaluation` (same as production package) so they can call the package-private constructors of `RenderQueue`, `MlQueue`, `RenderWorker`, `MlCoalescerWorker`, `EvaluationCoordinator`. Class names end in `IT` to distinguish from existing unit tests. `@Tag("integration")` triggers their inclusion under the `pr-gate` profile.

**Tech stack:** Java 8, JUnit Jupiter 5.10.3, Mockito 5.12.0, Awaitility 4.2.2.

**Spec:** `docs/superpowers/specs/2026-06-07-test-coverage-expansion-design.md` §6 phase 2. Risks covered:
- **EVAL-001:** `RenderQueue.offer()` failure must roll back `pending` dedup entry
- **EVAL-002:** Every `EvalFrame.rgb` is a fresh buffer; workers never reuse
- **EVAL-003:** Unregister-during-flight: in-flight ML batch completes; missing arena lookup silently skipped but batch is counted
- **EVAL-005:** Queue-full drop does NOT update `lastEvalAtNanos` (busy-loop avoidance)
- **EVAL-006:** Throwing score-callback doesn't kill `MlCoalescerWorker`; metrics still increment
- **EVAL-011:** Dropped job is re-considered in the NEXT coordinator tick

**Reminder:** assistant commits after each task (no push). Use the suggested commit message verbatim.

---

## File map

**Created:**
- `src/test/java/ru/ashesha/buildBattleAI/evaluation/RenderQueueDedupConcurrencyIT.java` — EVAL-001
- `src/test/java/ru/ashesha/buildBattleAI/evaluation/RenderWorkerBufferOwnershipIT.java` — EVAL-002
- `src/test/java/ru/ashesha/buildBattleAI/evaluation/EvaluationCoordinatorLifecycleIT.java` — EVAL-003, EVAL-005, EVAL-011
- `src/test/java/ru/ashesha/buildBattleAI/evaluation/MlCoalescerCallbackResilienceIT.java` — EVAL-006

**Modified:** none. Production code remains untouched.

---

## Shared notes for all tasks

- **Package:** every test class is `package ru.ashesha.buildBattleAI.evaluation;` — required for package-private access to `RenderQueue`, `MlQueue`, `RenderWorker`, `MlCoalescerWorker`, `EvaluationCoordinator`, `SessionHandle`, `EvalJob.builder()`.
- **Class-level annotations:**
  ```java
  @Tag("integration")
  class XxxIT { ... }
  ```
  No `package-private` modifier — JUnit Jupiter doesn't require `public` on test classes.
- **Imports always include:** `import org.junit.jupiter.api.Tag;` `import org.junit.jupiter.api.Test;` `import static org.junit.jupiter.api.Assertions.*;` (plus per-test extras).
- **Verification command:** every task ends with `mvn -B -ntp clean test -P integration -Dtest=<ClassName>` to confirm the test runs under the integration profile and passes. Then `mvn -B -ntp clean test -P pr-gate` to confirm it's included in PR CI.
- **Maven binary on this machine:** `/opt/homebrew/bin/mvn`.
- **Always `clean`** before test runs — stale Lombok-generated classes from incremental compile cause false fluent-accessor errors.
- **Awaitility:** use `await().atMost(2, SECONDS).until(...)` instead of `Thread.sleep` whenever you assert on a background-thread side effect. Static import: `import static org.awaitility.Awaitility.await;`.

---

## Task 1: `RenderQueueDedupConcurrencyIT` (EVAL-001)

**Risk:** `RenderQueue.offer()` is the two-step `pending.put(playerId, job)` followed by `queue.offer(job)`. If the queue is full, the second step fails — the rollback must remove the entry from `pending` so future calls for the same player aren't dedup-suppressed. A race could leave `pending` holding a phantom entry.

**Invariant:** when `offer()` returns `false` because the queue is at capacity, a subsequent `offer()` for the SAME player ID (with the queue having freed a slot) MUST succeed and the new job MUST land in the queue.

**Files:**
- Create: `src/test/java/ru/ashesha/buildBattleAI/evaluation/RenderQueueDedupConcurrencyIT.java`

- [ ] **Step 1.1: Write the test**

```java
package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
 * two-step (`pending.put` → `queue.offer`) with a conditional remove
 * using {@code ConcurrentHashMap.remove(k, v)}. The bug surface is in
 * how the two collaborators interact, not in either one alone.
 * <p>
 * Threading: this test exercises the contract from a single thread.
 * EVAL-012 (multi-consumer correctness under concurrent take/offer)
 * lives in the stress tier and gets its own test in Phase 5.
 */
@Tag("integration")
class RenderQueueDedupConcurrencyIT {

    /** Constructs a deterministic {@link EvalJob} bound to the given player id. */
    private static EvalJob jobFor(UUID playerId) {
        MutablePlotScene mirror = mock(MutablePlotScene.class);
        when(mirror.readLock()).thenReturn(new ReentrantReadWriteLock().readLock());
        return EvalJob.builder()
                .arenaName("arena-1")
                .playerId(playerId)
                .playerName("p-" + playerId)
                .plotIndex(0)
                .themeIndex(0)
                .expectedTheme("theme")
                .mirror(mirror)
                .cameraX(0).cameraY(0).cameraZ(0)
                .cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(System.nanoTime())
                .build();
    }

    @Test
    @DisplayName("offer() failure rolls back pending so the same player can re-offer")
    void offerFailureRollsBackDedup() {
        // Capacity 1 so the second offer for player B will fail.
        RenderQueue queue = new RenderQueue(1);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        // First offer fills the queue.
        assertTrue(queue.offer(jobFor(a)), "first offer should succeed (queue empty)");
        assertEquals(1, queue.size());

        // Second offer for a DIFFERENT player fails because queue is full.
        // The pending entry for player B must have been rolled back.
        EvalJob firstB = jobFor(b);
        assertFalse(queue.offer(firstB),
                "second offer for player B should fail (queue is full)");
        assertEquals(1, queue.size(), "queue size must remain at capacity");

        // Drain player A so a slot frees up.
        try {
            EvalJob drained = queue.take();
            assertEquals(a, drained.playerId(), "take must return player A's job first");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("take interrupted unexpectedly");
        }
        assertEquals(0, queue.size());

        // Player B must be able to re-offer NOW. If the previous failed
        // offer left a dangling pending entry, this would silently be a
        // no-op (pending says B is already queued, queue stays empty).
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
    @DisplayName("offer() of newer job for same player marks the old as stale and reuses dedup slot")
    void newOfferMarksOldStaleAndDoesNotDuplicate() {
        RenderQueue queue = new RenderQueue(4);
        UUID id = UUID.randomUUID();
        EvalJob first = jobFor(id);
        EvalJob second = jobFor(id);

        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second),
                "newer job for same player must be acceptable (dedup replaces, not rejects)");

        // The queue may contain both, but the older one must be marked stale
        // so workers skip it on take.
        assertTrue(first.isStale(),
                "first job must be marked stale once a newer job lands for the same player");
        assertFalse(second.isStale(),
                "newer job must remain non-stale");

        // take() skips stale entries transparently.
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
```

- [ ] **Step 1.2: Verify it runs**

Run `/opt/homebrew/bin/mvn -B -ntp clean test -P integration -Dtest=RenderQueueDedupConcurrencyIT 2>&1 | tail -8`.

Expected: BUILD SUCCESS, 2 tests passed.

If the second test (`newOfferMarksOldStaleAndDoesNotDuplicate`) fails, the production behaviour around stale-replacement may differ from what the explorer report described. STOP and read `RenderQueue.offer` to verify the actual semantics, then either fix the test or report it as a production discrepancy.

- [ ] **Step 1.3: Verify pr-gate includes it**

Run `/opt/homebrew/bin/mvn -B -ntp clean test -P pr-gate 2>&1 | grep -E "RenderQueueDedupConcurrencyIT|Tests run: [0-9]+, Failures" | tail -3`.

Expected: BUILD SUCCESS, `RenderQueueDedupConcurrencyIT` in the executed set.

- [ ] **Step 1.4: Commit**

```
test(integration): cover EVAL-001 RenderQueue dedup rollback
```

---

## Task 2: `RenderWorkerBufferOwnershipIT` (EVAL-002)

**Risk:** `RenderWorker` must allocate a NEW `byte[]` for every frame and never reuse buffers via `RenderService.render(scene, ..., outBuf)`. If a worker reused a buffer, the `MlCoalescerWorker` could observe a half-overwritten array because frames travel through the queue and are batched at unknown times.

**Invariant:** consecutive frames from the same worker carry distinct `rgb` array references — `frame1.rgb() != frame2.rgb()`.

**Files:**
- Create: `src/test/java/ru/ashesha/buildBattleAI/evaluation/RenderWorkerBufferOwnershipIT.java`

- [ ] **Step 2.1: Write the test**

```java
package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.render.RenderService;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test — covers risk EVAL-002 from the test-coverage spec.
 * <p>
 * Invariant: {@link RenderWorker} must not reuse {@code byte[]} buffers
 * across frames. Once a buffer is enqueued into {@link MlQueue} it
 * becomes the single-owner immutable input of a downstream batch — the
 * worker has lost ownership and any mutation through a stale reference
 * would corrupt the in-flight ML payload.
 * <p>
 * Why integration: the contract spans {@code RenderWorker.run()},
 * {@code RenderService.render(...)} (the allocating overload), and the
 * downstream {@link MlQueue}. Asserting "every frame has a fresh array"
 * needs all three actors wired.
 * <p>
 * Strategy: queue two jobs for the same player back-to-back; the worker
 * processes both. The {@link RenderService} mock returns a freshly
 * allocated buffer on each call. We then drain both frames and assert
 * the two buffers are distinct references (and that nothing was reused
 * across the two calls).
 */
@Tag("integration")
class RenderWorkerBufferOwnershipIT {

    @Test
    @DisplayName("Every frame produced by RenderWorker carries a fresh rgb buffer")
    void everyFrameGetsFreshBuffer() throws InterruptedException {
        RenderQueue rq = new RenderQueue(4);
        MlQueue mq = new MlQueue(4);
        EvaluationMetrics metrics = new EvaluationMetrics(8);
        PluginLogger logger = mock(PluginLogger.class);

        // RenderService mock: allocates a fresh buffer on every call.
        // We tag the buffer's first byte with a counter so we can verify
        // distinct provenance even if Java were to recycle memory addresses.
        AtomicInteger callCount = new AtomicInteger();
        RenderService renderService = mock(RenderService.class);
        when(renderService.render(any(MutablePlotScene.class),
                anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat()))
                .thenAnswer(inv -> {
                    byte[] buf = new byte[224 * 224 * 3];
                    buf[0] = (byte) (callCount.incrementAndGet() & 0xFF);
                    return buf;
                });

        // Queue two jobs for DIFFERENT players (same-player would mark the
        // first as stale and the worker would skip it).
        MutablePlotScene mirror = mock(MutablePlotScene.class);
        when(mirror.readLock()).thenReturn(new ReentrantReadWriteLock().readLock());

        rq.offer(jobFor(UUID.randomUUID(), mirror));
        rq.offer(jobFor(UUID.randomUUID(), mirror));

        RenderWorker worker = new RenderWorker(0, rq, mq, renderService, metrics, logger);
        Thread t = new Thread(worker, "test-render-worker-bufown");
        t.setDaemon(true);
        t.start();

        // Drain both frames (or fail the test if either doesn't arrive).
        // 5s is generous — under load CI a single render mock returns
        // instantly, so the only delay is thread scheduling.
        List<EvalFrame> first = mq.drainBatch(1, 5_000);
        List<EvalFrame> second = mq.drainBatch(1, 5_000);

        worker.stop();
        t.interrupt();
        t.join(TimeUnit.SECONDS.toMillis(2));

        assertEquals(1, first.size(), "first batch must contain exactly one frame");
        assertEquals(1, second.size(), "second batch must contain exactly one frame");

        byte[] buf1 = first.get(0).rgb();
        byte[] buf2 = second.get(0).rgb();

        // Identity check: buffers must be DIFFERENT array references.
        assertNotSame(buf1, buf2,
                "RenderWorker must allocate a fresh byte[] per frame — "
                        + "reusing the buffer would corrupt the in-flight ML batch");

        // Defensive secondary check: the tag bytes (call sequence numbers)
        // must differ. If buffers were reused, the first frame's tag would
        // have been overwritten by the second call.
        assertNotEquals(buf1[0], buf2[0],
                "tag bytes must differ — same value implies the second "
                        + "call overwrote the first buffer in place");

        // The worker must call the ALLOCATING overload, not the
        // buffer-reuse overload. Verify by counting mock invocations on
        // each signature.
        verify(renderService, times(2)).render(any(MutablePlotScene.class),
                anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat());
        verify(renderService, never()).render(any(MutablePlotScene.class),
                anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat(),
                any(byte[].class));
    }

    private static EvalJob jobFor(UUID id, MutablePlotScene mirror) {
        return EvalJob.builder()
                .arenaName("a")
                .playerId(id)
                .playerName("p-" + id)
                .plotIndex(0)
                .themeIndex(0)
                .expectedTheme("t")
                .mirror(mirror)
                .cameraX(0).cameraY(0).cameraZ(0)
                .cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(System.nanoTime())
                .build();
    }
}
```

- [ ] **Step 2.2: Verify**

`/opt/homebrew/bin/mvn -B -ntp clean test -P integration -Dtest=RenderWorkerBufferOwnershipIT 2>&1 | tail -8`.

Expected: BUILD SUCCESS, 1 test passed.

If the `verify(renderService, never()).render(..., any(byte[].class))` assertion fails, the production worker is using the reuse overload — that's a real EVAL-002 bug. STOP and report.

If the `assertNotSame` fails, also a real bug. Report.

- [ ] **Step 2.3: Commit**

```
test(integration): cover EVAL-002 RenderWorker fresh-buffer ownership
```

---

## Task 3: `EvaluationCoordinatorLifecycleIT` (EVAL-003, EVAL-005, EVAL-011)

This single class hosts three related tests because they all exercise the same coordinator + queue + metrics + session-registry quartet. Bundling avoids three near-identical fixture blocks.

**Files:**
- Create: `src/test/java/ru/ashesha/buildBattleAI/evaluation/EvaluationCoordinatorLifecycleIT.java`

**Background needed before writing tests:**

The coordinator's `tick(long nowNanos)` walks the registry. For each `PLAYING` session it advances camera index, then for each dirty player whose last enqueue was ≥ `minCadenceMs` ago it offers an `EvalJob`. On `offer()` failure (queue full) it increments `droppedRenderJobs` and importantly does NOT record `lastEvalAtNanos` — so the next tick re-considers the same player.

`SessionHandle` is constructed `new SessionHandle(GameSession session, EvaluationCallback callback)`. `GameSession`, `Arena`, `GamePlayer` are all concrete classes — mock with Mockito. The existing `EvaluationCoordinatorTest` has a `handleWith(UUID, boolean)` helper showing the full mock chain. **Read it first** before writing this task. Path: `src/test/java/ru/ashesha/buildBattleAI/evaluation/EvaluationCoordinatorTest.java`.

- [ ] **Step 3.1: Read the existing pattern**

Run:

```bash
cat /Users/ashesha/Sources/buildbattleai/src/test/java/ru/ashesha/buildBattleAI/evaluation/EvaluationCoordinatorTest.java
```

Identify (a) the `handleWith(UUID, boolean)` helper or its equivalent, (b) how it constructs the mock GameSession + Arena + GamePlayer chain. Copy that pattern into the new IT file's test fixtures.

- [ ] **Step 3.2: Write the test class**

Skeleton (adapt the `handleWith` body using the pattern from `EvaluationCoordinatorTest`):

```java
package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test — covers risks EVAL-003, EVAL-005, EVAL-011 from the
 * test-coverage spec.
 * <ul>
 *   <li><b>EVAL-003:</b> unregistering a session while a job is in flight
 *       must not crash the pipeline — the next tick simply skips the
 *       arena, the in-flight job (if any reaches the ML stage) is
 *       silently dropped at callback lookup, and the batch is still
 *       counted as completed.</li>
 *   <li><b>EVAL-005:</b> when the render queue is full, the coordinator
 *       must increment the drop counter but NOT update
 *       {@code lastEvalAtNanos} — otherwise the next tick would skip
 *       this player and the drop would persist into the future,
 *       producing a busy-loop / starvation interaction.</li>
 *   <li><b>EVAL-011:</b> a dropped job must be re-considered on the
 *       very next tick (assuming the player is still dirty and the
 *       queue has freed up).</li>
 * </ul>
 *
 * <p>The fixture pattern (mock Arena + PlotData + GameSession + GamePlayer
 * registered via a {@code Map<String, SessionHandle>}) mirrors
 * {@link EvaluationCoordinatorTest}. We extract the helper here so
 * each test reads at a glance.
 */
@Tag("integration")
class EvaluationCoordinatorLifecycleIT {

    private Map<String, SessionHandle> registry;
    private RenderQueue renderQueue;
    private EvaluationMetrics metrics;
    private EvaluationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        registry = new ConcurrentHashMap<>();
        renderQueue = new RenderQueue(1); // capacity 1 to engineer queue-full scenarios
        metrics = new EvaluationMetrics(8);
        coordinator = new EvaluationCoordinator(registry, renderQueue, metrics, /*minCadenceMs=*/ 10L);
    }

    /**
     * Test EVAL-005: when the queue is full, the coordinator increments
     * the drop counter but lastEvalAtNanos must remain at its sentinel
     * (0L) so the next tick re-evaluates this player.
     */
    @Test
    @DisplayName("EVAL-005: queue-full drop does NOT update lastEvalAtNanos")
    void queueFullDoesNotUpdateLastEvalAt() {
        UUID pid = UUID.randomUUID();
        SessionHandle handle = registerSessionWithDirtyPlayer("arena-1", pid);

        // Pre-fill the queue capacity 1 so the next offer fails.
        renderQueue.offer(throwawayJob(UUID.randomUUID()));

        long beforeTick = System.nanoTime();
        coordinator.tick(beforeTick);

        // Drop happened.
        assertEquals(1, metrics.snapshot(0, 0, 0, 0).droppedRenderJobs(),
                "queue-full must increment droppedRenderJobs counter");

        // lastEvalAtNanos remains at sentinel — production reads 0L when
        // never recorded.
        assertEquals(0L, handle.lastEvalAtNanos(pid),
                "lastEvalAtNanos must stay at 0 (sentinel) when the offer "
                        + "was dropped — otherwise next tick busy-loops");
    }

    /**
     * Test EVAL-011: the next tick MUST re-consider a dropped player
     * (because EVAL-005 leaves lastEvalAtNanos at the sentinel, so the
     * cadence gate doesn't suppress).
     */
    @Test
    @DisplayName("EVAL-011: dropped job is re-considered in the next tick")
    void droppedJobReenqueuedNextTick() {
        UUID pid = UUID.randomUUID();
        registerSessionWithDirtyPlayer("arena-1", pid);

        // Engineer the drop.
        renderQueue.offer(throwawayJob(UUID.randomUUID()));
        coordinator.tick(System.nanoTime());
        assertEquals(1, metrics.snapshot(0, 0, 0, 0).droppedRenderJobs(),
                "precondition: queue-full drop must happen on the first tick");

        // Free the slot. Now the next tick must successfully enqueue THIS
        // player (proving the coordinator re-considers dropped players).
        // The throwaway job from the prefill is what we drain.
        try {
            renderQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("take interrupted");
        }
        assertEquals(0, renderQueue.size(), "precondition: queue empty after drain");

        coordinator.tick(System.nanoTime());

        // After the second tick, the queue contains our player's job.
        assertEquals(1, renderQueue.size(),
                "second tick must re-enqueue the previously-dropped player");
    }

    /**
     * Test EVAL-003: unregistering a session in the middle of operation
     * must not throw. Subsequent ticks simply skip the missing arena.
     * The full "in-flight ML batch survives" assertion lives in
     * {@link MlCoalescerCallbackResilienceIT} (the coalescer is the
     * component that survives the lookup miss); here we only verify the
     * coordinator side: ticks before unregister enqueue normally,
     * unregister returns without throwing, and ticks AFTER unregister
     * are no-ops.
     */
    @Test
    @DisplayName("EVAL-003: unregister mid-flight is silent — coordinator tolerates missing arena")
    void unregisterDuringInflight() {
        UUID pid = UUID.randomUUID();
        registerSessionWithDirtyPlayer("arena-1", pid);

        coordinator.tick(System.nanoTime());
        assertEquals(1, renderQueue.size(),
                "precondition: first tick must enqueue (queue starts empty, "
                        + "minCadenceMs=10 allows immediate evaluation)");

        // Unregister.
        registry.remove("arena-1");

        // Next tick: arena gone. No throw, no enqueue.
        int sizeBefore = renderQueue.size();
        assertDoesNotThrow(() -> coordinator.tick(System.nanoTime() + 1_000_000_000L),
                "tick after unregister must not throw");
        assertEquals(sizeBefore, renderQueue.size(),
                "no new jobs may be enqueued after unregister");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Builds a SessionHandle whose mocked GameSession reports state PLAYING,
     * one dirty player with the given id, and the minimum stubs needed by
     * the coordinator. **Engineer:** copy/adapt the fixture from
     * EvaluationCoordinatorTest.handleWith(UUID, boolean). Do not re-derive
     * it from scratch — too easy to miss a stub.
     */
    private SessionHandle registerSessionWithDirtyPlayer(String arenaName, UUID pid) {
        // TODO during implementation: paste the body of EvaluationCoordinatorTest's
        // session-builder helper, adjusted to return a SessionHandle and to
        // register it into `registry` under arenaName. Mocked types:
        // GameSession, Arena, Arena.PlotData, GamePlayer.
        // The handle's callback can be a no-op `(playerId, themeIndex) -> {}`.
        throw new UnsupportedOperationException(
                "Replace this stub with the actual mock-session builder " +
                "patterned on EvaluationCoordinatorTest.");
    }

    private EvalJob throwawayJob(UUID id) {
        ru.ashesha.buildBattleAI.render.data.MutablePlotScene mirror =
                org.mockito.Mockito.mock(ru.ashesha.buildBattleAI.render.data.MutablePlotScene.class);
        org.mockito.Mockito.when(mirror.readLock())
                .thenReturn(new java.util.concurrent.locks.ReentrantReadWriteLock().readLock());
        return EvalJob.builder()
                .arenaName("filler")
                .playerId(id)
                .playerName("filler")
                .plotIndex(0)
                .themeIndex(0)
                .expectedTheme("t")
                .mirror(mirror)
                .cameraX(0).cameraY(0).cameraZ(0)
                .cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(System.nanoTime())
                .build();
    }
}
```

The `registerSessionWithDirtyPlayer` helper is a deliberate stub — the implementer MUST replace it with the pattern from `EvaluationCoordinatorTest`. If that test's helper isn't directly portable (e.g. it returns a `GameSession` instead of a `SessionHandle`), the implementer adapts. If something fundamental is incompatible (e.g. `SessionHandle` constructor requires extra args our scaffolding doesn't supply), STOP and re-investigate via a code-explorer subagent.

- [ ] **Step 3.3: Verify**

`/opt/homebrew/bin/mvn -B -ntp clean test -P integration -Dtest=EvaluationCoordinatorLifecycleIT 2>&1 | tail -10`.

Expected: BUILD SUCCESS, 3 tests passed.

- [ ] **Step 3.4: Commit**

```
test(integration): cover EVAL-003/005/011 evaluation coordinator lifecycle
```

---

## Task 4: `MlCoalescerCallbackResilienceIT` (EVAL-006)

**Risk:** the coalescer dispatches the score callback via `dispatcher.dispatch(() -> cb.onEvaluated(...))`. With Bukkit's `runTask` this lands on the main thread asynchronously. In our tests we use a synchronous dispatcher — but the worker loop ITSELF must remain alive even if a per-frame callback misbehaves.

**Invariant:** if a score callback throws, the worker continues to drain subsequent batches AND `metrics.mlBatchesCompleted` keeps incrementing.

**Gotcha from explorer report:** the production code does NOT wrap the dispatcher call in a per-frame try-catch. To prove worker survival, the test's `MainThreadDispatcher` must catch the exception itself. The dispatcher is the seam under test here: production-side it's `Bukkit.runTask` (which swallows worker-side exceptions) — we substitute one that swallows + records.

**Files:**
- Create: `src/test/java/ru/ashesha/buildBattleAI/evaluation/MlCoalescerCallbackResilienceIT.java`

- [ ] **Step 4.1: Pre-flight inspection**

Before writing the test, read:

```bash
cat /Users/ashesha/Sources/buildbattleai/src/test/java/ru/ashesha/buildBattleAI/evaluation/MlCoalescerWorkerTest.java
```

Identify:
- The shape of the mock `BBAIMLService` (which method does it stub? `predictBatchRgb(byte[][], int, int, int)`? The return type is `PredictionResult[]`).
- The `MainThreadDispatcher` it uses (likely `r -> r.run()`).
- How it asserts on completion (probably `await().until(() -> metrics.snapshot(...).mlBatchesCompleted() >= 1)`).

Copy that pattern. Report what you found before writing this task's test.

- [ ] **Step 4.2: Write the test**

Skeleton — fill in the mock-stub specifics from the pre-flight:

```java
package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationCallback;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test — covers risk EVAL-006 from the test-coverage spec.
 * <p>
 * Invariant: a throwing score callback dispatched to the "main thread"
 * (in tests: a synchronous dispatcher that records and swallows the
 * throwable) must not kill the {@link MlCoalescerWorker} run loop.
 * Subsequent batches must continue to be processed and the
 * {@code mlBatchesCompleted} counter must keep advancing.
 * <p>
 * Production gotcha: the worker's dispatch site is not wrapped in a
 * try/catch — Bukkit's main-thread scheduler swallows worker-side
 * throwables in production. This test substitutes a dispatcher that
 * behaves the same way (catch + log), proving the worker survives
 * the dispatch as long as the dispatcher itself is well-behaved.
 */
@Tag("integration")
class MlCoalescerCallbackResilienceIT {

    @Test
    @DisplayName("throwing score callback does NOT kill MlCoalescerWorker")
    void throwingCallbackSurvives() throws InterruptedException {
        MlQueue mq = new MlQueue(4);
        EvaluationMetrics metrics = new EvaluationMetrics(8);
        PluginLogger logger = mock(PluginLogger.class);

        // BBAIMLService mock: return a PredictionResult[] whose top-K
        // includes the expected theme. Fill this stub by patterning on
        // MlCoalescerWorkerTest's stub — paste the exact stub here.
        BBAIMLService ml = mock(BBAIMLService.class);
        // TODO during implementation: stub ml.predictBatchRgb(...) to
        // return an array of PredictionResult with the expected theme
        // present in topK. See MlCoalescerWorkerTest for the canonical
        // stub. Without this, the dispatch path won't fire.

        // Callback that throws on EVERY invocation.
        EvaluationCallback throwingCallback = (uuid, themeIdx) -> {
            throw new RuntimeException("intentional — verifying worker survival");
        };
        Function<String, EvaluationCallback> registry = arena -> throwingCallback;

        // Swallow-and-count main-thread dispatcher. Matches the
        // production Bukkit.runTask semantics (swallows worker-side
        // exceptions, schedules onto the main thread).
        AtomicInteger swallowedExceptions = new AtomicInteger();
        MlCoalescerWorker.MainThreadDispatcher dispatcher = r -> {
            try {
                r.run();
            } catch (Throwable t) {
                swallowedExceptions.incrementAndGet();
            }
        };

        MlCoalescerWorker worker = new MlCoalescerWorker(
                mq, ml, registry, dispatcher, metrics, logger,
                /*maxBatchSize=*/ 2, /*waitMs=*/ 50, /*topK=*/ 2);
        Thread t = new Thread(worker, "test-ml-coalescer-throwing");
        t.setDaemon(true);
        t.start();

        // Offer THREE frames. If the first throwing callback killed the
        // worker, the 2nd and 3rd would never be processed and
        // mlBatchesCompleted would stop at 1.
        for (int i = 0; i < 3; i++) {
            mq.offer(syntheticFrame(UUID.randomUUID()));
        }

        // Await ≥ 2 batches completed. With waitMs=50 and 3 frames, the
        // worker will drain in ≤ 2 batch cycles (could be 1, 2, or 3
        // batches depending on timing). The threshold is "more than 1
        // batch was processed despite the throw."
        await().atMost(5, SECONDS)
                .until(() -> metrics.snapshot(0, 0, 0, 0).mlBatchesCompleted() >= 2);

        worker.stop();
        t.interrupt();
        t.join(TimeUnit.SECONDS.toMillis(2));

        // Final assertions.
        long completed = metrics.snapshot(0, 0, 0, 0).mlBatchesCompleted();
        assertTrue(completed >= 2,
                "worker must complete ≥ 2 batches despite throwing callbacks; got "
                        + completed);
        assertTrue(swallowedExceptions.get() >= 1,
                "dispatcher must have swallowed at least one throwable");
    }

    /**
     * Builds a synthetic {@link EvalFrame} with a fresh {@code byte[]}
     * buffer. The arena name is fixed so the callback registry function
     * returns our throwing callback regardless of player.
     */
    private static EvalFrame syntheticFrame(UUID pid) {
        MutablePlotScene mirror = mock(MutablePlotScene.class);
        when(mirror.readLock()).thenReturn(new ReentrantReadWriteLock().readLock());
        EvalJob job = EvalJob.builder()
                .arenaName("arena-1")
                .playerId(pid)
                .playerName("p-" + pid)
                .plotIndex(0)
                .themeIndex(0)
                .expectedTheme("theme")
                .mirror(mirror)
                .cameraX(0).cameraY(0).cameraZ(0)
                .cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(System.nanoTime())
                .build();
        return new EvalFrame(job, new byte[224 * 224 * 3], System.nanoTime());
    }
}
```

The implementer fills the `BBAIMLService.predictBatchRgb` stub by patterning on `MlCoalescerWorkerTest`. The stub must return prediction results where the top-K list contains `"theme"` (matching `EvalJob.expectedTheme()`) — otherwise the worker considers the prediction a miss and the dispatcher is never invoked, making the throw unreachable.

- [ ] **Step 4.3: Verify**

`/opt/homebrew/bin/mvn -B -ntp clean test -P integration -Dtest=MlCoalescerCallbackResilienceIT 2>&1 | tail -10`.

Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 4.4: Commit**

```
test(integration): cover EVAL-006 ML coalescer callback resilience
```

---

## Task 5: Final verification

- [ ] **Step 5.1: pr-gate run**

`/opt/homebrew/bin/mvn -B -ntp clean verify -P pr-gate 2>&1 | grep -E "Tests run: [0-9]+, Failures.*Skipped|BUILD" | tail -3`.

Expected: BUILD SUCCESS. The total Tests-run count must increase by exactly 7 over the Phase 0+1 baseline (12 769) — the 7 new test methods land in PR CI via the `integration` tag. Final expected: **12 776**.

- [ ] **Step 5.2: integration-profile sanity**

`/opt/homebrew/bin/mvn -B -ntp clean test -P integration 2>&1 | grep -E "Tests run|BUILD" | tail -5`.

Expected: BUILD SUCCESS, 7 tests run (`@Tag("integration")` filter picks up all 4 IT classes; class counts: 2 + 1 + 3 + 1 = 7).

- [ ] **Step 5.3: default-profile sanity**

`/opt/homebrew/bin/mvn -B -ntp clean test 2>&1 | grep -E "Tests run: [0-9]+, Failures.*Skipped" | tail -2`.

The default profile does NOT exclude `integration` (per the design in `pom.xml:74` — only e2e/bench/stress/ml-it/nightly-only are excluded by default). So integration tests RUN by default. Expected count: 12 776 (same as pr-gate).

If the count is wrong, double-check whether the default profile excludes integration (it shouldn't, by spec).

- [ ] **Step 5.4: Commit any incidental cleanup if needed**

If steps 5.1–5.3 surfaced anything (e.g. a typo in a Javadoc), commit it as `chore: tidy …`.

---

## Self-review checklist

- [ ] All 4 IT classes exist under `src/test/java/ru/ashesha/buildBattleAI/evaluation/`.
- [ ] All 4 classes carry `@Tag("integration")` at the class level.
- [ ] All 4 classes declare `package ru.ashesha.buildBattleAI.evaluation;` (NOT `integration.evaluation` — package-private access is load-bearing).
- [ ] Every test method is documented with a `@DisplayName` that names the risk ID.
- [ ] No production code under `src/main/` was modified.
- [ ] `mvn -P pr-gate` passes; final count = 12 776.
- [ ] Each task committed separately with the suggested commit message.

---

## What comes next

Phase 3 (Integration: game) gets its own sibling plan: `2026-06-07-test-coverage-phase3-game.md`. Risks: GAME-01/02/03/05/09. Then Phase 4 (ml + data + arena + config), Phase 5 (stress), Phase 6 (E2E expansion), Phase 7 (ML-IT expansion), Phase 8 (bench baselines + nightly workflow + final CLAUDE.md). One PR per phase.
