package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationCallback;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;
import ru.ashesha.buildBattleAI.ml.api.PredictionResult;
import ru.ashesha.buildBattleAI.ml.api.TopKEntry;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * ML-stage worker. Drains a batch from the {@link MlQueue} (size K or
 * wait T ms), runs {@link BBAIMLService#predictBatchRgb} once across the
 * whole batch, and dispatches the per-arena {@link EvaluationCallback}
 * onto the Bukkit main thread for <i>every</i> frame — not only matches.
 * Match detection is delegated to the callback recipient (it inspects the
 * {@code matched} flag and the {@code topK} list).
 * <p>
 * Single-threaded by design — the ONNX session is concurrency-safe, but
 * keeping batch assembly serial removes a class of synchronization bugs
 * and guarantees deterministic batch sizes.
 * <p>
 * The callback registry is queried per-batch — when an arena has been
 * unregistered between {@code enqueue} and {@code drain}, the lookup
 * returns {@code null} and the frame is silently skipped (no dispatch,
 * no score). The ML batch itself is still counted as completed so error
 * accounting reflects real ONNX activity, not callback availability.
 */
final class MlCoalescerWorker implements Runnable {

    /**
     * Abstraction over {@code Bukkit.getScheduler().runTask(plugin, r)} so
     * the worker can be unit-tested without MockBukkit. Production wiring
     * passes a Bukkit-backed implementation; tests inject a synchronous
     * dispatcher that runs the callback inline.
     */
    interface MainThreadDispatcher {
        /**
         * Schedules the given {@link Runnable} to execute on the Bukkit
         * main thread on the next server tick.
         *
         * @param r the callback to dispatch; never {@code null}
         */
        void dispatch(@NonNull Runnable r);
    }

    private final MlQueue mlQueue;
    private final BBAIMLService mlService;
    private final Function<String, EvaluationCallback> callbackRegistry;
    private final MainThreadDispatcher dispatcher;
    private final EvaluationMetrics metrics;
    private final PluginLogger logger;
    private final int maxBatchSize;
    private final long waitMs;
    private final int topK;

    /**
     * Cooperative cancellation flag. Set by {@link #stop()} and observed
     * at each iteration boundary. {@code volatile} so the worker thread
     * sees the most recent write without a memory barrier on every check.
     */
    private volatile boolean running = true;

    MlCoalescerWorker(@NonNull MlQueue mlQueue,
                      @NonNull BBAIMLService mlService,
                      @NonNull Function<String, EvaluationCallback> callbackRegistry,
                      @NonNull MainThreadDispatcher dispatcher,
                      @NonNull EvaluationMetrics metrics,
                      @NonNull PluginLogger logger,
                      int maxBatchSize, long waitMs, int topK) {
        this.mlQueue = mlQueue;
        this.mlService = mlService;
        this.callbackRegistry = callbackRegistry;
        this.dispatcher = dispatcher;
        this.metrics = metrics;
        this.logger = logger;
        this.maxBatchSize = maxBatchSize;
        this.waitMs = waitMs;
        this.topK = topK;
    }

    /**
     * Signals the worker to exit at its next iteration boundary. The
     * caller is responsible for interrupting the worker thread to
     * unblock it from {@link MlQueue#drainBatch(int, long)} in addition
     * to calling this method.
     */
    void stop() {
        running = false;
    }

    /**
     * Worker loop. Renames the current thread for log diagnostics, then
     * repeatedly drains a batch, runs ML inference, and dispatches the
     * per-arena callback for each frame with the full top-K ranking and
     * a match flag. Errors are counted and the worker survives any
     * single bad batch so a transient ONNX failure does not stall the
     * pipeline.
     */
    @Override
    public void run() {
        Thread.currentThread().setName("bbai-eval-ml");
        while (running) {
            List<EvalFrame> batch;
            try {
                batch = mlQueue.drainBatch(maxBatchSize, waitMs);
            } catch (InterruptedException e) {
                // Shutdown path: stop() was called and the controller
                // interrupted us to unblock the drain. Re-assert the
                // interrupt flag for any caller that may inspect it
                // after we exit; otherwise spin one more iteration so
                // the while-check sees the running=false write.
                if (!running)
                    return;
                Thread.currentThread().interrupt();
                continue;
            }
            if (batch.isEmpty())
                continue;

            // Pack RGB buffers in queue order; the ML service preserves
            // ordering 1:1 so results[i] corresponds to batch.get(i).
            byte[][] rgbs = new byte[batch.size()][];
            for (int i = 0; i < batch.size(); i++)
                rgbs[i] = batch.get(i).rgb();

            PredictionResult[] results;
            long t0 = System.nanoTime();
            try {
                results = mlService.predictBatchRgb(rgbs, 224, 224, topK);
            } catch (Exception e) {
                // Defensive nesting: a throwing logger or metrics impl
                // must NOT kill the worker thread.
                try {
                    logger.debug("ML batch (size %d) failed: %s", batch.size(), e.getMessage());
                    metrics.incMlErrors();
                } catch (Exception suppressed) {
                    // Worker survival is mandatory; swallow.
                }
                continue;
            }
            metrics.recordMlLatencyNanos(System.nanoTime() - t0);
            metrics.incMlBatchesCompleted();
            metrics.recordBatchSize(batch.size());

            for (int i = 0; i < batch.size(); i++) {
                EvalFrame frame = batch.get(i);
                PredictionResult r = results[i];
                // Look up the arena's callback lazily: arenas that have
                // been unregistered between enqueue and drain return null here.
                EvaluationCallback cb = callbackRegistry.apply(frame.job().arenaName());
                if (cb == null)
                    continue;

                final boolean matched = themeMatched(r, frame.job().expectedTheme());
                final UUID pid = frame.job().playerId();
                final int themeIndex = frame.job().themeIndex();
                // r.topK() is the immutable list produced by PredictionResult —
                // safe to retain and forward across the dispatch boundary.
                final List<TopKEntry> topKList = r.topK();
                dispatcher.dispatch(() -> cb.onEvaluated(pid, themeIndex, topKList, matched));
                if (matched)
                    metrics.incMatchesDispatched();
            }
        }
    }

    /**
     * Returns {@code true} when {@code result.topK()} contains an entry
     * whose class name matches {@code expectedTheme} case-insensitively.
     * The class-name comparison must be case-insensitive because theme
     * strings round-trip through arena config (mixed-case) while the
     * model emits class names in the centroids file's canonical form.
     */
    private boolean themeMatched(PredictionResult result, String expectedTheme) {
        for (TopKEntry e : result.topK())
            if (e.className().equalsIgnoreCase(expectedTheme))
                return true;
        return false;
    }
}
