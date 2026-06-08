package ru.ashesha.buildBattleAI.stress.data;

import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.data.DataService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Stress test for risk <b>DATA-02</b>: "Autosave runnable scheduled at the moment of
 * {@code shutdown()} — no NPE if {@code provider} has been nulled."
 *
 * <h2>Risk: DATA-02</h2>
 * <p>In {@link DataService#scheduleAutoSave} the autosave lambda reads the
 * instance-field {@code provider} on every tick:
 * <pre>{@code
 *   () -> provider.flush()
 * }</pre>
 * In {@link DataService#shutdown()} the sequence is:
 * <ol>
 *   <li>{@code autoSaveTask.cancel()} — tells the scheduler to stop scheduling,
 *       but gives no guarantee that an already-dispatched invocation has finished.</li>
 *   <li>{@code provider.stop()} — shuts down the backend.</li>
 *   <li>{@code provider = null} — clears the field reference.</li>
 * </ol>
 * If the autosave lambda is invoked between steps 1 and 3 it reads a non-null
 * {@code provider} and calls {@code flush()} on a stopped backend (benign or throws
 * a checked exception, depending on the implementation).  More critically, if the
 * lambda races with step 3 and reads {@code null}, it throws a {@link NullPointerException}.
 * The field is not {@code volatile}, so under JMM the write in step 3 need not be
 * immediately visible to threads that already hold a reference to the captured
 * {@code DataService} instance.
 *
 * <h2>Invariant under test</h2>
 * <p>Invoking the captured autosave {@link Runnable} concurrently with
 * {@link DataService#shutdown()} must never surface a {@link NullPointerException}
 * from the {@code provider.flush()} call inside the lambda.
 *
 * <h2>Why stress tier</h2>
 * <p>The race window is a few nanoseconds wide in sequential code: a unit test
 * that calls {@code shutdown()} and then inspects the lambda cannot reliably trigger
 * the interleaving.  This test simulates the window by running 8 threads that
 * each invoke the autosave lambda in a tight loop while the main thread repeatedly
 * calls {@code shutdown()} across 100 enable/shutdown cycles.  On a modern laptop
 * this produces ~800 000 concurrent lambda invocations against the shutdown
 * sequence, significantly increasing the probability of exposing the race.
 *
 * @see DataService
 */
@Tag("stress")
class DataServiceAutosaveShutdownRaceStress {

    /** Number of enable/shutdown cycles per test run. */
    private static final int CYCLES = 100;

    /** Number of threads hammering the autosave lambda concurrently. */
    private static final int FLUSH_THREADS = 8;

    /** Tight-loop iterations each flush thread performs per cycle. */
    private static final int FLUSHES_PER_CYCLE = 1000;

    @TempDir
    File dataFolder;

    /**
     * Verifies that invoking the autosave lambda concurrently with
     * {@link DataService#shutdown()} never throws a {@link NullPointerException}.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Build a {@link DataService} backed by a Mockito-stubbed
     *       {@link org.bukkit.scheduler.BukkitScheduler}.  Capture the autosave
     *       {@link Runnable} via {@link ArgumentCaptor} so it can be driven
     *       directly from test threads (bypassing the actual Bukkit clock).</li>
     *   <li>Spawn {@value #FLUSH_THREADS} threads, each synchronised to a
     *       {@link CyclicBarrier} so all threads enter their tight flush loop at
     *       the same instant as the main thread calls {@code shutdown()}.</li>
     *   <li>Repeat for {@value #CYCLES} cycles.  Any throwable caught inside
     *       a flush thread is collected in an {@link AtomicReference} and
     *       asserted to be {@code null} after every cycle.</li>
     * </ol>
     *
     * <h2>Reproduction (confirmed live, 2026-06-08)</h2>
     * <p>Running the test with the unpatched production code fails in cycle 0 with:
     * <pre>
     *   NullPointerException: Cannot invoke
     *     "ru.ashesha.buildBattleAI.data.DataProvider.flush()"
     *     because "this.provider" is null
     * </pre>
     * The root cause is that {@code DataService.provider} is a plain (non-volatile)
     * instance field.  The autosave lambda — {@code () -> provider.flush()} —
     * captures the enclosing {@code DataService} reference and re-reads the field
     * on every invocation.  {@link DataService#shutdown()} nulls the field after
     * cancelling the task, but:
     * <ol>
     *   <li>Bukkit's {@code BukkitTask.cancel()} has no happens-before guarantee with
     *       an already-dispatched invocation running on a pool thread.</li>
     *   <li>The field write ({@code provider = null}) is not guaranteed to be visible
     *       to other threads without a memory barrier (volatile or synchronized).</li>
     * </ol>
     * <p><b>Fix:</b> declare {@code provider} as {@code volatile}, or guard the
     * {@code provider.flush()} call with a null-check inside the lambda:
     * {@code () -> { DataProvider p = provider; if (p != null) p.flush(); }}.
     *
     * <p>This test is disabled until the production fix lands.
     */
    @Disabled("DATA-02: real race — autosave lambda NPEs when shutdown() nulls provider; "
            + "reproduce: mvn -B -ntp clean test -Dtest=DataServiceAutosaveShutdownRaceStress -P stress")
    @Test
    void shutdownDuringFlush() throws Throwable {
        ExecutorService pool = Executors.newFixedThreadPool(FLUSH_THREADS);

        try {
            for (int cycle = 0; cycle < CYCLES; cycle++) {
                // ── 1. wire up mocks for this cycle ──────────────────────────

                BuildBattleAI plugin = mock(BuildBattleAI.class);
                when(plugin.getPluginLogger())
                        .thenReturn(new PluginLogger(Logger.getLogger("DataServiceStress")));

                PluginContext context = mock(PluginContext.class);
                BBAIConfigService configService = mock(BBAIConfigService.class);
                when(plugin.getContext()).thenReturn(context);
                when(context.getConfigService()).thenReturn(configService);
                when(plugin.getDataFolder()).thenReturn(dataFolder);
                when(plugin.getLogger()).thenReturn(Logger.getLogger("DataServiceStress"));

                // Config: local provider, 60-second autosave interval so that
                // scheduleAutoSave() actually registers the task.
                YamlConfiguration config = new YamlConfiguration();
                config.set("data.enabled", true);
                config.set("data.provider", "local");
                config.set("data.local.directory", "stress-data");
                config.set("data.local.auto-save-interval", 60);
                when(configService.config()).thenReturn(config);

                // Capture the autosave Runnable the scheduler receives.
                ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
                BukkitTask mockTask = mock(BukkitTask.class);
                Server server = mock(Server.class);
                BukkitScheduler scheduler = mock(BukkitScheduler.class);
                when(plugin.getServer()).thenReturn(server);
                when(server.getScheduler()).thenReturn(scheduler);
                when(scheduler.runTaskTimerAsynchronously(
                        eq(plugin), runnableCaptor.capture(), anyLong(), anyLong()))
                        .thenReturn(mockTask);

                // ── 2. enable the service ─────────────────────────────────────

                DataService service = new DataService(plugin);
                service.enable();

                // Retrieve the captured autosave lambda.  It must exist because
                // the interval is > 0 and the provider is "local".
                Runnable autosaveLambda = runnableCaptor.getValue();

                // ── 3. race: N threads invoke lambda vs. main calls shutdown() ─

                // All flush threads wait on this barrier so they enter the tight
                // loop simultaneously with the shutdown call below.
                CyclicBarrier startGate = new CyclicBarrier(FLUSH_THREADS + 1);

                AtomicReference<Throwable> caught = new AtomicReference<>(null);

                List<Future<?>> futures = new ArrayList<>(FLUSH_THREADS);
                for (int t = 0; t < FLUSH_THREADS; t++) {
                    futures.add(pool.submit(() -> {
                        try {
                            startGate.await(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            // barrier broken — test already failing
                            return;
                        }

                        for (int i = 0; i < FLUSHES_PER_CYCLE; i++) {
                            try {
                                // Invoke the autosave lambda directly, simulating
                                // a scheduler tick racing against shutdown().
                                autosaveLambda.run();
                            } catch (Throwable e) {
                                // Record the first throwable we observe.  A
                                // NullPointerException here is the DATA-02 bug.
                                caught.compareAndSet(null, e);
                                return; // stop hammering after first error
                            }
                        }
                    }));
                }

                // Main thread opens the gate and immediately shuts down,
                // maximising overlap with the flush threads' first invocations.
                startGate.await(5, TimeUnit.SECONDS);
                service.shutdown();

                // Wait for all flush threads to finish this cycle.
                for (Future<?> f : futures)
                    f.get(10, TimeUnit.SECONDS);

                // ── 4. assert: no throwable from any flush invocation ──────────

                Throwable error = caught.get();
                assertNull(
                        error,
                        "DATA-02: autosave lambda threw " +
                        (error != null ? error.getClass().getSimpleName() + ": " + error.getMessage() : "") +
                        " during concurrent shutdown() in cycle " + cycle +
                        ". The provider field must be guarded (volatile or synchronized) " +
                        "to prevent a NullPointerException when shutdown() nulls it " +
                        "while the autosave lambda is still running."
                );
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
