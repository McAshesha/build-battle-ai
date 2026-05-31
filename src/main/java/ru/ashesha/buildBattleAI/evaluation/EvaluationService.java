package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.evaluation.api.BBAIEvaluationService;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats;
import ru.ashesha.buildBattleAI.game.GameSession;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Default implementation of {@link BBAIEvaluationService}. Owns the
 * session registry, bounded queues, render workers, ML coalescer, the
 * Bukkit-scheduled coordinator, and the metrics counter set. Lifecycle
 * is idempotent.
 */
public class EvaluationService implements PluginService, BBAIEvaluationService {

    private final BuildBattleAI plugin;
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    private EvalConfig config;
    private EvaluationMetrics metrics;
    private RenderQueue renderQueue;
    private MlQueue mlQueue;
    private EvaluationCoordinator coordinator;
    private ConcurrentHashMap<String, SessionHandle> registry;
    private List<Thread> renderThreads;
    private List<RenderWorker> renderWorkers;
    private Thread mlThread;
    private MlCoalescerWorker mlWorker;
    private BukkitTask coordinatorTask;

    public EvaluationService(@NonNull BuildBattleAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        if (!enabled.compareAndSet(false, true))
            return;

        PluginLogger logger = plugin.getPluginLogger();
        config = EvalConfig.fromYaml(plugin.getContext().getConfigService().config());
        metrics = new EvaluationMetrics(config.mlBatchMaxSize());
        renderQueue = new RenderQueue(config.renderQueueCapacity());
        mlQueue = new MlQueue(config.mlQueueCapacity());
        registry = new ConcurrentHashMap<String, SessionHandle>();

        coordinator = new EvaluationCoordinator(registry, renderQueue, metrics, config.minCadenceMs());

        renderWorkers = new ArrayList<RenderWorker>(config.renderWorkers());
        renderThreads = new ArrayList<Thread>(config.renderWorkers());
        for (int i = 0; i < config.renderWorkers(); i++) {
            RenderWorker w = new RenderWorker(i, renderQueue, mlQueue,
                    plugin.getContext().getRenderService(), metrics, logger);
            Thread t = new Thread(w, "bbai-eval-render-" + i);
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, ex) ->
                    logger.error("Render worker " + thread.getName() + " died: " + ex));
            renderWorkers.add(w);
            renderThreads.add(t);
            t.start();
        }

        mlWorker = new MlCoalescerWorker(mlQueue,
                plugin.getContext().getMlService(),
                arenaName -> {
                    SessionHandle h = registry.get(arenaName);
                    return h == null ? null : h.scoreCallback();
                },
                r -> Bukkit.getScheduler().runTask(plugin, r),
                metrics, logger,
                config.mlBatchMaxSize(),
                config.mlBatchMaxWaitMs(),
                config.mlTopK());
        mlThread = new Thread(mlWorker, "bbai-eval-ml");
        mlThread.setDaemon(true);
        mlThread.setUncaughtExceptionHandler((thread, ex) ->
                logger.error("ML coalescer died: " + ex));
        mlThread.start();

        coordinatorTask = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> coordinator.tick(System.nanoTime()),
                config.coordinatorTickPeriod(), config.coordinatorTickPeriod());

        logger.info("EvaluationService enabled (render-workers=" + config.renderWorkers()
                + ", ml-batch-max=" + config.mlBatchMaxSize()
                + ", cadence-ms=" + config.minCadenceMs() + ")");
    }

    @Override
    public void shutdown() {
        if (!enabled.compareAndSet(true, false))
            return;

        if (coordinatorTask != null) {
            coordinatorTask.cancel();
            coordinatorTask = null;
        }

        if (renderWorkers != null)
            for (RenderWorker w : renderWorkers)
                w.stop();
        if (mlWorker != null)
            mlWorker.stop();

        if (renderThreads != null)
            for (Thread t : renderThreads) {
                t.interrupt();
                joinQuietly(t, 5000L);
            }
        if (mlThread != null) {
            mlThread.interrupt();
            joinQuietly(mlThread, 5000L);
        }

        if (renderQueue != null)
            renderQueue.clear();
        if (mlQueue != null)
            mlQueue.clear();
        if (registry != null)
            registry.clear();

        renderWorkers = null;
        renderThreads = null;
        mlWorker = null;
        mlThread = null;
        renderQueue = null;
        mlQueue = null;
        registry = null;
        coordinator = null;
        metrics = null;
        config = null;
    }

    @Override
    public void registerSession(@NonNull GameSession session,
                                @NonNull BiConsumer<UUID, Integer> scoreCallback) {
        if (!enabled.get())
            throw new IllegalStateException("EvaluationService is not enabled");
        registry.put(session.arena().name(), new SessionHandle(session, scoreCallback));
    }

    @Override
    public void unregisterSession(@NonNull String arenaName) {
        if (!enabled.get())
            return;
        registry.remove(arenaName);
    }

    @Override
    public @NonNull EvaluationStats stats() {
        if (!enabled.get())
            return new EvaluationStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, new long[0]);
        int players = 0;
        for (SessionHandle h : registry.values())
            players += h.session().players().size();
        return metrics.snapshot(renderQueue.size(), mlQueue.size(), registry.size(), players);
    }

    private static void joinQuietly(Thread t, long millis) {
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
