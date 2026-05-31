# Evaluation Pipeline — Design Spec

**Status:** Approved (2026-05-31)
**Author:** brainstorm with user

## Problem

Render + ML inference today runs via a per-arena `runTaskTimer` at 100 ticks (5 s) in
`GameManager.startRenderTimer` (`GameManager.java:435`). With multiple active arenas, the
timers can synchronise — one Bukkit tick out of every 100 carries a peak render/ML load,
the other 99 are idle. This:

- doesn't scale with arena count — pile-ups grow linearly;
- leaves ONNX batching capacity unused (currently one `predictRgb` per player per tick);
- offers no backpressure or admission control under sustained overload;
- makes per-player cadence brittle (each arena's timer is independent — no global view).

## Goals

- Smooth render + ML load across time; no bursty peaks.
- Use ML batching (`MLService.predictBatchRgb`) for throughput.
- Soft per-player evaluation cadence: target ~5 s, may stretch to ~10 s under load.
- Centralised, observable metrics for debugging.
- Preserve all existing safety invariants: `MutablePlotScene` single-writer contract,
  no main-thread blocking on rendering or ML.

## Non-goals

- Enabling TTA by default. Production today calls `predictRgb(..., topK=2)` without TTA;
  we keep that.
- GPU-aware adaptive scheduling.
- Hard latency guarantees (e.g. "every player gets ≤5 s no matter what").
- Multi-JVM coordination.

## Architecture

```
                       ┌────────────────────────────────────┐
                       │        EvaluationService           │
                       │                                    │
GameManager ─register─▶│  Map<arenaName, SessionHandle>     │
                       │                                    │
                       │  ┌───────────────────────────────┐ │
                       │  │ Coordinator                   │ │
Bukkit main ───tick───▶│  │ (Bukkit runTaskTimer,         │ │
  every 5 ticks        │  │  period = 5 ticks = 250 ms)   │ │
                       │  │  • scan registered sessions   │ │
                       │  │  • pick dirty players where   │ │
                       │  │    now-lastEvalAt ≥ minCadence│ │
                       │  │  • clearZoneDirty (under main)│ │
                       │  │  • advance session camera idx │ │
                       │  │  • offer() to RenderQueue     │ │
                       │  └────────┬──────────────────────┘ │
                       │           │                        │
                       │           ▼                        │
                       │  ┌───────────────────────────────┐ │
                       │  │ RenderQueue                   │ │
                       │  │ • LinkedBlockingQueue<EvalJob>│ │
                       │  │ • dedup map UUID → EvalJob    │ │
                       │  │ • bounded (capacity 64)       │ │
                       │  └────────┬──────────────────────┘ │
                       │           │ take()                 │
                       │           ▼                        │
                       │  ┌───────────────────────────────┐ │
                       │  │ RenderWorker × N (default 1)  │ │
                       │  │ • mirror.readLock()           │ │
                       │  │ • RenderService.render(...)   │ │
                       │  │ • emit EvalFrame to MlQueue   │ │
                       │  └────────┬──────────────────────┘ │
                       │           │                        │
                       │           ▼                        │
                       │  ┌───────────────────────────────┐ │
                       │  │ MlQueue                       │ │
                       │  │ • LinkedBlockingQueue<Frame>  │ │
                       │  │ • drainBatch(K, T)            │ │
                       │  └────────┬──────────────────────┘ │
                       │           │                        │
                       │           ▼                        │
                       │  ┌───────────────────────────────┐ │
                       │  │ MlCoalescerWorker (1)         │ │
                       │  │ • drain up to K or wait T ms  │ │
                       │  │ • MLService.predictBatchRgb   │ │
                       │  │ • for matches: runTask(score) │ │
                       │  └───────────────────────────────┘ │
                       │                                    │
                       │  EvaluationMetrics (atomic)        │
                       └────────────────────────────────────┘
```

## Components

### `EvaluationService` (public, `PluginService`)

Top-level service. Owns the coordinator task, queues, worker threads, metrics, and the
session registry. Lifecycle:

- `enable()`: reads `evaluation.*` from `config.yml`, builds queues + metrics, starts
  worker threads (daemon, named), schedules coordinator on Bukkit scheduler.
- `shutdown()`: cancels Bukkit task, sets `running=false`, interrupts workers, joins
  with 5 s timeout each, drops queues, clears registry.
- `reload()`: default `PluginService` implementation (shutdown + enable).

Public API (interface `BBAIEvaluationService` in `evaluation/api/`):

```java
void registerSession(GameSession session, BiConsumer<UUID, Integer> scoreCallback);
void unregisterSession(String arenaName);
EvaluationStats stats();
```

The `scoreCallback` receives `(playerId, themeIndex)` for each successful match. The
service marshals it onto Bukkit's main thread before invocation.

### `EvaluationCoordinator`

Pure logic, no Bukkit dependency in its core method. Runs as a Bukkit-scheduled
`Runnable` (the wiring lives in `EvaluationService`). On each tick:

```
for handle in sessionRegistry.values():
    if handle.session.state() != PLAYING: continue
    cameraIdx = handle.currentCameraIndex
    handle.advanceCamera()
    for gp in handle.session.players().values():
        if !gp.zoneDirty(): continue
        if now - handle.lastEvalAt(gp.playerId()) < minCadenceMs: continue
        mirror = handle.session.mirror(gp.plotIndex())
        if mirror == null: continue
        camera = handle.session.arena().plots().get(gp.plotIndex()).cameras().get(cameraIdx % size)
        gp.clearZoneDirty()  // eager — same as today
        job = EvalJob.of(handle.session.arena().name(), gp, mirror, camera, nowNanos)
        if !renderQueue.offer(job):
            metrics.incDroppedRenderJobs()
            // player remains "due" — dirty will be re-flipped on next block change, or
            // re-considered next tick anyway because we don't update lastEvalAt
        else:
            handle.recordEvalAttempt(gp.playerId(), nowNanos)
```

Notes:
- Coordinator runs on main thread → reading `gp.zoneDirty()` and `session.players()` is
  safe without locks.
- Eager `clearZoneDirty()` matches today's semantics (`GameManager.java:471`): writes
  during the in-flight render land in the next eval cycle, not double-counted.
- `lastEvalAt` is set on enqueue (not completion). If we waited until completion the
  player could be re-enqueued while a job is in flight, causing thrashing under load.

### `RenderQueue`

Bounded queue with per-player deduplication.

```java
// pseudocode
class RenderQueue {
    LinkedBlockingQueue<EvalJob> queue;          // FIFO fairness
    ConcurrentHashMap<UUID, EvalJob> pending;    // dedup index

    boolean offer(EvalJob job) {
        EvalJob prev = pending.put(job.playerId(), job);
        if (prev != null) prev.markStale();      // worker will skip
        if (!queue.offer(job)) {
            pending.remove(job.playerId(), job);
            return false;
        }
        return true;
    }

    EvalJob take() throws InterruptedException {
        while (true) {
            EvalJob j = queue.take();
            if (!j.isStale()) {
                pending.remove(j.playerId(), j);
                return j;
            }
            // stale: dropped, loop and take the next
        }
    }
}
```

`offer()` is non-blocking (called from main-thread coordinator).
`take()` blocks (called from render worker).

### `MlQueue`

Bounded queue with timed batched drain.

```java
class MlQueue {
    LinkedBlockingQueue<EvalFrame> queue;

    boolean offer(EvalFrame f) { return queue.offer(f); }

    List<EvalFrame> drainBatch(int max, long waitMs) throws InterruptedException {
        EvalFrame first = queue.poll(waitMs, MILLISECONDS);
        if (first == null) return Collections.emptyList();
        List<EvalFrame> batch = new ArrayList<>(max);
        batch.add(first);
        queue.drainTo(batch, max - 1);
        return batch;
    }
}
```

The first `poll(waitMs, ...)` blocks until either an item arrives or the wait window
expires. Once we have one, we drain whatever else is already present, up to `max`. This
gives us "fill the batch quickly when load is high, don't wait forever when load is low".

### `RenderWorker`

```java
class RenderWorker implements Runnable {
    public void run() {
        Thread.currentThread().setName("bbai-eval-render-" + id);
        while (running) {
            EvalJob job;
            try { job = renderQueue.take(); }
            catch (InterruptedException e) { if (!running) return; continue; }

            long t0 = System.nanoTime();
            byte[] rgb;
            Lock lock = job.mirror().readLock();
            lock.lock();
            try {
                rgb = renderService.render(job.mirror(),
                        job.cameraX(), job.cameraY(), job.cameraZ(),
                        job.cameraYaw(), job.cameraPitch());
            } catch (Exception e) {
                logger.debug("Render failed for %s: %s", job.playerName(), e.getMessage());
                metrics.incRenderErrors();
                continue;
            } finally {
                lock.unlock();
            }
            metrics.recordRenderLatency(System.nanoTime() - t0);
            metrics.incRendersCompleted();

            EvalFrame frame = new EvalFrame(job, rgb, System.nanoTime());
            if (!mlQueue.offer(frame))
                metrics.incDroppedMlJobs();
        }
    }
}
```

### `MlCoalescerWorker`

```java
class MlCoalescerWorker implements Runnable {
    public void run() {
        Thread.currentThread().setName("bbai-eval-ml");
        while (running) {
            List<EvalFrame> batch;
            try { batch = mlQueue.drainBatch(maxSize, maxWaitMs); }
            catch (InterruptedException e) { if (!running) return; continue; }
            if (batch.isEmpty()) continue;

            byte[][] rgbs = new byte[batch.size()][];
            for (int i = 0; i < batch.size(); i++) rgbs[i] = batch.get(i).rgb();

            PredictionResult[] results;
            long t0 = System.nanoTime();
            try {
                results = mlService.predictBatchRgb(rgbs, 224, 224, topK);
            } catch (Exception e) {
                logger.debug("ML batch (size %d) failed: %s", batch.size(), e.getMessage());
                metrics.incMlErrors();
                continue;
            }
            metrics.recordMlLatency(System.nanoTime() - t0);
            metrics.incMlBatchesCompleted();
            metrics.recordBatchSize(batch.size());

            for (int i = 0; i < batch.size(); i++) {
                EvalFrame frame = batch.get(i);
                PredictionResult r = results[i];
                if (matches(r, frame.job().expectedTheme())) {
                    BiConsumer<UUID, Integer> cb = registry.callback(frame.job().arenaName());
                    if (cb != null) {
                        UUID pid = frame.job().playerId();
                        int ti = frame.job().themeIndex();
                        Bukkit.getScheduler().runTask(plugin, () -> cb.accept(pid, ti));
                        metrics.incMatchesDispatched();
                    }
                }
            }
        }
    }

    private boolean matches(PredictionResult r, String expectedTheme) {
        for (TopKEntry e : r.topK())
            if (e.className().equalsIgnoreCase(expectedTheme)) return true;
        return false;
    }
}
```

### `EvaluationMetrics`

```java
final class EvaluationMetrics {
    final LongAdder rendersCompleted   = new LongAdder();
    final LongAdder mlBatchesCompleted = new LongAdder();
    final LongAdder matchesDispatched  = new LongAdder();
    final LongAdder droppedRenderJobs  = new LongAdder();
    final LongAdder droppedMlJobs      = new LongAdder();
    final LongAdder renderErrors       = new LongAdder();
    final LongAdder mlErrors           = new LongAdder();
    final AtomicLong renderLatencySumNanos = new AtomicLong();
    final AtomicLong renderLatencyCount    = new AtomicLong();
    final AtomicLong mlLatencySumNanos     = new AtomicLong();
    final AtomicLong mlLatencyCount        = new AtomicLong();
    final AtomicLongArray batchSizeHistogram; // length = maxBatchSize + 1

    EvaluationStats snapshot(int renderQueueDepth, int mlQueueDepth, int sessions, int players);
}
```

`EvaluationStats` is the public immutable DTO returned by `service.stats()`:

```java
@Value
public class EvaluationStats {
    long rendersCompleted, mlBatchesCompleted, matchesDispatched;
    long droppedRenderJobs, droppedMlJobs, renderErrors, mlErrors;
    long renderLatencyAvgMicros, mlLatencyAvgMicros;
    int renderQueueDepth, mlQueueDepth;
    int registeredSessions, activePlayers;
    long[] batchSizeHistogram;
}
```

### `SessionHandle` (internal)

```java
final class SessionHandle {
    final GameSession session;
    final BiConsumer<UUID, Integer> scoreCallback;
    final ConcurrentHashMap<UUID, Long> lastEvalAtNanos = new ConcurrentHashMap<>();
    int currentCameraIndex = 0; // accessed only from main-thread coordinator

    void advanceCamera() { currentCameraIndex = (currentCameraIndex + 1) % 3; }

    long lastEvalAt(UUID playerId) { return lastEvalAtNanos.getOrDefault(playerId, 0L); }
    void recordEvalAttempt(UUID playerId, long nanos) { lastEvalAtNanos.put(playerId, nanos); }
    void forgetPlayer(UUID playerId) { lastEvalAtNanos.remove(playerId); }
}
```

`currentCameraIndex` lives here (moved out of `GameSession`) because camera rotation is
an evaluation concern, not a game-session concern.

## Data Flow

1. Block place / break on a plot → `GameListener.applyMirrorPlace/Break` →
   `MutablePlotScene.setBlock` (main thread) + `gp.zoneDirty(true)`. *Unchanged.*
2. Coordinator (every 250 ms, main thread): for each registered session, for each
   eligible dirty player, builds `EvalJob`, clears dirty, offers to render queue.
3. `RenderWorker` pulls job → `mirror.readLock()` → `RenderService.render()` →
   `EvalFrame` to ML queue.
4. `MlCoalescerWorker` drains batch → `MLService.predictBatchRgb()` → matches dispatched
   to `Bukkit.getScheduler().runTask(...)` → `GameManager.handleScore(arena, player, theme)`.

The mirror reference travels through the pipeline; rendering reads whatever state the
mirror is in at render time (under read-lock). Writes that happened between enqueue and
render are reflected in the rendered frame — that is correct and desirable.

## Threading Model

| Stage           | Thread                            | Locks                     | Blocking? |
|-----------------|-----------------------------------|---------------------------|-----------|
| Listener writes | Bukkit main                       | none (single-writer)      | no        |
| Coordinator     | Bukkit main (scheduled tick)      | none                      | no        |
| Render worker   | daemon `bbai-eval-render-<i>`     | `mirror.readLock()`       | yes (queue.take, render) |
| ML coalescer    | daemon `bbai-eval-ml`             | none                      | yes (queue.poll, ONNX) |
| Score callback  | Bukkit main (`runTask`)           | none                      | no        |

- The `RenderService.render(...)` call internally uses its own `ForkJoinPool` to
  parallelise pixel work. Default `renderWorkers=1` therefore keeps CPU saturated
  without over-subscription.
- `MLService` ONNX session is thread-safe; using a single ML coalescer thread keeps
  batching strictly serial (no need for synchronisation around batch assembly).

## Configuration

```yaml
# ── Evaluation Pipeline ────────────────────────────────────────────────
# Centralised render + ML inference scheduling. Replaces the per-arena
# render timer; spreads load evenly across time and enables ML batching.
evaluation:
  # Minimum interval (ms) between two evaluations of the same player.
  # Under load the effective cadence stretches automatically via queue
  # backpressure (target ≤10 s).
  min-cadence-ms: 5000

  # Coordinator scan period in Bukkit ticks (1 tick = 50 ms).
  coordinator-tick-period: 5

  # Render worker thread count. The renderer's internal ForkJoinPool
  # already saturates all CPU cores; 1 worker is usually optimal.
  render-workers: 1

  # Render queue capacity. When full, the coordinator drops the job
  # (player stays dirty and is re-considered next tick).
  render-queue-capacity: 64

  # Maximum images per ML batch.
  ml-batch-max-size: 8

  # Maximum wait (ms) for the ML worker to gather a batch.
  ml-batch-max-wait-ms: 200

  # ML queue capacity.
  ml-queue-capacity: 64

  # ML topK — number of top predictions to check against the theme.
  ml-top-k: 2

  # Period (seconds) for the DEBUG metrics summary log line. 0 = off.
  metrics-log-period-seconds: 60
```

## Metrics & Observability

- Periodic log line at DEBUG, every `metrics-log-period-seconds`:
  ```
  [DEBUG] [Evaluation] rendered=1234 mlBatches=312 awg-batch=4.0 avg-render=12.3ms
                       avg-ml=87.4ms scores=89 dropped(r/m)=0/0 errors(r/m)=0/0
                       queueDepth(r/m)=3/2 sessions=4 players=23
  ```
- Admin command `/bbai stats` prints the same snapshot interactively.

## Lifecycle Integration

### `PluginContext.enable()` order

Insert `evaluationService.enable()` **after** `mlService.enable()` and
`renderService.enable()`, **before** `gameManager.enable()`. Reason: GameManager needs
EvaluationService at session start.

### `GameManager` changes

Remove:
- `private static final int RENDER_INTERVAL_TICKS = 100;` (`GameManager.java:56`)
- `private void startRenderTimer(GameSession session)` (`GameManager.java:435–526`)
- The call site `startRenderTimer(session)` in `startGame`.

Add: in `startGame`, after mirrors are installed:
```java
plugin.getContext().getEvaluationService().registerSession(
        session,
        (playerId, themeIndex) -> handleScore(session.arena().name(), playerId, themeIndex));
```

In `endGame` / wherever sessions are torn down:
```java
plugin.getContext().getEvaluationService().unregisterSession(session.arena().name());
```

### `GameSession` changes

Remove:
- `private int currentCameraIndex;` (line 52) and accessors (`currentCameraIndex(...)`,
  `advanceCamera()`).
- `private int renderTaskId = -1;` (line 70) and accessors.
- `cancelTask(renderTaskId);` in `stopAllTasks()` (line 164).
- `renderTaskId = -1;` reset (line 168).

Keep: `gameTickTaskId`, `mirror(...)`, `installMirror(...)`, all player state.

## Failure Modes

| Scenario                        | Behaviour                                           |
|---------------------------------|-----------------------------------------------------|
| Render queue full               | Coordinator drops, increments `droppedRenderJobs`. Player stays dirty → retried next tick. |
| ML queue full                   | Render worker drops, increments `droppedMlJobs`.    |
| Render exception                | Worker logs DEBUG, increments `renderErrors`, continues. |
| ML exception                    | Worker logs DEBUG, increments `mlErrors`, continues. Entire batch is lost (acceptable — they'll be re-evaluated next cycle). |
| Worker thread dies (uncaught)   | `UncaughtExceptionHandler` logs ERROR. Service is degraded but other workers continue. |
| Session unregistered mid-flight | Worker checks `registry.callback(arenaName)` before dispatch; if null, drops silently. |
| Player left mid-flight          | Existing `handleScore` re-validates `gp != null` and `session.state == PLAYING`. |
| ML service in disabled mode     | `predictBatchRgb` returns zero-embeddings → no matches → no scores awarded. Pipeline keeps running, just never scores. |

## Testing Strategy

Stack: JUnit Jupiter 5.10 + Mockito 5.12 + MockBukkit 4.50.0 + paper-api 1.21.5 (test).

**Plain Mockito unit tests:**
- `EvalConfig`: parsing from `YamlConfiguration` + defaults
- `EvalJob`, `EvalFrame`: equality, stale marking
- `RenderQueue`: FIFO order, dedup replaces, capacity backpressure
- `MlQueue`: `drainBatch` semantics — wait window, partial drain, full drain, empty
- `EvaluationMetrics`: counter atomicity, snapshot correctness
- `SessionHandle`: camera rotation, lastEvalAt CRUD
- `RenderWorker`: mock `RenderService` + `MlQueue`, verify happy path + error swallowing
- `MlCoalescerWorker`: mock `MLService.predictBatchRgb`, verify batch assembly +
  callback dispatch + match logic
- `EvaluationCoordinator`: extract `pickAndDispatch(now, handles)` as pure function
  taking the clock, fake `RenderQueue` and `EvaluationMetrics`; test selection logic
  exhaustively without Bukkit.

**MockBukkit integration:**
- `EvaluationService` lifecycle (enable/shutdown idempotency).
- `GameManager.startGame` → service registered.

**Not tested in CI (existing gap):**
- Real ONNX inference end-to-end. Validated manually via `/bbaitest run`.

## Risks

| Risk                              | Mitigation                                          |
|-----------------------------------|-----------------------------------------------------|
| `RenderService` internal FJP × N render workers → CPU over-subscription | Default `renderWorkers=1`; configurable upward if profiling shows headroom. |
| Coordinator main-thread cost      | Scan is `O(sessions × players)` with trivial constant. At 10 × 8 = 80 players × 4 ticks/sec = 320 ops/sec — well below 1 ms/tick budget. |
| Evaluation cadence stretches >10 s under pathological load | Documented as accepted soft behaviour. Metrics will surface it if it becomes a problem. |
| Behavioural drift vs. current code on edge cases (e.g. theme rotation after score) | Score callback semantics preserved verbatim — `handleScore` itself unchanged; only its trigger source moves. |

## Open Questions

None — design accepted by user 2026-05-31.
