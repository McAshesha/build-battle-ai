package ru.ashesha.buildBattleAI.stress.evaluation;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.evaluation.EvaluationMetrics;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stress test for risk EVAL-004: {@link EvaluationStats} snapshots must remain
 * internally consistent under heavy concurrent write load.
 *
 * <p><b>Risk:</b> EVAL-004 — "EvaluationStats snapshot remains internally
 * consistent under concurrent load — rendersCompleted &gt;= matchesDispatched."
 *
 * <p><b>Invariant under test:</b>
 * <ol>
 *   <li>Every counter in every snapshot is non-negative.</li>
 *   <li>{@code rendersCompleted &gt;= matchesDispatched} holds in every snapshot
 *       because renders must precede ML batches, which precede match dispatch.
 *       Writers increment in that order in every iteration, so a snapshot can
 *       never legitimately see more matches than renders.</li>
 *   <li>Counters are non-decreasing across consecutive snapshots from the same
 *       reader thread (snapshots are taken in monotonic wall-clock order).</li>
 *   <li>After all writers finish, the final snapshot exactly equals the total
 *       number of increments issued by all writer threads.</li>
 * </ol>
 *
 * <p><b>Why stress tier:</b> The invariants hold trivially in single-threaded
 * code. The interesting failure mode (torn reads across multiple
 * {@link java.util.concurrent.atomic.LongAdder LongAdder} cells) only surfaces
 * under contention from many simultaneous writers. A unit test cannot exercise
 * this because it is inherently about interleaving; the stress tier is the
 * cheapest tier that reliably reproduces the race window. Target duration is
 * ~10–15 s on a modern Mac laptop.
 *
 * <p><b>Note on eventual-consistency:</b> {@link EvaluationMetrics#snapshot}
 * is documented as eventually-consistent — counters are not read atomically as
 * a group. Therefore <em>momentary</em> violations of the ordering invariant
 * between two counters would be acceptable for monitoring purposes. However,
 * because every writer increments {@code rendersCompleted} <em>before</em>
 * {@code matchesDispatched}, the {@code rendersCompleted &gt;= matchesDispatched}
 * guarantee should hold in practice. If this test uncovers a real violation it
 * will be documented in the annotation and the invariant relaxed accordingly.
 */
@Tag("stress")
class EvaluationStatsConcurrencyStress {

    /**
     * Number of concurrent writer threads racing on the metrics instance.
     * 8 threads means the JVM will spread work across available cores.
     */
    private static final int WRITER_THREADS = 8;

    /**
     * Number of iterations per writer thread. 50 000 × 8 = 400 000 total
     * increments per counter — enough to expose contention without making the
     * test run for minutes.
     */
    private static final int ITERATIONS_PER_WRITER = 50_000;

    /**
     * Number of concurrent reader threads continuously polling snapshots.
     * 4 readers create enough snapshot traffic to interleave with all writers
     * but do not saturate the CPU and hide writer contention.
     */
    private static final int READER_THREADS = 4;

    /**
     * Max batch size passed to {@link EvaluationMetrics}. Matches the default
     * production value so the histogram array length is realistic.
     */
    private static final int MAX_BATCH_SIZE = 8;

    /**
     * Verifies EVAL-004: snapshot fields are internally consistent under load.
     *
     * <p>Writer threads increment counters in a fixed ordering that mirrors the
     * real pipeline:
     * <ol>
     *   <li>{@code incRendersCompleted()} — a render finished.</li>
     *   <li>{@code incMlBatchesCompleted()} — the ML coalescer processed it.</li>
     *   <li>{@code incMatchesDispatched()} — the match callback was triggered.</li>
     * </ol>
     * Because every writer always increments renders before matches, any snapshot
     * should satisfy {@code rendersCompleted >= matchesDispatched}.
     *
     * <p>Reader threads continuously take snapshots while writers are active and
     * record any violation. After all writers have finished, a final settled
     * snapshot is taken and compared against the exact expected totals tracked
     * by {@link LongAdder} accumulators in the test.
     *
     * @throws InterruptedException if the test thread is interrupted while
     *                              waiting for workers to finish
     */
    @Test
    void snapshotMonotonicUnderLoad() throws InterruptedException {
        EvaluationMetrics metrics = new EvaluationMetrics(MAX_BATCH_SIZE);

        // LongAdders in the test mirror the expected values so the final
        // comparison does not need to know thread scheduling.
        LongAdder expectedRenders  = new LongAdder();
        LongAdder expectedBatches  = new LongAdder();
        LongAdder expectedMatches  = new LongAdder();
        LongAdder expectedDropsR   = new LongAdder();
        LongAdder expectedDropsML  = new LongAdder();
        LongAdder expectedErrR     = new LongAdder();
        LongAdder expectedErrML    = new LongAdder();

        // Shared failure trackers — readers write here on assertion failure.
        List<String> readerFailures = new ArrayList<>();
        // Guards readerFailures against concurrent modification; readers are few
        // and the list is only written on failure, so a simple synchronized list
        // is fine.
        List<String> failures = java.util.Collections.synchronizedList(readerFailures);

        // A latch that drops to zero once every writer and reader has set up,
        // triggering a simultaneous start so threads race from the very first
        // increment.
        CountDownLatch startGate = new CountDownLatch(WRITER_THREADS + READER_THREADS);
        // A latch that drops to zero when all writers have finished their
        // iterations. Readers poll until this latch reaches zero.
        CountDownLatch writersFinished = new CountDownLatch(WRITER_THREADS);

        ExecutorService pool = Executors.newFixedThreadPool(WRITER_THREADS + READER_THREADS);

        // ---- Writer tasks -------------------------------------------------
        for (int w = 0; w < WRITER_THREADS; w++) {
            pool.submit(() -> {
                startGate.countDown();
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    writersFinished.countDown();
                    return;
                }

                for (int i = 0; i < ITERATIONS_PER_WRITER; i++) {
                    // Increment in pipeline order: render → batch → match.
                    // This ordering is the foundation of the rendersCompleted
                    // >= matchesDispatched invariant.
                    metrics.incRendersCompleted();
                    expectedRenders.increment();

                    metrics.incMlBatchesCompleted();
                    expectedBatches.increment();

                    // One in every 3 renders results in a match dispatch.
                    // This exercises the gap between renders and matches.
                    if (i % 3 == 0) {
                        metrics.incMatchesDispatched();
                        expectedMatches.increment();
                    }

                    // Simulate occasional drops and errors.
                    if (i % 7 == 0) {
                        metrics.incDroppedRenderJobs();
                        expectedDropsR.increment();
                    }
                    if (i % 11 == 0) {
                        metrics.incDroppedMlJobs();
                        expectedDropsML.increment();
                    }
                    if (i % 13 == 0) {
                        metrics.incRenderErrors();
                        expectedErrR.increment();
                    }
                    if (i % 17 == 0) {
                        metrics.incMlErrors();
                        expectedErrML.increment();
                    }

                    // Record a batch size so the histogram accumulates data.
                    metrics.recordBatchSize((i % MAX_BATCH_SIZE) + 1);
                }

                writersFinished.countDown();
            });
        }

        // ---- Reader tasks -------------------------------------------------
        for (int r = 0; r < READER_THREADS; r++) {
            pool.submit(() -> {
                startGate.countDown();
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                EvaluationStats prev = null;
                // Poll until all writers have finished.
                while (writersFinished.getCount() > 0) {
                    EvaluationStats snap = metrics.snapshot(0, 0, 0, 0);

                    // 1. No counter is negative.
                    if (snap.rendersCompleted() < 0)
                        failures.add("rendersCompleted negative: " + snap.rendersCompleted());
                    if (snap.mlBatchesCompleted() < 0)
                        failures.add("mlBatchesCompleted negative: " + snap.mlBatchesCompleted());
                    if (snap.matchesDispatched() < 0)
                        failures.add("matchesDispatched negative: " + snap.matchesDispatched());
                    if (snap.droppedRenderJobs() < 0)
                        failures.add("droppedRenderJobs negative: " + snap.droppedRenderJobs());
                    if (snap.droppedMlJobs() < 0)
                        failures.add("droppedMlJobs negative: " + snap.droppedMlJobs());
                    if (snap.renderErrors() < 0)
                        failures.add("renderErrors negative: " + snap.renderErrors());
                    if (snap.mlErrors() < 0)
                        failures.add("mlErrors negative: " + snap.mlErrors());

                    // 2. Core ordering invariant: renders >= matches.
                    // Writers always increment renders before matches (see above),
                    // so a valid snapshot must never show more matches than renders.
                    if (snap.rendersCompleted() < snap.matchesDispatched())
                        failures.add("EVAL-004 VIOLATED: rendersCompleted=" + snap.rendersCompleted()
                                + " < matchesDispatched=" + snap.matchesDispatched());

                    // 3. Counters are non-decreasing from this reader's perspective.
                    if (prev != null) {
                        if (snap.rendersCompleted() < prev.rendersCompleted())
                            failures.add("rendersCompleted decreased: "
                                    + prev.rendersCompleted() + " -> " + snap.rendersCompleted());
                        if (snap.mlBatchesCompleted() < prev.mlBatchesCompleted())
                            failures.add("mlBatchesCompleted decreased: "
                                    + prev.mlBatchesCompleted() + " -> " + snap.mlBatchesCompleted());
                        if (snap.matchesDispatched() < prev.matchesDispatched())
                            failures.add("matchesDispatched decreased: "
                                    + prev.matchesDispatched() + " -> " + snap.matchesDispatched());
                    }

                    prev = snap;
                }
            });
        }

        pool.shutdown();
        boolean done = pool.awaitTermination(60, TimeUnit.SECONDS);
        assertTrue(done, "Worker pool did not finish within 60 s — possible deadlock");

        // ---- Final settled snapshot ----------------------------------------
        // All writers have finished; LongAdder.sum() is now exact.
        EvaluationStats final_ = metrics.snapshot(0, 0, 0, 0);

        long expRenders  = expectedRenders.sum();
        long expBatches  = expectedBatches.sum();
        long expMatches  = expectedMatches.sum();
        long expDropsR   = expectedDropsR.sum();
        long expDropsML  = expectedDropsML.sum();
        long expErrR     = expectedErrR.sum();
        long expErrML    = expectedErrML.sum();

        // All writer increments must be visible in the final snapshot.
        failures.add(checkEq("rendersCompleted",  final_.rendersCompleted(),  expRenders));
        failures.add(checkEq("mlBatchesCompleted",final_.mlBatchesCompleted(),expBatches));
        failures.add(checkEq("matchesDispatched", final_.matchesDispatched(), expMatches));
        failures.add(checkEq("droppedRenderJobs", final_.droppedRenderJobs(), expDropsR));
        failures.add(checkEq("droppedMlJobs",     final_.droppedMlJobs(),     expDropsML));
        failures.add(checkEq("renderErrors",      final_.renderErrors(),      expErrR));
        failures.add(checkEq("mlErrors",          final_.mlErrors(),          expErrML));

        // Remove null entries (checkEq returns null on success).
        failures.removeIf(s -> s == null);

        assertTrue(failures.isEmpty(),
                "Stress failures detected:\n" + String.join("\n", failures));
    }

    /**
     * Returns a human-readable failure message when {@code actual != expected},
     * or {@code null} when they match.
     *
     * @param name     field label for the failure message
     * @param actual   observed counter value in the final snapshot
     * @param expected sum of all increments issued by writer threads
     * @return failure description, or {@code null} on success
     */
    private static String checkEq(String name, long actual, long expected) {
        if (actual == expected)
            return null;
        return name + ": expected=" + expected + ", actual=" + actual;
    }
}
