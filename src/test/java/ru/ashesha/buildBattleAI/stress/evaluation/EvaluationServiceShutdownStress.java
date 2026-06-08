package ru.ashesha.buildBattleAI.stress.evaluation;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.evaluation.EvaluationService;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;
import ru.ashesha.buildBattleAI.render.RenderService;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Stress test for risk EVAL-010: {@link EvaluationService#stats()} is safe to
 * call at any moment, including during concurrent shutdown — no NPE, no race.
 *
 * <p><b>Risk:</b> EVAL-010 — "stats() is safe to call at any moment, including
 * during concurrent shutdown — no NPE, no IllegalStateException, no race."
 *
 * <p><b>Invariant under test:</b>
 * <ol>
 *   <li>{@code stats()} never throws any exception (NPE in particular) regardless
 *       of how it interleaves with a concurrent {@code shutdown()} call.</li>
 *   <li>Every snapshot returned is non-null.</li>
 *   <li>Every counter in every snapshot is &gt;= 0 (zero snapshot is the
 *       documented contract when the service is not running).</li>
 * </ol>
 *
 * <p><b>The race window:</b> {@code stats()} first copies the volatile field
 * references {@code metrics}, {@code renderQueue}, {@code mlQueue}, and
 * {@code registry} into locals, then checks {@code enabled.get()}. Meanwhile,
 * {@code shutdown()} flips {@code enabled} to {@code false} first and then nulls
 * the same fields. A correctly guarded {@code stats()} implementation must
 * tolerate any interleaving of these two sequences without dereferencing a null
 * pointer or observing an inconsistent partially-shutdown state.
 *
 * <p><b>Why stress tier:</b> The race window is extremely narrow in single-threaded
 * code — unit tests cannot reliably trigger the interleaving. Hundreds of
 * enable/shutdown cycles combined with concurrent reader threads spinning on
 * {@code stats()} maximises the probability of hitting the window. The test
 * targets ~15–30 s on a modern laptop (1000 iterations × 4 readers × ~50 calls
 * per iteration = ~200 000 snapshot invocations under shutdown races).
 */
@Tag("stress")
class EvaluationServiceShutdownStress {

    /**
     * Number of complete enable-then-shutdown cycles. Each cycle creates a fresh
     * race window between the reader threads and the shutdown call.
     */
    private static final int STRESS_ITERATIONS = 1000;

    /**
     * Number of concurrent reader threads hammering {@code stats()} during each
     * shutdown cycle. Four readers spread across cores without dominating CPU.
     */
    private static final int READER_THREADS = 4;

    /**
     * Approximate number of {@code stats()} calls each reader attempts before
     * the shutdown signal is raised. A small loop prevents readers from advancing
     * so fast that the race window closes before they enter {@code stats()}.
     */
    private static final int CALLS_BEFORE_SHUTDOWN_SIGNAL = 10;

    /**
     * Verifies EVAL-010: {@code stats()} is safe to call concurrently with
     * {@code shutdown()} — no exception is ever thrown and every snapshot is
     * valid (non-null, all counters &gt;= 0).
     *
     * <p>Each iteration:
     * <ol>
     *   <li>Calls {@code enable()} on a fresh {@link EvaluationService}.</li>
     *   <li>Launches {@value #READER_THREADS} daemon reader threads that call
     *       {@code stats()} in a tight loop, stopping when the shutdown latch
     *       drops.</li>
     *   <li>Lets readers warm up with a small number of calls, then triggers
     *       {@code shutdown()} on the main thread — racing with the readers.</li>
     *   <li>Joins all readers.</li>
     *   <li>Asserts that no reader encountered any exception and every snapshot
     *       met the validity constraints.</li>
     * </ol>
     *
     * @throws InterruptedException if the test thread is interrupted while
     *                              joining reader threads
     */
    @Test
    void statsConcurrentWithShutdown() throws InterruptedException {
        // Shared exception sink — any reader that catches a Throwable appends it
        // here. Thread.UncaughtExceptionHandler below also feeds this queue so
        // exceptions that propagate out of the reader Runnable are captured.
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<String>();

        for (int iter = 0; iter < STRESS_ITERATIONS; iter++) {
            // Capture iter in a final local so it can be referenced inside
            // anonymous Runnable / UncaughtExceptionHandler (Java 8 requirement).
            final int currentIter = iter;

            // Build a lightweight mock graph that satisfies EvaluationService.enable()
            // without requiring MockBukkit or a real server. This mirrors the
            // pattern in EvaluationServiceLifecycleTest.
            BuildBattleAI plugin = mock(BuildBattleAI.class);
            PluginContext ctx = mock(PluginContext.class);
            when(plugin.getContext()).thenReturn(ctx);
            when(plugin.getPluginLogger()).thenReturn(mock(PluginLogger.class));

            BBAIConfigService cfg = mock(BBAIConfigService.class);
            // Empty YamlConfiguration → EvalConfig.fromYaml falls back to all defaults.
            when(cfg.config()).thenReturn(new YamlConfiguration());
            when(ctx.getConfigService()).thenReturn(cfg);
            when(ctx.getRenderService()).thenReturn(mock(RenderService.class));
            when(ctx.getMlService()).thenReturn(mock(BBAIMLService.class));

            BukkitScheduler sched = mock(BukkitScheduler.class);
            when(sched.runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong()))
                    .thenReturn(mock(BukkitTask.class));
            // metrics-log task is disabled by default (period=0 in EvalConfig defaults),
            // but stub anyway for safety.
            when(sched.runTaskTimerAsynchronously(any(), any(Runnable.class), anyLong(), anyLong()))
                    .thenReturn(mock(BukkitTask.class));

            final EvaluationService service;

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getScheduler).thenReturn(sched);

                service = new EvaluationService(plugin);
                service.enable();

                // shutdownSignal drops to zero when the main thread is about to call
                // shutdown(), giving readers a chance to be mid-stats() at that moment.
                CountDownLatch shutdownSignal = new CountDownLatch(1);
                // readersReady counts down as readers arrive; main thread waits for all.
                CountDownLatch readersReady = new CountDownLatch(READER_THREADS);
                // readersDone counts down when each reader exits; main thread joins via this.
                CountDownLatch readersDone = new CountDownLatch(READER_THREADS);
                // Set to true once shutdown() has returned, so readers stop looping.
                AtomicBoolean shutdownComplete = new AtomicBoolean(false);

                for (int r = 0; r < READER_THREADS; r++) {
                    final int readerIdx = r;
                    Thread reader = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            readersReady.countDown();
                            try {
                                // Warm-up calls before the shutdown race begins.
                                for (int i = 0; i < CALLS_BEFORE_SHUTDOWN_SIGNAL; i++) {
                                    EvaluationStats s = service.stats();
                                    validateSnapshot(s, currentIter, readerIdx, "pre-shutdown", failures);
                                }

                                // Signal that we are ready for the shutdown to be triggered.
                                shutdownSignal.countDown();

                                // Continue hammering stats() through and after shutdown.
                                while (!shutdownComplete.get()) {
                                    EvaluationStats s = service.stats();
                                    validateSnapshot(s, currentIter, readerIdx, "during-shutdown", failures);
                                }

                                // A few more calls after shutdown to confirm the post-shutdown
                                // zero snapshot is also valid.
                                for (int i = 0; i < CALLS_BEFORE_SHUTDOWN_SIGNAL; i++) {
                                    EvaluationStats s = service.stats();
                                    validateSnapshot(s, currentIter, readerIdx, "post-shutdown", failures);
                                }
                            } catch (Throwable t) {
                                failures.add("iter=" + currentIter + " reader=" + readerIdx
                                        + " EXCEPTION: " + t.getClass().getName() + ": " + t.getMessage());
                            } finally {
                                readersDone.countDown();
                            }
                        }
                    }, "stress-reader-" + readerIdx);
                    reader.setDaemon(true);
                    // Belt-and-suspenders: also catch exceptions that leak past the
                    // try/catch (e.g. unchecked from inside validateSnapshot itself).
                    reader.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                        @Override
                        public void uncaughtException(Thread t, Throwable e) {
                            failures.add("iter=" + currentIter + " UncaughtException in " + t.getName()
                                    + ": " + e.getClass().getName() + ": " + e.getMessage());
                        }
                    });
                    reader.start();
                }

                // Wait until all readers are up and have completed warm-up calls.
                boolean allReady = readersReady.await(10, TimeUnit.SECONDS);
                assertTrue(allReady, "Readers did not start within 10 s — iteration " + iter);

                // Wait until at least one reader has signalled it's about to enter the race.
                // Using await(1) rather than READER_THREADS so the signal fires as soon as
                // any single reader has reached the window — maximises overlap with shutdown.
                shutdownSignal.await(10, TimeUnit.SECONDS);

                // THE RACE: call shutdown() while readers are inside stats().
                service.shutdown();
                shutdownComplete.set(true);

                // Join all readers; they should exit promptly once shutdownComplete is set.
                boolean joinedOk = readersDone.await(30, TimeUnit.SECONDS);
                assertTrue(joinedOk, "Reader threads did not finish within 30 s — iteration " + iter);

                // Idempotent second shutdown must also never throw.
                try {
                    service.shutdown();
                } catch (Throwable t) {
                    failures.add("iter=" + iter + " second shutdown threw: "
                            + t.getClass().getName() + ": " + t.getMessage());
                }
            }

            // Fail fast after each iteration if any violation was observed — avoids
            // running 1000 iterations when the first already found a real bug.
            assertTrue(failures.isEmpty(),
                    "EVAL-010 violations detected:\n" + String.join("\n", failures));
        }

        // Final assertion covering all iterations.
        assertTrue(failures.isEmpty(),
                "EVAL-010 violations detected across " + STRESS_ITERATIONS + " iterations:\n"
                        + String.join("\n", failures));
    }

    /**
     * Validates a single {@link EvaluationStats} snapshot against the EVAL-010
     * invariants and appends a human-readable failure message to {@code failures}
     * if any constraint is violated.
     *
     * <p>Invariants checked:
     * <ul>
     *   <li>Snapshot is non-null.</li>
     *   <li>All long counters are &gt;= 0.</li>
     *   <li>All int queue/session/player fields are &gt;= 0.</li>
     *   <li>{@code batchSizeHistogram()} is non-null (even if empty).</li>
     * </ul>
     *
     * @param s        the snapshot to validate; may legally be a zero snapshot
     * @param iter     current stress iteration, for failure messages
     * @param reader   reader thread index, for failure messages
     * @param phase    "pre-shutdown", "during-shutdown", or "post-shutdown"
     * @param failures sink for failure descriptions
     */
    private static void validateSnapshot(EvaluationStats s, int iter, int reader,
                                         String phase,
                                         ConcurrentLinkedQueue<String> failures) {
        String prefix = "iter=" + iter + " reader=" + reader + " phase=" + phase + " ";

        if (s == null) {
            failures.add(prefix + "stats() returned null");
            return;
        }

        if (s.rendersCompleted() < 0)
            failures.add(prefix + "rendersCompleted=" + s.rendersCompleted() + " < 0");
        if (s.mlBatchesCompleted() < 0)
            failures.add(prefix + "mlBatchesCompleted=" + s.mlBatchesCompleted() + " < 0");
        if (s.matchesDispatched() < 0)
            failures.add(prefix + "matchesDispatched=" + s.matchesDispatched() + " < 0");
        if (s.droppedRenderJobs() < 0)
            failures.add(prefix + "droppedRenderJobs=" + s.droppedRenderJobs() + " < 0");
        if (s.droppedMlJobs() < 0)
            failures.add(prefix + "droppedMlJobs=" + s.droppedMlJobs() + " < 0");
        if (s.renderErrors() < 0)
            failures.add(prefix + "renderErrors=" + s.renderErrors() + " < 0");
        if (s.mlErrors() < 0)
            failures.add(prefix + "mlErrors=" + s.mlErrors() + " < 0");
        if (s.renderLatencyAvgMicros() < 0)
            failures.add(prefix + "renderLatencyAvgMicros=" + s.renderLatencyAvgMicros() + " < 0");
        if (s.mlLatencyAvgMicros() < 0)
            failures.add(prefix + "mlLatencyAvgMicros=" + s.mlLatencyAvgMicros() + " < 0");
        if (s.renderQueueDepth() < 0)
            failures.add(prefix + "renderQueueDepth=" + s.renderQueueDepth() + " < 0");
        if (s.mlQueueDepth() < 0)
            failures.add(prefix + "mlQueueDepth=" + s.mlQueueDepth() + " < 0");
        if (s.registeredSessions() < 0)
            failures.add(prefix + "registeredSessions=" + s.registeredSessions() + " < 0");
        if (s.activePlayers() < 0)
            failures.add(prefix + "activePlayers=" + s.activePlayers() + " < 0");

        // batchSizeHistogram() returns a defensive clone — never null.
        if (s.batchSizeHistogram() == null)
            failures.add(prefix + "batchSizeHistogram() returned null");
    }
}
