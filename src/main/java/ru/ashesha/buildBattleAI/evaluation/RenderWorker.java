package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.render.RenderService;

import java.util.concurrent.locks.Lock;

/**
 * Render-stage worker. Pulls {@link EvalJob}s from the {@link RenderQueue},
 * renders each one under the mirror's read-lock, and pushes the resulting
 * RGB buffer into the {@link MlQueue} as an {@link EvalFrame}.
 * <p>
 * Exceptions raised by {@link RenderService#render} are swallowed and
 * counted via {@link EvaluationMetrics#incRenderErrors()} — the worker
 * must survive a single bad job so a transient render failure for one
 * player does not stall the entire pipeline. The outer loop only exits
 * when {@link #stop()} has been called and the worker thread is
 * interrupted out of its blocking {@link RenderQueue#take()}.
 */
final class RenderWorker implements Runnable {

    private final int workerId;
    private final RenderQueue renderQueue;
    private final MlQueue mlQueue;
    private final RenderService renderService;
    private final EvaluationMetrics metrics;
    private final PluginLogger logger;

    /**
     * Cooperative cancellation flag. Set by {@link #stop()} and observed
     * at each iteration boundary. {@code volatile} so the worker thread
     * sees the most recent write without a memory barrier on every check.
     */
    private volatile boolean running = true;

    RenderWorker(int workerId,
                 @NonNull RenderQueue renderQueue,
                 @NonNull MlQueue mlQueue,
                 @NonNull RenderService renderService,
                 @NonNull EvaluationMetrics metrics,
                 @NonNull PluginLogger logger) {
        this.workerId = workerId;
        this.renderQueue = renderQueue;
        this.mlQueue = mlQueue;
        this.renderService = renderService;
        this.metrics = metrics;
        this.logger = logger;
    }

    /**
     * Signals the worker to exit at its next iteration boundary. The
     * caller is responsible for interrupting the worker thread to
     * unblock it from {@link RenderQueue#take()} in addition to calling
     * this method.
     */
    void stop() {
        running = false;
    }

    /**
     * Worker loop. Renames the current thread for log diagnostics,
     * then repeatedly:
     * <ol>
     *   <li>Blocks on {@link RenderQueue#take()} for the next job.</li>
     *   <li>Acquires the mirror's read-lock and invokes
     *       {@link RenderService#render}.</li>
     *   <li>On success, records render latency and offers a fresh
     *       {@link EvalFrame} to the {@link MlQueue}; dropped offers
     *       (queue full) increment the ML-drop counter.</li>
     *   <li>On exception, logs at debug, increments the render-error
     *       counter, and continues with the next job.</li>
     * </ol>
     * The read-lock is released in the {@code finally} block on both
     * the happy and exception paths — Java's specification guarantees
     * {@code finally} executes before {@code continue} takes effect, so
     * the lock is correctly released before the loop iteration restarts.
     */
    @Override
    public void run() {
        Thread.currentThread().setName("bbai-eval-render-" + workerId);
        while (running) {
            EvalJob job;
            try {
                job = renderQueue.take();
            } catch (InterruptedException e) {
                // Shutdown path: stop() was called and the controller
                // interrupted us to unblock the take(). Re-assert the
                // interrupt flag for any caller that may inspect it
                // after we exit; otherwise spin one more iteration so
                // the while-check sees the running=false write.
                if (!running)
                    return;
                Thread.currentThread().interrupt();
                continue;
            }

            long t0 = System.nanoTime();
            byte[] rgb;
            Lock readLock = job.mirror().readLock();
            readLock.lock();
            try {
                rgb = renderService.render(
                        job.mirror(),
                        job.cameraX(), job.cameraY(), job.cameraZ(),
                        job.cameraYaw(), job.cameraPitch());
            } catch (Exception e) {
                // Swallow + count: a single bad job must not stall the
                // pipeline. The lock is released by the finally block
                // before this continue jumps back to the loop header.
                logger.debug("Render failed for %s: %s", job.playerName(), e.getMessage());
                metrics.incRenderErrors();
                continue;
            } finally {
                readLock.unlock();
            }
            metrics.recordRenderLatencyNanos(System.nanoTime() - t0);
            metrics.incRendersCompleted();

            EvalFrame frame = new EvalFrame(job, rgb, System.nanoTime());
            if (!mlQueue.offer(frame))
                metrics.incDroppedMlJobs();
        }
    }
}
