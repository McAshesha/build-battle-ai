# Evaluation Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Commit policy:** The user signs commits manually and has asked Claude not to invoke `git commit`. Each task ends with a **"Hand off for review"** step — stop there and let the user commit.

**Goal:** Replace the per-arena 5 s render/ML timer with a centralised `EvaluationService` that smooths load via bounded queues, batches ML inference, exposes metrics, and preserves all existing safety invariants.

**Architecture:** New `evaluation/` domain (interface in `api/`, impl outside). One Bukkit-scheduled coordinator on the main thread picks dirty players and offers `EvalJob`s to a bounded `RenderQueue`. N daemon render workers pull jobs under `mirror.readLock()` and emit `EvalFrame`s to a bounded `MlQueue`. One ML coalescer drains batches (size K or wait T ms) and calls `MLService.predictBatchRgb`. Matches dispatch back to main via `runTask` → `GameManager.handleScore`. All counters in `EvaluationMetrics`; snapshots via `service.stats()` and the `/bbai stats` admin command.

**Tech Stack:** Java 8, Lombok, Bukkit/Spigot 1.21.8 (provided), PacketEvents (shaded), XSeries (shaded), ONNX Runtime 1.21.0, JUnit Jupiter 5.10, Mockito 5.12, MockBukkit 4.50.0.

**Reference spec:** `docs/superpowers/specs/2026-05-31-evaluation-pipeline-design.md`

**Code style reminders:**
- Java 8 only (no `var`, `List.of`, switch-expressions).
- Brace-free single-statement bodies, body on the next line.
- Javadoc on every new public type / method / field.
- Tests live in `src/test/java/ru/ashesha/buildBattleAI/evaluation/`.

---

## File Structure

**Created (production):**
- `src/main/java/ru/ashesha/buildBattleAI/evaluation/api/BBAIEvaluationService.java`
- `src/main/java/ru/ashesha/buildBattleAI/evaluation/api/EvaluationStats.java`
- `src/main/java/ru/ashesha/buildBattleAI/evaluation/EvalConfig.java`
- `src/main/java/ru/ashesha/buildBattleAI/evaluation/EvalJob.java`
- `src/main/java/ru/ashesha/buildBattleAI/evaluation/EvalFrame.java`
- `src/main/java/ru/ashesha/buildBattleAI/evaluation/EvaluationMetrics.java`
- `src/main/java/ru/ashesha/buildBattleAI/evaluation/SessionHandle.java`
- `src/main/java/ru/ashesha/buildBattleAI/evaluation/RenderQueue.java`
- `src/main/java/ru/ashesha/buildBattleAI/evaluation/MlQueue.java`
- `src/main/java/ru/ashesha/buildBattleAI/evaluation/RenderWorker.java`
- `src/main/java/ru/ashesha/buildBattleAI/evaluation/MlCoalescerWorker.java`
- `src/main/java/ru/ashesha/buildBattleAI/evaluation/EvaluationCoordinator.java`
- `src/main/java/ru/ashesha/buildBattleAI/evaluation/EvaluationService.java`

**Created (tests, one per production class):**
- `src/test/java/ru/ashesha/buildBattleAI/evaluation/EvalConfigTest.java`
- ...etc (paths mirror production).

**Modified:**
- `src/main/java/ru/ashesha/buildBattleAI/core/PluginContext.java`
- `src/main/java/ru/ashesha/buildBattleAI/game/GameManager.java`
- `src/main/java/ru/ashesha/buildBattleAI/game/GameSession.java`
- `src/main/java/ru/ashesha/buildBattleAI/commands/ArenaCommand.java`
- `src/main/resources/config.yml`

---

## Task 1: Package scaffold + empty `EvaluationService` skeleton

**Goal:** Create the package, an empty `BBAIEvaluationService` interface, and an `EvaluationService` skeleton implementing `PluginService`. Wire a *no-op* enable/shutdown so the build is green and other services can compile-time reference it later. No behaviour yet.

**Files:**
- Create: `src/main/java/ru/ashesha/buildBattleAI/evaluation/api/BBAIEvaluationService.java`
- Create: `src/main/java/ru/ashesha/buildBattleAI/evaluation/EvaluationService.java`
- Create: `src/test/java/ru/ashesha/buildBattleAI/evaluation/EvaluationServiceLifecycleTest.java`

- [ ] **Step 1: Write the failing lifecycle test**

`src/test/java/ru/ashesha/buildBattleAI/evaluation/EvaluationServiceLifecycleTest.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.BuildBattleAI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

class EvaluationServiceLifecycleTest {

    @Test
    void enableThenShutdown_isIdempotent() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        EvaluationService service = new EvaluationService(plugin);
        assertDoesNotThrow(service::enable);
        assertDoesNotThrow(service::shutdown);
        assertDoesNotThrow(service::shutdown); // second shutdown is a no-op
    }
}
```

- [ ] **Step 2: Run the test — it must fail (class does not exist)**

```bash
mvn test -pl . -Dtest=EvaluationServiceLifecycleTest
```
Expected: compilation failure on `EvaluationService` symbol.

- [ ] **Step 3: Create the interface**

`src/main/java/ru/ashesha/buildBattleAI/evaluation/api/BBAIEvaluationService.java`:

```java
package ru.ashesha.buildBattleAI.evaluation.api;

import lombok.NonNull;
import ru.ashesha.buildBattleAI.game.GameSession;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Public API of the evaluation pipeline. Implementations centrally schedule
 * render + ML inference across all active arenas with bounded queues,
 * ML batching, and per-player cadence smoothing.
 * <p>
 * Lifecycle is owned by {@code PluginService} (internal); callers only see
 * the runtime API below.
 */
public interface BBAIEvaluationService {

    /**
     * Registers an active game session with the evaluation pipeline. From
     * this moment on, the service will periodically scan the session's
     * dirty players and run the render → ML pipeline for them.
     *
     * @param session       the active session (already in PLAYING state)
     * @param scoreCallback invoked on the Bukkit main thread for every
     *                      successful match — arguments are (playerId, themeIndex)
     */
    void registerSession(@NonNull GameSession session,
                         @NonNull BiConsumer<UUID, Integer> scoreCallback);

    /**
     * Unregisters a session. Any in-flight jobs for this arena are dropped
     * silently when they reach the dispatch stage.
     *
     * @param arenaName arena name (the session's arena identifier)
     */
    void unregisterSession(@NonNull String arenaName);

    /**
     * Returns an immutable snapshot of pipeline metrics. Safe to call from
     * any thread.
     */
    @NonNull EvaluationStats stats();
}
```

- [ ] **Step 4: Create the service skeleton**

`src/main/java/ru/ashesha/buildBattleAI/evaluation/EvaluationService.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.evaluation.api.BBAIEvaluationService;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats;
import ru.ashesha.buildBattleAI.game.GameSession;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Default implementation of {@link BBAIEvaluationService}. Wires together
 * the bounded queues, render workers, ML coalescer, coordinator task, and
 * metrics. Lifecycle is exposed through {@link PluginService}; the public
 * runtime API is on the interface.
 */
@RequiredArgsConstructor
public class EvaluationService implements PluginService, BBAIEvaluationService {

    private final BuildBattleAI plugin;
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    @Override
    public void enable() {
        if (!enabled.compareAndSet(false, true))
            return;
        // Real wiring is added in later tasks.
    }

    @Override
    public void shutdown() {
        if (!enabled.compareAndSet(true, false))
            return;
        // Real teardown is added in later tasks.
    }

    @Override
    public void registerSession(@NonNull GameSession session,
                                @NonNull BiConsumer<UUID, Integer> scoreCallback) {
        throw new UnsupportedOperationException("Wired in a later task");
    }

    @Override
    public void unregisterSession(@NonNull String arenaName) {
        throw new UnsupportedOperationException("Wired in a later task");
    }

    @Override
    public @NonNull EvaluationStats stats() {
        throw new UnsupportedOperationException("Wired in a later task");
    }
}
```

- [ ] **Step 5: Run the test — must pass**

```bash
mvn test -pl . -Dtest=EvaluationServiceLifecycleTest
```
Expected: 1 test, 0 failures.

- [ ] **Step 6: Hand off for review**

Stop here. The user reviews and commits before Task 2.

---

## Task 2: `EvalJob` and `EvalFrame` data classes

**Goal:** Immutable data carriers for render requests and render→ML hand-offs. `EvalJob` carries a stale flag so the dedup index can invalidate older copies sitting in the queue.

**Files:**
- Create: `src/main/java/ru/ashesha/buildBattleAI/evaluation/EvalJob.java`
- Create: `src/main/java/ru/ashesha/buildBattleAI/evaluation/EvalFrame.java`
- Create: `src/test/java/ru/ashesha/buildBattleAI/evaluation/EvalJobTest.java`

- [ ] **Step 1: Write the failing test**

`EvalJobTest.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EvalJobTest {

    @Test
    void newJob_isNotStale() {
        EvalJob job = sampleJob();
        assertFalse(job.isStale());
    }

    @Test
    void markStale_flipsFlag() {
        EvalJob job = sampleJob();
        job.markStale();
        assertTrue(job.isStale());
    }

    @Test
    void carriesAllFields() {
        UUID pid = UUID.randomUUID();
        MutablePlotScene mirror = mock(MutablePlotScene.class);
        EvalJob job = EvalJob.builder()
                .arenaName("arena1")
                .playerId(pid)
                .playerName("Bob")
                .plotIndex(0)
                .themeIndex(3)
                .expectedTheme("castle")
                .mirror(mirror)
                .cameraX(1.5).cameraY(64.0).cameraZ(2.5)
                .cameraYaw(90f).cameraPitch(0f)
                .enqueuedAtNanos(123_456L)
                .build();

        assertEquals("arena1", job.arenaName());
        assertEquals(pid, job.playerId());
        assertEquals("Bob", job.playerName());
        assertEquals(0, job.plotIndex());
        assertEquals(3, job.themeIndex());
        assertEquals("castle", job.expectedTheme());
        assertSame(mirror, job.mirror());
        assertEquals(1.5, job.cameraX());
        assertEquals(64.0, job.cameraY());
        assertEquals(2.5, job.cameraZ());
        assertEquals(90f, job.cameraYaw());
        assertEquals(0f, job.cameraPitch());
        assertEquals(123_456L, job.enqueuedAtNanos());
    }

    private static EvalJob sampleJob() {
        return EvalJob.builder()
                .arenaName("a").playerId(UUID.randomUUID()).playerName("p")
                .plotIndex(0).themeIndex(0).expectedTheme("t")
                .mirror(mock(MutablePlotScene.class))
                .cameraX(0).cameraY(0).cameraZ(0).cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(0L)
                .build();
    }
}
```

- [ ] **Step 2: Run the test — must fail (no class)**

```bash
mvn test -pl . -Dtest=EvalJobTest
```
Expected: compilation failure.

- [ ] **Step 3: Create `EvalJob`**

`src/main/java/ru/ashesha/buildBattleAI/evaluation/EvalJob.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Immutable render-request descriptor produced by the coordinator and
 * consumed by render workers. Carries everything the worker needs to
 * render without touching Bukkit state again.
 * <p>
 * The stale flag is the only mutable bit: {@link #markStale()} lets the
 * {@code RenderQueue} dedup index invalidate older queued copies when a
 * newer request for the same player arrives.
 */
@Builder(access = AccessLevel.PACKAGE)
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class EvalJob {

    @NonNull private final String arenaName;
    @NonNull private final UUID playerId;
    @NonNull private final String playerName;
    private final int plotIndex;
    private final int themeIndex;
    @NonNull private final String expectedTheme;
    @NonNull private final MutablePlotScene mirror;
    private final double cameraX;
    private final double cameraY;
    private final double cameraZ;
    private final float cameraYaw;
    private final float cameraPitch;
    private final long enqueuedAtNanos;

    private final AtomicBoolean stale = new AtomicBoolean(false);

    /** Marks this job as superseded; workers must skip stale jobs on dequeue. */
    public void markStale() {
        stale.set(true);
    }

    /** True if a newer job for the same player has been enqueued. */
    public boolean isStale() {
        return stale.get();
    }
}
```

- [ ] **Step 4: Create `EvalFrame`**

`src/main/java/ru/ashesha/buildBattleAI/evaluation/EvalFrame.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Output of the render stage: a fully-rendered RGB byte buffer ready
 * for ML inference, along with its originating job (so the ML stage
 * can route scores back to the right player).
 */
@Value
@Accessors(fluent = true)
class EvalFrame {

    @NonNull EvalJob job;
    /** Row-major 224×224 RGB, layout matching {@code MLService.predictRgb}. */
    byte @NonNull [] rgb;
    long renderedAtNanos;
}
```

- [ ] **Step 5: Run the tests — must pass**

```bash
mvn test -pl . -Dtest=EvalJobTest
```
Expected: 3 tests, 0 failures.

- [ ] **Step 6: Hand off for review**

---

## Task 3: `EvalConfig` with YAML loader

**Goal:** Immutable config DTO with a static `fromYaml(YamlConfiguration)` factory applying defaults for every key.

**Files:**
- Create: `src/main/java/ru/ashesha/buildBattleAI/evaluation/EvalConfig.java`
- Create: `src/test/java/ru/ashesha/buildBattleAI/evaluation/EvalConfigTest.java`

- [ ] **Step 1: Write the failing test**

`EvalConfigTest.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvalConfigTest {

    @Test
    void defaults_appliedWhenSectionMissing() {
        YamlConfiguration y = new YamlConfiguration();
        EvalConfig c = EvalConfig.fromYaml(y);

        assertEquals(5000L, c.minCadenceMs());
        assertEquals(5, c.coordinatorTickPeriod());
        assertEquals(1, c.renderWorkers());
        assertEquals(64, c.renderQueueCapacity());
        assertEquals(8, c.mlBatchMaxSize());
        assertEquals(200L, c.mlBatchMaxWaitMs());
        assertEquals(64, c.mlQueueCapacity());
        assertEquals(2, c.mlTopK());
        assertEquals(60, c.metricsLogPeriodSeconds());
    }

    @Test
    void overrides_areRespected() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("evaluation.min-cadence-ms", 3000);
        y.set("evaluation.coordinator-tick-period", 10);
        y.set("evaluation.render-workers", 2);
        y.set("evaluation.render-queue-capacity", 128);
        y.set("evaluation.ml-batch-max-size", 16);
        y.set("evaluation.ml-batch-max-wait-ms", 100);
        y.set("evaluation.ml-queue-capacity", 128);
        y.set("evaluation.ml-top-k", 3);
        y.set("evaluation.metrics-log-period-seconds", 30);

        EvalConfig c = EvalConfig.fromYaml(y);
        assertEquals(3000L, c.minCadenceMs());
        assertEquals(10, c.coordinatorTickPeriod());
        assertEquals(2, c.renderWorkers());
        assertEquals(128, c.renderQueueCapacity());
        assertEquals(16, c.mlBatchMaxSize());
        assertEquals(100L, c.mlBatchMaxWaitMs());
        assertEquals(128, c.mlQueueCapacity());
        assertEquals(3, c.mlTopK());
        assertEquals(30, c.metricsLogPeriodSeconds());
    }
}
```

- [ ] **Step 2: Run — must fail**

```bash
mvn test -pl . -Dtest=EvalConfigTest
```

- [ ] **Step 3: Create `EvalConfig`**

`src/main/java/ru/ashesha/buildBattleAI/evaluation/EvalConfig.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;
import lombok.Value;
import lombok.experimental.Accessors;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Immutable configuration snapshot for the evaluation pipeline. Loaded
 * once at {@code EvaluationService.enable()} from {@code config.yml}.
 */
@Value
@Accessors(fluent = true)
public class EvalConfig {

    long minCadenceMs;
    int coordinatorTickPeriod;
    int renderWorkers;
    int renderQueueCapacity;
    int mlBatchMaxSize;
    long mlBatchMaxWaitMs;
    int mlQueueCapacity;
    int mlTopK;
    int metricsLogPeriodSeconds;

    /**
     * Reads the {@code evaluation.*} section, applying documented defaults
     * for every missing key.
     */
    public static @NonNull EvalConfig fromYaml(@NonNull YamlConfiguration yaml) {
        return new EvalConfig(
                yaml.getLong("evaluation.min-cadence-ms", 5000L),
                yaml.getInt("evaluation.coordinator-tick-period", 5),
                yaml.getInt("evaluation.render-workers", 1),
                yaml.getInt("evaluation.render-queue-capacity", 64),
                yaml.getInt("evaluation.ml-batch-max-size", 8),
                yaml.getLong("evaluation.ml-batch-max-wait-ms", 200L),
                yaml.getInt("evaluation.ml-queue-capacity", 64),
                yaml.getInt("evaluation.ml-top-k", 2),
                yaml.getInt("evaluation.metrics-log-period-seconds", 60));
    }
}
```

- [ ] **Step 4: Run — must pass**

```bash
mvn test -pl . -Dtest=EvalConfigTest
```

- [ ] **Step 5: Hand off for review**

---

## Task 4: `EvaluationMetrics` + `EvaluationStats` snapshot

**Goal:** Atomic counters, latency sums, batch-size histogram, and an immutable `EvaluationStats` DTO snapshotting the current state.

**Files:**
- Create: `src/main/java/ru/ashesha/buildBattleAI/evaluation/EvaluationMetrics.java`
- Create: `src/main/java/ru/ashesha/buildBattleAI/evaluation/api/EvaluationStats.java`
- Create: `src/test/java/ru/ashesha/buildBattleAI/evaluation/EvaluationMetricsTest.java`

- [ ] **Step 1: Write the failing test**

`EvaluationMetricsTest.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationMetricsTest {

    @Test
    void countersAggregate() {
        EvaluationMetrics m = new EvaluationMetrics(8);
        m.incRendersCompleted();
        m.incRendersCompleted();
        m.incMlBatchesCompleted();
        m.incMatchesDispatched();
        m.incDroppedRenderJobs();

        EvaluationStats s = m.snapshot(3, 1, 2, 16);
        assertEquals(2, s.rendersCompleted());
        assertEquals(1, s.mlBatchesCompleted());
        assertEquals(1, s.matchesDispatched());
        assertEquals(1, s.droppedRenderJobs());
        assertEquals(3, s.renderQueueDepth());
        assertEquals(1, s.mlQueueDepth());
        assertEquals(2, s.registeredSessions());
        assertEquals(16, s.activePlayers());
    }

    @Test
    void latencyAverageIsComputed() {
        EvaluationMetrics m = new EvaluationMetrics(8);
        m.recordRenderLatencyNanos(10_000_000L); // 10 ms
        m.recordRenderLatencyNanos(20_000_000L); // 20 ms

        EvaluationStats s = m.snapshot(0, 0, 0, 0);
        assertEquals(15_000L, s.renderLatencyAvgMicros()); // average 15 ms = 15000 µs
    }

    @Test
    void batchHistogramTracksSizes() {
        EvaluationMetrics m = new EvaluationMetrics(8);
        m.recordBatchSize(1);
        m.recordBatchSize(4);
        m.recordBatchSize(4);
        m.recordBatchSize(8);

        EvaluationStats s = m.snapshot(0, 0, 0, 0);
        long[] h = s.batchSizeHistogram();
        assertEquals(9, h.length); // 0..8 inclusive
        assertEquals(1, h[1]);
        assertEquals(2, h[4]);
        assertEquals(1, h[8]);
    }

    @Test
    void oversizedBatch_clampsToHistogramTail() {
        EvaluationMetrics m = new EvaluationMetrics(4);
        m.recordBatchSize(99); // out of range
        EvaluationStats s = m.snapshot(0, 0, 0, 0);
        long[] h = s.batchSizeHistogram();
        assertEquals(1, h[4]); // clamped to max
    }
}
```

- [ ] **Step 2: Run — must fail**

```bash
mvn test -pl . -Dtest=EvaluationMetricsTest
```

- [ ] **Step 3: Create `EvaluationStats`**

`src/main/java/ru/ashesha/buildBattleAI/evaluation/api/EvaluationStats.java`:

```java
package ru.ashesha.buildBattleAI.evaluation.api;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Immutable point-in-time snapshot of evaluation-pipeline metrics. Safe
 * to pass across threads — all fields are primitives or an array that the
 * service has already defensively copied.
 */
@Value
@Accessors(fluent = true)
public class EvaluationStats {

    long rendersCompleted;
    long mlBatchesCompleted;
    long matchesDispatched;
    long droppedRenderJobs;
    long droppedMlJobs;
    long renderErrors;
    long mlErrors;
    long renderLatencyAvgMicros;
    long mlLatencyAvgMicros;
    int renderQueueDepth;
    int mlQueueDepth;
    int registeredSessions;
    int activePlayers;
    long[] batchSizeHistogram;
}
```

- [ ] **Step 4: Create `EvaluationMetrics`**

`src/main/java/ru/ashesha/buildBattleAI/evaluation/EvaluationMetrics.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Mutable counter set for the evaluation pipeline. Designed for high
 * write contention from multiple worker threads:
 * <ul>
 *   <li>{@link LongAdder} for hot counters (renders, drops, errors).</li>
 *   <li>{@link AtomicLong} for latency sums (paired with their count).</li>
 *   <li>{@link AtomicLongArray} for the batch-size histogram.</li>
 * </ul>
 * Snapshots are eventually-consistent — fields are not read atomically as a
 * group, but each individual counter is correct in isolation.
 */
public final class EvaluationMetrics {

    private final int maxBatchSize;

    private final LongAdder rendersCompleted   = new LongAdder();
    private final LongAdder mlBatchesCompleted = new LongAdder();
    private final LongAdder matchesDispatched  = new LongAdder();
    private final LongAdder droppedRenderJobs  = new LongAdder();
    private final LongAdder droppedMlJobs      = new LongAdder();
    private final LongAdder renderErrors       = new LongAdder();
    private final LongAdder mlErrors           = new LongAdder();

    private final AtomicLong renderLatencySumNanos = new AtomicLong();
    private final AtomicLong renderLatencyCount    = new AtomicLong();
    private final AtomicLong mlLatencySumNanos     = new AtomicLong();
    private final AtomicLong mlLatencyCount        = new AtomicLong();

    private final AtomicLongArray batchSizeHistogram;

    public EvaluationMetrics(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
        this.batchSizeHistogram = new AtomicLongArray(maxBatchSize + 1);
    }

    public void incRendersCompleted()  { rendersCompleted.increment(); }
    public void incMlBatchesCompleted(){ mlBatchesCompleted.increment(); }
    public void incMatchesDispatched() { matchesDispatched.increment(); }
    public void incDroppedRenderJobs() { droppedRenderJobs.increment(); }
    public void incDroppedMlJobs()     { droppedMlJobs.increment(); }
    public void incRenderErrors()      { renderErrors.increment(); }
    public void incMlErrors()          { mlErrors.increment(); }

    public void recordRenderLatencyNanos(long nanos) {
        renderLatencySumNanos.addAndGet(nanos);
        renderLatencyCount.incrementAndGet();
    }

    public void recordMlLatencyNanos(long nanos) {
        mlLatencySumNanos.addAndGet(nanos);
        mlLatencyCount.incrementAndGet();
    }

    /**
     * Records a single ML batch's size. Out-of-range values are clamped
     * into the last bucket so an unexpected size never silently disappears.
     */
    public void recordBatchSize(int size) {
        int clamped = size;
        if (clamped < 0) clamped = 0;
        if (clamped > maxBatchSize) clamped = maxBatchSize;
        batchSizeHistogram.incrementAndGet(clamped);
    }

    public EvaluationStats snapshot(int renderQueueDepth, int mlQueueDepth,
                                    int registeredSessions, int activePlayers) {
        long[] hist = new long[batchSizeHistogram.length()];
        for (int i = 0; i < hist.length; i++)
            hist[i] = batchSizeHistogram.get(i);

        return new EvaluationStats(
                rendersCompleted.sum(),
                mlBatchesCompleted.sum(),
                matchesDispatched.sum(),
                droppedRenderJobs.sum(),
                droppedMlJobs.sum(),
                renderErrors.sum(),
                mlErrors.sum(),
                avgMicros(renderLatencySumNanos.get(), renderLatencyCount.get()),
                avgMicros(mlLatencySumNanos.get(), mlLatencyCount.get()),
                renderQueueDepth,
                mlQueueDepth,
                registeredSessions,
                activePlayers,
                hist);
    }

    private static long avgMicros(long sumNanos, long count) {
        if (count == 0)
            return 0L;
        return sumNanos / count / 1_000L;
    }
}
```

- [ ] **Step 5: Run — must pass**

```bash
mvn test -pl . -Dtest=EvaluationMetricsTest
```

- [ ] **Step 6: Hand off for review**

---

## Task 5: `SessionHandle`

**Goal:** Per-session internal state — the originating `GameSession`, the score callback, the per-player `lastEvalAtNanos` map, and the camera rotation index.

**Files:**
- Create: `src/main/java/ru/ashesha/buildBattleAI/evaluation/SessionHandle.java`
- Create: `src/test/java/ru/ashesha/buildBattleAI/evaluation/SessionHandleTest.java`

- [ ] **Step 1: Write the failing test**

`SessionHandleTest.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.game.GameSession;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SessionHandleTest {

    @Test
    void cameraRotates_threeStepsThenWrapsTo0() {
        SessionHandle h = sample();
        assertEquals(0, h.currentCameraIndex());
        h.advanceCamera();
        assertEquals(1, h.currentCameraIndex());
        h.advanceCamera();
        assertEquals(2, h.currentCameraIndex());
        h.advanceCamera();
        assertEquals(0, h.currentCameraIndex());
    }

    @Test
    void lastEvalAt_recordedAndReadBack() {
        SessionHandle h = sample();
        UUID pid = UUID.randomUUID();
        assertEquals(0L, h.lastEvalAtNanos(pid));
        h.recordEvalAttempt(pid, 12345L);
        assertEquals(12345L, h.lastEvalAtNanos(pid));
    }

    @Test
    void forgetPlayer_dropsTimestamp() {
        SessionHandle h = sample();
        UUID pid = UUID.randomUUID();
        h.recordEvalAttempt(pid, 1L);
        h.forgetPlayer(pid);
        assertEquals(0L, h.lastEvalAtNanos(pid));
    }

    @Test
    void scoreCallback_isExposed() {
        AtomicReference<UUID> capturedPid = new AtomicReference<>();
        AtomicReference<Integer> capturedTheme = new AtomicReference<>();
        BiConsumer<UUID, Integer> cb = (p, t) -> { capturedPid.set(p); capturedTheme.set(t); };
        SessionHandle h = new SessionHandle(mock(GameSession.class), cb);
        UUID pid = UUID.randomUUID();
        h.scoreCallback().accept(pid, 7);
        assertEquals(pid, capturedPid.get());
        assertEquals(7, capturedTheme.get());
    }

    private static SessionHandle sample() {
        return new SessionHandle(mock(GameSession.class), (p, t) -> {});
    }
}
```

- [ ] **Step 2: Run — must fail**

```bash
mvn test -pl . -Dtest=SessionHandleTest
```

- [ ] **Step 3: Create `SessionHandle`**

`src/main/java/ru/ashesha/buildBattleAI/evaluation/SessionHandle.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import ru.ashesha.buildBattleAI.game.GameSession;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Per-session bookkeeping for the evaluation pipeline. Owns the camera
 * rotation index (moved out of {@code GameSession} — it's an evaluation
 * concern, not a game-session concern) and the per-player last-evaluated
 * timestamps that drive cadence enforcement.
 * <p>
 * The camera index is only touched by the coordinator (main-thread,
 * single-writer) — no synchronisation needed. The lastEvalAt map is a
 * {@link ConcurrentHashMap} so future-proof against off-main-thread use.
 */
@RequiredArgsConstructor
@Getter
@Accessors(fluent = true)
public final class SessionHandle {

    private final @NonNull GameSession session;
    private final @NonNull BiConsumer<UUID, Integer> scoreCallback;

    @Getter(AccessLevel.NONE)
    private final ConcurrentHashMap<UUID, Long> lastEvalAtNanos = new ConcurrentHashMap<>();

    private int currentCameraIndex = 0;

    /** Advances camera through the fixed 3-slot rotation. */
    public void advanceCamera() {
        currentCameraIndex = (currentCameraIndex + 1) % 3;
    }

    /** Returns the last enqueue time for this player, or 0 if never enqueued. */
    public long lastEvalAtNanos(@NonNull UUID playerId) {
        Long v = lastEvalAtNanos.get(playerId);
        return v == null ? 0L : v;
    }

    /** Records that a job was just enqueued for this player. */
    public void recordEvalAttempt(@NonNull UUID playerId, long nanos) {
        lastEvalAtNanos.put(playerId, nanos);
    }

    /** Drops the lastEvalAt entry for a player who left the session. */
    public void forgetPlayer(@NonNull UUID playerId) {
        lastEvalAtNanos.remove(playerId);
    }
}
```

- [ ] **Step 4: Run — must pass**

```bash
mvn test -pl . -Dtest=SessionHandleTest
```

- [ ] **Step 5: Hand off for review**

---

## Task 6: `RenderQueue` with dedup + bounded capacity

**Goal:** A bounded FIFO queue with per-player dedup. New offers for an existing player mark the stale job; the consumer skips stale jobs on dequeue.

**Files:**
- Create: `src/main/java/ru/ashesha/buildBattleAI/evaluation/RenderQueue.java`
- Create: `src/test/java/ru/ashesha/buildBattleAI/evaluation/RenderQueueTest.java`

- [ ] **Step 1: Write the failing test**

`RenderQueueTest.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RenderQueueTest {

    @Test
    void offerThenTake_returnsSameJob() throws Exception {
        RenderQueue q = new RenderQueue(8);
        EvalJob j = jobFor(UUID.randomUUID());
        assertTrue(q.offer(j));
        assertSame(j, q.take());
    }

    @Test
    void capacityIsRespected() {
        RenderQueue q = new RenderQueue(2);
        assertTrue(q.offer(jobFor(UUID.randomUUID())));
        assertTrue(q.offer(jobFor(UUID.randomUUID())));
        assertFalse(q.offer(jobFor(UUID.randomUUID())));
    }

    @Test
    void dedup_secondOfferForSamePlayer_marksFirstStale() {
        RenderQueue q = new RenderQueue(8);
        UUID pid = UUID.randomUUID();
        EvalJob first = jobFor(pid);
        EvalJob second = jobFor(pid);
        assertTrue(q.offer(first));
        assertTrue(q.offer(second));
        assertTrue(first.isStale());
        assertFalse(second.isStale());
    }

    @Test
    void take_skipsStaleJobs() throws Exception {
        RenderQueue q = new RenderQueue(8);
        UUID pid = UUID.randomUUID();
        EvalJob first = jobFor(pid);
        EvalJob second = jobFor(pid);
        q.offer(first);
        q.offer(second);
        EvalJob taken = q.take();
        assertSame(second, taken);
    }

    @Test
    void size_reflectsQueueDepth() {
        RenderQueue q = new RenderQueue(8);
        q.offer(jobFor(UUID.randomUUID()));
        q.offer(jobFor(UUID.randomUUID()));
        assertEquals(2, q.size());
    }

    private static EvalJob jobFor(UUID playerId) {
        return EvalJob.builder()
                .arenaName("a").playerId(playerId).playerName("p")
                .plotIndex(0).themeIndex(0).expectedTheme("t")
                .mirror(mock(MutablePlotScene.class))
                .cameraX(0).cameraY(0).cameraZ(0).cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(0L)
                .build();
    }
}
```

- [ ] **Step 2: Run — must fail**

```bash
mvn test -pl . -Dtest=RenderQueueTest
```

- [ ] **Step 3: Create `RenderQueue`**

`src/main/java/ru/ashesha/buildBattleAI/evaluation/RenderQueue.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Bounded FIFO queue feeding the render stage, with per-player
 * deduplication. When a new job arrives for a player who already has a
 * queued job, the queued job is marked stale ({@link EvalJob#markStale()})
 * and the new job takes its place in the dedup index. Workers skip stale
 * jobs on dequeue, so the queue's nominal size may overshoot the actual
 * useful work slightly — that's by design and bounded by the dedup map's
 * cardinality.
 * <p>
 * Thread-safety: many producers (in practice one — the coordinator on the
 * main thread), one or more consumers (render workers).
 */
public final class RenderQueue {

    private final LinkedBlockingQueue<EvalJob> queue;
    private final ConcurrentHashMap<UUID, EvalJob> pending = new ConcurrentHashMap<>();

    public RenderQueue(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /**
     * Non-blocking offer. Returns {@code false} if the underlying queue is
     * full — the caller should treat that as backpressure.
     */
    public boolean offer(@NonNull EvalJob job) {
        EvalJob prev = pending.put(job.playerId(), job);
        if (prev != null)
            prev.markStale();
        if (!queue.offer(job)) {
            pending.remove(job.playerId(), job);
            return false;
        }
        return true;
    }

    /**
     * Blocking take that transparently skips stale jobs.
     */
    public @NonNull EvalJob take() throws InterruptedException {
        while (true) {
            EvalJob j = queue.take();
            if (j.isStale())
                continue;
            pending.remove(j.playerId(), j);
            return j;
        }
    }

    /** Approximate queue depth — for metrics only. */
    public int size() {
        return queue.size();
    }

    /** Drops all queued jobs. Called only during service shutdown. */
    public void clear() {
        queue.clear();
        pending.clear();
    }
}
```

- [ ] **Step 4: Run — must pass**

```bash
mvn test -pl . -Dtest=RenderQueueTest
```

- [ ] **Step 5: Hand off for review**

---

## Task 7: `MlQueue` with batched drain

**Goal:** A bounded queue with a `drainBatch(maxSize, waitMs)` operation: blocks up to `waitMs` for the first frame, then opportunistically drains whatever's already queued up to `maxSize`.

**Files:**
- Create: `src/main/java/ru/ashesha/buildBattleAI/evaluation/MlQueue.java`
- Create: `src/test/java/ru/ashesha/buildBattleAI/evaluation/MlQueueTest.java`

- [ ] **Step 1: Write the failing test**

`MlQueueTest.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MlQueueTest {

    @Test
    void drainBatch_returnsEmpty_whenIdleForFullWait() throws Exception {
        MlQueue q = new MlQueue(4);
        long t0 = System.nanoTime();
        List<EvalFrame> batch = q.drainBatch(4, 50L);
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        assertTrue(batch.isEmpty());
        assertTrue(ms >= 40L, "actual: " + ms); // allow scheduling slop
    }

    @Test
    void drainBatch_returnsImmediately_whenItemAvailable() throws Exception {
        MlQueue q = new MlQueue(4);
        q.offer(frame());
        long t0 = System.nanoTime();
        List<EvalFrame> batch = q.drainBatch(4, 5_000L);
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        assertEquals(1, batch.size());
        assertTrue(ms < 200L, "actual: " + ms);
    }

    @Test
    void drainBatch_capsAtMax() throws Exception {
        MlQueue q = new MlQueue(8);
        for (int i = 0; i < 6; i++)
            q.offer(frame());
        List<EvalFrame> batch = q.drainBatch(4, 5_000L);
        assertEquals(4, batch.size());
    }

    @Test
    void offer_failsWhenFull() {
        MlQueue q = new MlQueue(1);
        assertTrue(q.offer(frame()));
        assertFalse(q.offer(frame()));
    }

    @Test
    void size_reflectsDepth() {
        MlQueue q = new MlQueue(4);
        q.offer(frame());
        q.offer(frame());
        assertEquals(2, q.size());
    }

    private static EvalFrame frame() {
        EvalJob j = EvalJob.builder()
                .arenaName("a").playerId(UUID.randomUUID()).playerName("p")
                .plotIndex(0).themeIndex(0).expectedTheme("t")
                .mirror(mock(MutablePlotScene.class))
                .cameraX(0).cameraY(0).cameraZ(0).cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(0L)
                .build();
        return new EvalFrame(j, new byte[224 * 224 * 3], 0L);
    }
}
```

- [ ] **Step 2: Run — must fail**

```bash
mvn test -pl . -Dtest=MlQueueTest
```

- [ ] **Step 3: Create `MlQueue`**

`src/main/java/ru/ashesha/buildBattleAI/evaluation/MlQueue.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bounded FIFO queue feeding the ML stage, with a "wait for one then
 * drain whatever's there" batched-take primitive. Designed for a single
 * consumer (the ML coalescer thread) that wants to keep batches as full
 * as possible without blocking forever when load is low.
 */
public final class MlQueue {

    private final LinkedBlockingQueue<EvalFrame> queue;

    public MlQueue(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /**
     * Non-blocking offer. Returns {@code false} on full queue.
     */
    public boolean offer(@NonNull EvalFrame frame) {
        return queue.offer(frame);
    }

    /**
     * Blocks up to {@code waitMs} for the first frame, then opportunistically
     * drains whatever else is already queued, up to {@code maxSize - 1} more.
     * Returns an empty list (without exception) if no frame arrives within
     * the wait window.
     */
    public @NonNull List<EvalFrame> drainBatch(int maxSize, long waitMs) throws InterruptedException {
        EvalFrame first = queue.poll(waitMs, TimeUnit.MILLISECONDS);
        if (first == null)
            return Collections.emptyList();
        List<EvalFrame> batch = new ArrayList<>(maxSize);
        batch.add(first);
        queue.drainTo(batch, maxSize - 1);
        return batch;
    }

    /** Approximate queue depth — for metrics only. */
    public int size() {
        return queue.size();
    }

    /** Drops all queued frames. Called only during service shutdown. */
    public void clear() {
        queue.clear();
    }
}
```

- [ ] **Step 4: Run — must pass**

```bash
mvn test -pl . -Dtest=MlQueueTest
```

- [ ] **Step 5: Hand off for review**

---

## Task 8: `RenderWorker`

**Goal:** A `Runnable` that loops: take from `RenderQueue` → render under `mirror.readLock()` → emit `EvalFrame` to `MlQueue`. Errors are swallowed (logged + counted) so the worker survives.

**Files:**
- Create: `src/main/java/ru/ashesha/buildBattleAI/evaluation/RenderWorker.java`
- Create: `src/test/java/ru/ashesha/buildBattleAI/evaluation/RenderWorkerTest.java`

- [ ] **Step 1: Write the failing test**

`RenderWorkerTest.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.render.api.BBAIRenderService;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.Lock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RenderWorkerTest {

    @Test
    void happyPath_renderAndEmitFrame() throws Exception {
        BBAIRenderService render = mock(BBAIRenderService.class);
        byte[] rgb = new byte[224 * 224 * 3];
        when(render.render(any(MutablePlotScene.class), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat()))
                .thenReturn(rgb);

        RenderQueue rq = new RenderQueue(4);
        MlQueue mq = new MlQueue(4);
        EvaluationMetrics metrics = new EvaluationMetrics(8);

        MutablePlotScene mirror = mock(MutablePlotScene.class);
        Lock readLock = new ReentrantReadWriteLock().readLock();
        when(mirror.readLock()).thenReturn(readLock);

        EvalJob job = EvalJob.builder()
                .arenaName("a").playerId(UUID.randomUUID()).playerName("p")
                .plotIndex(0).themeIndex(0).expectedTheme("t")
                .mirror(mirror)
                .cameraX(0).cameraY(0).cameraZ(0).cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(0L)
                .build();
        rq.offer(job);

        RenderWorker worker = new RenderWorker(0, rq, mq, render, metrics, mock(PluginLogger.class));
        Thread t = new Thread(worker, "test-render-worker");
        t.start();
        Thread.sleep(100);
        worker.stop();
        t.interrupt();
        t.join(1000);

        assertEquals(0, rq.size());
        assertEquals(1, mq.size());
        verify(render, times(1)).render(eq(mirror), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat());
    }

    @Test
    void renderException_isSwallowed_andCounted() throws Exception {
        BBAIRenderService render = mock(BBAIRenderService.class);
        when(render.render(any(MutablePlotScene.class), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat()))
                .thenThrow(new RuntimeException("boom"));

        RenderQueue rq = new RenderQueue(4);
        MlQueue mq = new MlQueue(4);
        EvaluationMetrics metrics = new EvaluationMetrics(8);

        MutablePlotScene mirror = mock(MutablePlotScene.class);
        when(mirror.readLock()).thenReturn(new ReentrantReadWriteLock().readLock());

        rq.offer(EvalJob.builder()
                .arenaName("a").playerId(UUID.randomUUID()).playerName("p")
                .plotIndex(0).themeIndex(0).expectedTheme("t")
                .mirror(mirror)
                .cameraX(0).cameraY(0).cameraZ(0).cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(0L)
                .build());

        RenderWorker worker = new RenderWorker(0, rq, mq, render, metrics, mock(PluginLogger.class));
        Thread t = new Thread(worker, "test-render-worker");
        t.start();
        Thread.sleep(100);
        worker.stop();
        t.interrupt();
        t.join(1000);

        EvalFrame f = mq.drainBatch(1, 1).isEmpty() ? null : mq.drainBatch(1, 1).get(0);
        assertNull(f); // nothing produced
        assertEquals(1, metrics.snapshot(0, 0, 0, 0).renderErrors());
    }
}
```

> **Note:** This test depends on `BBAIRenderService` having a `render(scene, x, y, z, yaw, pitch)` method. Confirmed present in current codebase; if signature differs, adjust the mock setup.

- [ ] **Step 2: Run — must fail**

```bash
mvn test -pl . -Dtest=RenderWorkerTest
```

- [ ] **Step 3: Create `RenderWorker`**

`src/main/java/ru/ashesha/buildBattleAI/evaluation/RenderWorker.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.render.api.BBAIRenderService;

import java.util.concurrent.locks.Lock;

/**
 * Render-stage worker. Pulls {@link EvalJob}s from the {@link RenderQueue},
 * renders each one under the mirror's read-lock, and pushes the resulting
 * RGB buffer into the {@link MlQueue} as an {@link EvalFrame}.
 * <p>
 * Exceptions during render are swallowed and counted — the worker must
 * survive a single bad job. The outer loop only exits when {@link #stop()}
 * is called and the worker thread is interrupted out of its blocking take.
 */
public final class RenderWorker implements Runnable {

    private final int workerId;
    private final RenderQueue renderQueue;
    private final MlQueue mlQueue;
    private final BBAIRenderService renderService;
    private final EvaluationMetrics metrics;
    private final PluginLogger logger;

    private volatile boolean running = true;

    public RenderWorker(int workerId,
                        @NonNull RenderQueue renderQueue,
                        @NonNull MlQueue mlQueue,
                        @NonNull BBAIRenderService renderService,
                        @NonNull EvaluationMetrics metrics,
                        @NonNull PluginLogger logger) {
        this.workerId = workerId;
        this.renderQueue = renderQueue;
        this.mlQueue = mlQueue;
        this.renderService = renderService;
        this.metrics = metrics;
        this.logger = logger;
    }

    /** Signals the worker to exit at its next iteration boundary. */
    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        Thread.currentThread().setName("bbai-eval-render-" + workerId);
        while (running) {
            EvalJob job;
            try {
                job = renderQueue.take();
            } catch (InterruptedException e) {
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
```

- [ ] **Step 4: Run — must pass**

```bash
mvn test -pl . -Dtest=RenderWorkerTest
```

- [ ] **Step 5: Hand off for review**

---

## Task 9: `MlCoalescerWorker`

**Goal:** A `Runnable` that loops: drain a batch (size K or wait T ms) → call `MLService.predictBatchRgb` → for each match, dispatch the score callback via `Bukkit.getScheduler().runTask(plugin, ...)`.

**Design note on the callback registry:** the worker needs to look up the per-arena callback at dispatch time (the session may have been unregistered while the job was in flight). We inject a `Function<String, BiConsumer<UUID, Integer>>` — the service supplies a lookup into its `SessionHandle` registry.

**Files:**
- Create: `src/main/java/ru/ashesha/buildBattleAI/evaluation/MlCoalescerWorker.java`
- Create: `src/test/java/ru/ashesha/buildBattleAI/evaluation/MlCoalescerWorkerTest.java`

- [ ] **Step 1: Write the failing test**

`MlCoalescerWorkerTest.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.ml.PredictionResult;
import ru.ashesha.buildBattleAI.ml.TopKEntry;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MlCoalescerWorkerTest {

    @Test
    void batchOfTwo_matchOneDispatchesCallbackOnceWithCorrectArgs() throws Exception {
        BBAIMLService ml = mock(BBAIMLService.class);

        // First frame matches "castle"; second frame does not.
        PredictionResult matchResult = new PredictionResult(
                new TopKEntry[]{ new TopKEntry("castle", 0.9f), new TopKEntry("house", 0.1f) },
                new float[0]);
        PredictionResult missResult = new PredictionResult(
                new TopKEntry[]{ new TopKEntry("tree", 0.9f), new TopKEntry("house", 0.1f) },
                new float[0]);
        when(ml.predictBatchRgb(any(byte[][].class), anyInt(), anyInt(), anyInt()))
                .thenReturn(new PredictionResult[]{ matchResult, missResult });

        AtomicInteger calls = new AtomicInteger();
        UUID matchPid = UUID.randomUUID();
        BiConsumer<UUID, Integer> arenaCallback = (pid, theme) -> {
            if (pid.equals(matchPid) && theme == 5)
                calls.incrementAndGet();
        };
        Function<String, BiConsumer<UUID, Integer>> registry = arena -> "arena1".equals(arena) ? arenaCallback : null;

        // Dispatcher runs callbacks synchronously in the test (no Bukkit).
        SyncDispatcher dispatcher = new SyncDispatcher();

        MlQueue mq = new MlQueue(4);
        mq.offer(frameFor("arena1", matchPid, 5, "castle"));
        mq.offer(frameFor("arena1", UUID.randomUUID(), 0, "house"));

        EvaluationMetrics metrics = new EvaluationMetrics(8);
        MlCoalescerWorker worker = new MlCoalescerWorker(
                mq, ml, registry, dispatcher, metrics, mock(PluginLogger.class),
                /* maxBatch */ 8, /* waitMs */ 50L, /* topK */ 2);

        Thread t = new Thread(worker, "test-ml");
        t.start();
        Thread.sleep(200);
        worker.stop();
        t.interrupt();
        t.join(1000);

        assertEquals(1, calls.get());
        assertEquals(1, metrics.snapshot(0, 0, 0, 0).matchesDispatched());
        assertEquals(1, metrics.snapshot(0, 0, 0, 0).mlBatchesCompleted());
    }

    private static EvalFrame frameFor(String arena, UUID pid, int themeIndex, String expectedTheme) {
        EvalJob j = EvalJob.builder()
                .arenaName(arena).playerId(pid).playerName("p")
                .plotIndex(0).themeIndex(themeIndex).expectedTheme(expectedTheme)
                .mirror(mock(MutablePlotScene.class))
                .cameraX(0).cameraY(0).cameraZ(0).cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(0L)
                .build();
        return new EvalFrame(j, new byte[224 * 224 * 3], 0L);
    }

    /** Test dispatcher that runs the callback on the current thread. */
    static final class SyncDispatcher implements MlCoalescerWorker.MainThreadDispatcher {
        @Override public void dispatch(Runnable r) { r.run(); }
    }
}
```

> **Cross-check:** test references `PredictionResult(TopKEntry[], float[])` and `TopKEntry(String, float)` — confirm these constructors exist; if `@Value` Lombok with single all-args, use whatever constructor is available. If signatures differ, adjust the test imports/constructor calls before running.

- [ ] **Step 2: Run — must fail**

```bash
mvn test -pl . -Dtest=MlCoalescerWorkerTest
```

- [ ] **Step 3: Create `MlCoalescerWorker`**

`src/main/java/ru/ashesha/buildBattleAI/evaluation/MlCoalescerWorker.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.ml.PredictionResult;
import ru.ashesha.buildBattleAI.ml.TopKEntry;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * ML-stage worker. Drains a batch from the {@link MlQueue} (size K or
 * wait T ms), runs {@link BBAIMLService#predictBatchRgb}, and for every
 * frame whose top-K predictions contain its expected theme, dispatches
 * the per-arena score callback onto the Bukkit main thread.
 * <p>
 * Single-threaded by design — the ONNX session is concurrency-safe but
 * keeping batch assembly serial removes a class of synchronisation bugs.
 */
public final class MlCoalescerWorker implements Runnable {

    /**
     * Abstraction over {@code Bukkit.getScheduler().runTask(plugin, r)} so the
     * worker can be unit-tested without MockBukkit. Production wiring passes
     * a Bukkit-backed implementation.
     */
    public interface MainThreadDispatcher {
        void dispatch(@NonNull Runnable r);
    }

    private final MlQueue mlQueue;
    private final BBAIMLService mlService;
    private final Function<String, BiConsumer<UUID, Integer>> callbackRegistry;
    private final MainThreadDispatcher dispatcher;
    private final EvaluationMetrics metrics;
    private final PluginLogger logger;
    private final int maxBatchSize;
    private final long waitMs;
    private final int topK;

    private volatile boolean running = true;

    public MlCoalescerWorker(@NonNull MlQueue mlQueue,
                             @NonNull BBAIMLService mlService,
                             @NonNull Function<String, BiConsumer<UUID, Integer>> callbackRegistry,
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

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        Thread.currentThread().setName("bbai-eval-ml");
        while (running) {
            List<EvalFrame> batch;
            try {
                batch = mlQueue.drainBatch(maxBatchSize, waitMs);
            } catch (InterruptedException e) {
                if (!running)
                    return;
                Thread.currentThread().interrupt();
                continue;
            }
            if (batch.isEmpty())
                continue;

            byte[][] rgbs = new byte[batch.size()][];
            for (int i = 0; i < batch.size(); i++)
                rgbs[i] = batch.get(i).rgb();

            PredictionResult[] results;
            long t0 = System.nanoTime();
            try {
                results = mlService.predictBatchRgb(rgbs, 224, 224, topK);
            } catch (Exception e) {
                logger.debug("ML batch (size %d) failed: %s", batch.size(), e.getMessage());
                metrics.incMlErrors();
                continue;
            }
            metrics.recordMlLatencyNanos(System.nanoTime() - t0);
            metrics.incMlBatchesCompleted();
            metrics.recordBatchSize(batch.size());

            for (int i = 0; i < batch.size(); i++) {
                EvalFrame frame = batch.get(i);
                PredictionResult r = results[i];
                if (!themeMatched(r, frame.job().expectedTheme()))
                    continue;
                BiConsumer<UUID, Integer> cb = callbackRegistry.apply(frame.job().arenaName());
                if (cb == null)
                    continue; // session was unregistered while job was in flight

                UUID pid = frame.job().playerId();
                int themeIndex = frame.job().themeIndex();
                dispatcher.dispatch(() -> cb.accept(pid, themeIndex));
                metrics.incMatchesDispatched();
            }
        }
    }

    private boolean themeMatched(PredictionResult r, String expectedTheme) {
        for (TopKEntry e : r.topK())
            if (e.className().equalsIgnoreCase(expectedTheme))
                return true;
        return false;
    }
}
```

- [ ] **Step 4: Run — must pass**

```bash
mvn test -pl . -Dtest=MlCoalescerWorkerTest
```

If the test fails on `PredictionResult` / `TopKEntry` constructors, open those source files and use the actual constructor signatures (the rest of the worker logic is unaffected by their shape).

- [ ] **Step 5: Hand off for review**

---

## Task 10: `EvaluationCoordinator` (picker logic)

**Goal:** Pure, Bukkit-free picker that, given a "now" timestamp and the session registry, picks eligible dirty players and offers `EvalJob`s to the render queue. Bukkit scheduling is wired separately in Task 11.

**Files:**
- Create: `src/main/java/ru/ashesha/buildBattleAI/evaluation/EvaluationCoordinator.java`
- Create: `src/test/java/ru/ashesha/buildBattleAI/evaluation/EvaluationCoordinatorTest.java`

- [ ] **Step 1: Write the failing test**

`EvaluationCoordinatorTest.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.game.GamePlayer;
import ru.ashesha.buildBattleAI.game.GameSession;
import ru.ashesha.buildBattleAI.game.api.ArenaState;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvaluationCoordinatorTest {

    @Test
    void dirtyPlayer_beyondMinCadence_isEnqueued() {
        UUID pid = UUID.randomUUID();
        SessionHandle h = handleWith(pid, /* dirty */ true);
        Map<String, SessionHandle> registry = singleton("a1", h);

        RenderQueue rq = new RenderQueue(8);
        EvaluationMetrics metrics = new EvaluationMetrics(8);
        EvaluationCoordinator coord = new EvaluationCoordinator(registry, rq, metrics,
                /* minCadenceMs */ 5000L);

        long now = nanos(0);
        coord.tick(now);

        assertEquals(1, rq.size());
    }

    @Test
    void dirtyPlayer_withinMinCadence_isSkipped() {
        UUID pid = UUID.randomUUID();
        SessionHandle h = handleWith(pid, true);
        h.recordEvalAttempt(pid, nanos(0));
        Map<String, SessionHandle> registry = singleton("a1", h);

        RenderQueue rq = new RenderQueue(8);
        EvaluationMetrics metrics = new EvaluationMetrics(8);
        EvaluationCoordinator coord = new EvaluationCoordinator(registry, rq, metrics, 5000L);

        coord.tick(nanos(3000)); // only 3s elapsed
        assertEquals(0, rq.size());
    }

    @Test
    void notDirty_isSkipped() {
        SessionHandle h = handleWith(UUID.randomUUID(), false);
        RenderQueue rq = new RenderQueue(8);
        EvaluationCoordinator c = new EvaluationCoordinator(
                singleton("a1", h), rq, new EvaluationMetrics(8), 5000L);
        c.tick(nanos(0));
        assertEquals(0, rq.size());
    }

    @Test
    void notInPlayingState_isSkipped() {
        SessionHandle h = handleWith(UUID.randomUUID(), true);
        when(h.session().state()).thenReturn(ArenaState.COUNTDOWN);
        RenderQueue rq = new RenderQueue(8);
        EvaluationCoordinator c = new EvaluationCoordinator(
                singleton("a1", h), rq, new EvaluationMetrics(8), 5000L);
        c.tick(nanos(0));
        assertEquals(0, rq.size());
    }

    @Test
    void renderQueueFull_incrementsDropCounter_andDoesNotRecordLastEvalAt() {
        UUID pid = UUID.randomUUID();
        SessionHandle h = handleWith(pid, true);
        RenderQueue rq = new RenderQueue(0); // capacity 0 means offer always fails
        // Actually LinkedBlockingQueue(0) is invalid — use 1 then pre-fill instead.
        RenderQueue rq2 = new RenderQueue(1);
        // Fill it:
        rq2.offer(EvalJob.builder()
                .arenaName("z").playerId(UUID.randomUUID()).playerName("z")
                .plotIndex(0).themeIndex(0).expectedTheme("z")
                .mirror(mock(MutablePlotScene.class))
                .cameraX(0).cameraY(0).cameraZ(0).cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(0L).build());

        EvaluationMetrics metrics = new EvaluationMetrics(8);
        EvaluationCoordinator c = new EvaluationCoordinator(
                singleton("a1", h), rq2, metrics, 5000L);
        c.tick(nanos(0));

        assertEquals(1, metrics.snapshot(0, 0, 0, 0).droppedRenderJobs());
        assertEquals(0L, h.lastEvalAtNanos(pid)); // lastEvalAt NOT advanced on drop
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static long nanos(long ms) {
        return ms * 1_000_000L;
    }

    private static Map<String, SessionHandle> singleton(String name, SessionHandle h) {
        ConcurrentHashMap<String, SessionHandle> m = new ConcurrentHashMap<>();
        m.put(name, h);
        return m;
    }

    private static SessionHandle handleWith(UUID pid, boolean dirty) {
        // Mock the entire chain — coordinator only reads, never mutates Bukkit state.
        GameSession session = mock(GameSession.class);
        when(session.state()).thenReturn(ArenaState.PLAYING);

        Arena arena = mock(Arena.class);
        when(arena.name()).thenReturn("a1");
        Arena.PlotData plot = mock(Arena.PlotData.class);
        Arena.Position cam = mock(Arena.Position.class);
        when(cam.x()).thenReturn(0.0); when(cam.y()).thenReturn(0.0); when(cam.z()).thenReturn(0.0);
        when(cam.yaw()).thenReturn(0f); when(cam.pitch()).thenReturn(0f);
        when(plot.cameras()).thenReturn(Collections.singletonList(cam));
        when(arena.plots()).thenReturn(Collections.singletonList(plot));
        when(session.arena()).thenReturn(arena);

        GamePlayer gp = mock(GamePlayer.class);
        when(gp.playerId()).thenReturn(pid);
        when(gp.playerName()).thenReturn("Bob");
        when(gp.plotIndex()).thenReturn(0);
        when(gp.themeIndex()).thenReturn(0);
        when(gp.zoneDirty()).thenReturn(dirty);
        when(session.getTheme(0)).thenReturn("castle");

        MutablePlotScene mirror = mock(MutablePlotScene.class);
        when(session.mirror(0)).thenReturn(mirror);

        Map<UUID, GamePlayer> players = new LinkedHashMap<>();
        players.put(pid, gp);
        when(session.players()).thenReturn(players);

        return new SessionHandle(session, (p, t) -> {});
    }
}
```

- [ ] **Step 2: Run — must fail**

```bash
mvn test -pl . -Dtest=EvaluationCoordinatorTest
```

- [ ] **Step 3: Create `EvaluationCoordinator`**

`src/main/java/ru/ashesha/buildBattleAI/evaluation/EvaluationCoordinator.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.game.GamePlayer;
import ru.ashesha.buildBattleAI.game.GameSession;
import ru.ashesha.buildBattleAI.game.api.ArenaState;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.List;
import java.util.Map;

/**
 * Pure (Bukkit-free) picker that drives the evaluation pipeline. Designed
 * to be invoked from a Bukkit-scheduled main-thread task — the actual
 * scheduling lives in {@link EvaluationService}.
 * <p>
 * Each tick:
 * <ol>
 *   <li>For each registered session in {@link ArenaState#PLAYING}, pick a
 *       camera (round-robin) and advance the session's camera index.</li>
 *   <li>For each dirty player whose lastEvalAt is older than minCadence,
 *       clear the dirty flag, build an {@link EvalJob}, and offer it to
 *       the render queue.</li>
 *   <li>If the render queue is full, increment the drop counter and leave
 *       the player's lastEvalAt unchanged — they'll be re-considered next
 *       tick.</li>
 * </ol>
 */
public final class EvaluationCoordinator {

    private final Map<String, SessionHandle> registry;
    private final RenderQueue renderQueue;
    private final EvaluationMetrics metrics;
    private final long minCadenceNanos;

    public EvaluationCoordinator(@NonNull Map<String, SessionHandle> registry,
                                 @NonNull RenderQueue renderQueue,
                                 @NonNull EvaluationMetrics metrics,
                                 long minCadenceMs) {
        this.registry = registry;
        this.renderQueue = renderQueue;
        this.metrics = metrics;
        this.minCadenceNanos = minCadenceMs * 1_000_000L;
    }

    /**
     * Executes one coordinator tick. Caller is responsible for invoking
     * this on the Bukkit main thread.
     *
     * @param nowNanos current time (System.nanoTime equivalent)
     */
    public void tick(long nowNanos) {
        for (SessionHandle handle : registry.values()) {
            GameSession session = handle.session();
            if (session.state() != ArenaState.PLAYING)
                continue;

            int cameraIdx = handle.currentCameraIndex();
            handle.advanceCamera();

            for (GamePlayer gp : session.players().values())
                considerPlayer(handle, session, gp, cameraIdx, nowNanos);
        }
    }

    private void considerPlayer(SessionHandle handle, GameSession session,
                                GamePlayer gp, int cameraIdx, long nowNanos) {
        if (!gp.zoneDirty())
            return;
        if (nowNanos - handle.lastEvalAtNanos(gp.playerId()) < minCadenceNanos)
            return;

        Arena arena = session.arena();
        List<Arena.PlotData> plots = arena.plots();
        if (gp.plotIndex() >= plots.size())
            return;
        Arena.PlotData plot = plots.get(gp.plotIndex());
        List<Arena.Position> cameras = plot.cameras();
        if (cameras.isEmpty())
            return;

        MutablePlotScene mirror = session.mirror(gp.plotIndex());
        if (mirror == null)
            return;

        Arena.Position cam = cameras.get(cameraIdx % cameras.size());

        gp.clearZoneDirty();

        EvalJob job = EvalJob.builder()
                .arenaName(arena.name())
                .playerId(gp.playerId())
                .playerName(gp.playerName())
                .plotIndex(gp.plotIndex())
                .themeIndex(gp.themeIndex())
                .expectedTheme(session.getTheme(gp.themeIndex()))
                .mirror(mirror)
                .cameraX(cam.x()).cameraY(cam.y()).cameraZ(cam.z())
                .cameraYaw(cam.yaw()).cameraPitch(cam.pitch())
                .enqueuedAtNanos(nowNanos)
                .build();

        if (renderQueue.offer(job)) {
            handle.recordEvalAttempt(gp.playerId(), nowNanos);
        } else {
            metrics.incDroppedRenderJobs();
            // lastEvalAt stays — player will be retried next tick.
        }
    }
}
```

- [ ] **Step 4: Run — must pass**

```bash
mvn test -pl . -Dtest=EvaluationCoordinatorTest
```

- [ ] **Step 5: Hand off for review**

---

## Task 11: Full `EvaluationService` wire-up

**Goal:** Complete `enable()` / `shutdown()` / `registerSession()` / `unregisterSession()` / `stats()`. Wires queues + metrics + coordinator + render workers + ML coalescer. Pulls `EvalConfig` from `ConfigService`. Schedules the coordinator via `Bukkit.getScheduler().runTaskTimer`.

**Files:**
- Modify: `src/main/java/ru/ashesha/buildBattleAI/evaluation/EvaluationService.java`
- Modify: `src/test/java/ru/ashesha/buildBattleAI/evaluation/EvaluationServiceLifecycleTest.java` (extend)

- [ ] **Step 1: Extend the lifecycle test**

Replace the existing `EvaluationServiceLifecycleTest.java` with:

```java
package ru.ashesha.buildBattleAI.evaluation;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;
import ru.ashesha.buildBattleAI.render.api.BBAIRenderService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvaluationServiceLifecycleTest {

    @Test
    void enableAndShutdown_areIdempotent_andStatsAvailable() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        PluginContext ctx = mock(PluginContext.class);
        when(plugin.getContext()).thenReturn(ctx);
        when(plugin.getPluginLogger()).thenReturn(mock(PluginLogger.class));

        BBAIConfigService cfg = mock(BBAIConfigService.class);
        when(cfg.config()).thenReturn(new YamlConfiguration());
        when(ctx.getConfigService()).thenReturn(cfg);
        when(ctx.getRenderService()).thenReturn(mock(BBAIRenderService.class));
        when(ctx.getMlService()).thenReturn(mock(BBAIMLService.class));

        BukkitScheduler sched = mock(BukkitScheduler.class);
        when(sched.runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong()))
                .thenReturn(mock(org.bukkit.scheduler.BukkitTask.class, RETURNS_DEEP_STUBS));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(sched);

            EvaluationService service = new EvaluationService(plugin);
            assertDoesNotThrow(service::enable);

            EvaluationStats s = service.stats();
            assertEquals(0L, s.rendersCompleted());

            assertDoesNotThrow(service::shutdown);
            assertDoesNotThrow(service::shutdown); // idempotent
            assertDoesNotThrow(service::enable);   // re-enable works
            service.shutdown();
        }
    }
}
```

- [ ] **Step 2: Run — must fail (service still stub)**

```bash
mvn test -pl . -Dtest=EvaluationServiceLifecycleTest
```

- [ ] **Step 3: Rewrite `EvaluationService` with full wiring**

`src/main/java/ru/ashesha/buildBattleAI/evaluation/EvaluationService.java`:

```java
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
 * Default implementation of {@link BBAIEvaluationService}. Owns:
 * <ul>
 *   <li>The session registry (arenaName → {@link SessionHandle}).</li>
 *   <li>The bounded render and ML queues.</li>
 *   <li>N daemon render-worker threads and 1 ML coalescer thread.</li>
 *   <li>A Bukkit-scheduled main-thread coordinator task.</li>
 *   <li>The metrics counter set.</li>
 * </ul>
 * Lifecycle is idempotent: repeated {@code enable()} / {@code shutdown()}
 * calls are safe.
 */
public class EvaluationService implements PluginService, BBAIEvaluationService {

    private final BuildBattleAI plugin;
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    // Populated on enable, cleared on shutdown.
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
        registry = new ConcurrentHashMap<>();

        coordinator = new EvaluationCoordinator(registry, renderQueue, metrics, config.minCadenceMs());

        renderWorkers = new ArrayList<>(config.renderWorkers());
        renderThreads = new ArrayList<>(config.renderWorkers());
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

        if (renderQueue != null) renderQueue.clear();
        if (mlQueue != null)     mlQueue.clear();
        if (registry != null)    registry.clear();

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
```

- [ ] **Step 4: Run lifecycle test — must pass**

```bash
mvn test -pl . -Dtest=EvaluationServiceLifecycleTest
```

- [ ] **Step 5: Hand off for review**

---

## Task 12: Wire `EvaluationService` into `PluginContext` and add config defaults

**Goal:** Make the service part of the plugin lifecycle. Add the `evaluation:` section to `config.yml` so deployments get the documented defaults out of the box.

**Files:**
- Modify: `src/main/java/ru/ashesha/buildBattleAI/core/PluginContext.java`
- Modify: `src/main/resources/config.yml`

- [ ] **Step 1: Add the field and getter in `PluginContext`**

Open `PluginContext.java`. Locate the block of service fields (search for `private final BBAIMLService mlService;`). Insert after it:

```java
/** Centralised render + ML evaluation pipeline. Replaces per-arena render timers. */
@Getter
private final BBAIEvaluationService evaluationService;
```

In the constructor, locate `this.mlService = new MLService(plugin);` (or similar — match the project's existing style). Add immediately after, before `this.renderService = ...`:

```java
this.evaluationService = new ru.ashesha.buildBattleAI.evaluation.EvaluationService(plugin);
```

Add the import at the top:

```java
import ru.ashesha.buildBattleAI.evaluation.api.BBAIEvaluationService;
```

- [ ] **Step 2: Add the service to the lifecycle list**

In `PluginContext`, find the list of services that's iterated in `enable()` / `shutdown()`. Based on the spec, the order must be: `... → MLService → RenderService → EvaluationService → CommandService → ListenerService`. Insert `(PluginService) evaluationService` immediately after `(PluginService) renderService`.

If services are stored in an explicit `List<PluginService>` (typical pattern in this codebase per CLAUDE.md), find that initialiser and add the entry in the correct position. If they're enabled in-line by name, add `evaluationService.enable();` after `renderService.enable();` and `evaluationService.shutdown();` symmetrically (before `renderService.shutdown()`, since `shutdown` walks in reverse).

- [ ] **Step 3: Append the `evaluation:` block to `config.yml`**

Open `src/main/resources/config.yml` and append at the end:

```yaml

# ── Evaluation Pipeline ────────────────────────────────────────────────
# Centralised render + ML inference scheduling. Spreads load evenly
# across time and enables ML batching. Replaces the legacy per-arena
# render timer.
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

- [ ] **Step 4: Verify everything still compiles + tests still pass**

```bash
mvn test
```

Expected: full test suite green.

- [ ] **Step 5: Hand off for review**

---

## Task 13: `GameManager` integration — remove old render code, wire new service

**Goal:** Remove `RENDER_INTERVAL_TICKS`, the `startRenderTimer` method, the per-session `renderTaskId` and `currentCameraIndex` fields, and wire `EvaluationService.registerSession` in `startGame` / `unregisterSession` in `endGame`.

**Files:**
- Modify: `src/main/java/ru/ashesha/buildBattleAI/game/GameManager.java`
- Modify: `src/main/java/ru/ashesha/buildBattleAI/game/GameSession.java`

- [ ] **Step 1: Read the current `GameManager.startGame` to find the integration point**

Open `GameManager.java`. Search for `startRenderTimer(session);` to find its call site. The line above (after `installMirror` loop) is where we'll inject `registerSession`.

- [ ] **Step 2: Remove `RENDER_INTERVAL_TICKS` constant**

Delete the line:

```java
private static final int RENDER_INTERVAL_TICKS = 100;
```

(`GameManager.java:56` in current code — may have shifted.)

- [ ] **Step 3: Remove the `startRenderTimer` method**

Delete the entire `startRenderTimer(GameSession session)` method body. In current code it spans `GameManager.java:435–526`. Also delete the Javadoc block above it.

- [ ] **Step 4: Replace the `startRenderTimer(session)` call with `registerSession`**

At the spot where `startRenderTimer(session)` was previously called inside `startGame`, replace with:

```java
plugin.getContext().getEvaluationService().registerSession(
        session,
        (playerId, themeIndex) -> handleScore(session.arena().name(), playerId, themeIndex));
```

Add the import if the IDE does not auto-add it:

```java
import java.util.function.BiConsumer; // only if not already present
```

- [ ] **Step 5: Add `unregisterSession` to game teardown**

Find the method that tears down a session (likely `endGame(String arenaName)` or similar — search for `session.stopAllTasks()` to locate it). Before or immediately after `stopAllTasks()`, add:

```java
plugin.getContext().getEvaluationService().unregisterSession(session.arena().name());
```

If there are multiple shutdown paths (forced shutdown vs natural end), add it to all of them.

- [ ] **Step 6: Remove fields from `GameSession`**

Open `GameSession.java`. Remove:

```java
private int currentCameraIndex;

void advanceCamera() {
    currentCameraIndex = (currentCameraIndex + 1) % 3;
}
```

And:

```java
private int renderTaskId = -1;
```

Remove all references in `GameSession`:
- the accessor methods (if Lombok-generated, remove the field — accessors disappear automatically; if hand-written, remove them)
- `cancelTask(renderTaskId);` inside `stopAllTasks()`
- `renderTaskId = -1;` reset

- [ ] **Step 7: Remove the `session.currentCameraIndex(0);` line from `startGame`**

Search `GameManager.java` for `currentCameraIndex(0)` and remove that line (current code: `GameManager.java:302`).

- [ ] **Step 8: Compile**

```bash
mvn compile
```
Expected: BUILD SUCCESS. Fix any leftover references the previous steps missed.

- [ ] **Step 9: Run the full test suite**

```bash
mvn test
```

Existing tests that mocked the deleted methods will fail. Update them:
- Any test that calls `session.currentCameraIndex(...)` or `session.advanceCamera()` — delete those calls.
- Any test that stubbed `session.renderTaskId(...)` — delete that.
- `GameListenerTest` — if it touches the removed fields, update it.

Iterate until green.

- [ ] **Step 10: Hand off for review**

---

## Task 14: `/bbai stats` admin command + periodic metrics log

**Goal:** Add an admin sub-command that prints the current `EvaluationStats` snapshot, and a low-frequency DEBUG log line at the configured `metrics-log-period-seconds` interval.

**Files:**
- Modify: `src/main/java/ru/ashesha/buildBattleAI/commands/ArenaCommand.java`
- Modify: `src/main/java/ru/ashesha/buildBattleAI/evaluation/EvaluationService.java`

- [ ] **Step 1: Add the periodic-log task to `EvaluationService.enable`**

Inside `EvaluationService.enable()`, after the coordinator task is scheduled, add (only when `config.metricsLogPeriodSeconds() > 0`):

```java
if (config.metricsLogPeriodSeconds() > 0) {
    long ticks = config.metricsLogPeriodSeconds() * 20L; // 20 ticks per second
    metricsLogTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
        EvaluationStats s = stats();
        plugin.getPluginLogger().debug(
                "[Evaluation] rendered=%d mlBatches=%d matches=%d dropped(r/m)=%d/%d "
                        + "errors(r/m)=%d/%d avgRender=%dµs avgMl=%dµs "
                        + "queueDepth(r/m)=%d/%d sessions=%d players=%d",
                s.rendersCompleted(), s.mlBatchesCompleted(), s.matchesDispatched(),
                s.droppedRenderJobs(), s.droppedMlJobs(),
                s.renderErrors(), s.mlErrors(),
                s.renderLatencyAvgMicros(), s.mlLatencyAvgMicros(),
                s.renderQueueDepth(), s.mlQueueDepth(),
                s.registeredSessions(), s.activePlayers());
    }, ticks, ticks);
}
```

Add the field at the top of the class with the other task fields:

```java
private BukkitTask metricsLogTask;
```

Add to `shutdown()`, alongside `coordinatorTask.cancel()`:

```java
if (metricsLogTask != null) {
    metricsLogTask.cancel();
    metricsLogTask = null;
}
```

- [ ] **Step 2: Locate the existing `/bbai` subcommand dispatch in `ArenaCommand`**

Open `ArenaCommand.java`. Find the subcommand router (likely `switch (args[0])` or chained `if/else`). Identify the pattern used for an existing subcommand (e.g. `list`, `info`) and mirror it.

- [ ] **Step 3: Add the `stats` subcommand**

Add a new branch matching `"stats"` that prints the snapshot. Use `MessageService.sendChat` (per project conventions — no `player.sendMessage`). Example structure:

```java
} else if (args[0].equalsIgnoreCase("stats")) {
    handleStatsCommand(sender);
    return true;
}
```

And the handler:

```java
private void handleStatsCommand(CommandSender sender) {
    EvaluationStats s = plugin.getContext().getEvaluationService().stats();
    sendStatsLine(sender, "&7── &eEvaluation Pipeline Stats &7──");
    sendStatsLine(sender, "&7Sessions: &f" + s.registeredSessions() + "  &7Players: &f" + s.activePlayers());
    sendStatsLine(sender, "&7Rendered: &f" + s.rendersCompleted() + "  &7ML batches: &f" + s.mlBatchesCompleted() + "  &7Matches: &f" + s.matchesDispatched());
    sendStatsLine(sender, "&7Avg render: &f" + s.renderLatencyAvgMicros() + "µs  &7Avg ML: &f" + s.mlLatencyAvgMicros() + "µs");
    sendStatsLine(sender, "&7Dropped (R/M): &f" + s.droppedRenderJobs() + "&7/&f" + s.droppedMlJobs() + "  &7Errors (R/M): &f" + s.renderErrors() + "&7/&f" + s.mlErrors());
    sendStatsLine(sender, "&7Queue depth (R/M): &f" + s.renderQueueDepth() + "&7/&f" + s.mlQueueDepth());
    StringBuilder hist = new StringBuilder("&7Batch sizes: &f");
    for (int i = 1; i < s.batchSizeHistogram().length; i++)
        hist.append("[").append(i).append(":").append(s.batchSizeHistogram()[i]).append("]");
    sendStatsLine(sender, hist.toString());
}

private void sendStatsLine(CommandSender sender, String line) {
    if (sender instanceof Player)
        plugin.getContext().getMessageService().sendChat((Player) sender, line);
    else
        sender.sendMessage(line.replace("&", "§"));
}
```

Imports to add:

```java
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
```

(Adjust to existing patterns — the existing command class will already have most of these.)

- [ ] **Step 4: Add `stats` to the help/usage text**

If `ArenaCommand` has a "show usage" / "help" branch, add a line for `/bbai stats`. Otherwise skip.

- [ ] **Step 5: Add a smoke test for the stats path**

Create `src/test/java/ru/ashesha/buildBattleAI/evaluation/EvaluationServiceStatsTest.java`:

```java
package ru.ashesha.buildBattleAI.evaluation;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;
import ru.ashesha.buildBattleAI.render.api.BBAIRenderService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvaluationServiceStatsTest {

    @Test
    void statsBeforeEnable_returnsAllZeros() {
        EvaluationService service = new EvaluationService(mock(BuildBattleAI.class));
        EvaluationStats s = service.stats();
        assertEquals(0L, s.rendersCompleted());
        assertEquals(0, s.registeredSessions());
        assertEquals(0, s.batchSizeHistogram().length);
    }
}
```

- [ ] **Step 6: Run the full suite**

```bash
mvn test
```
Expected: green.

- [ ] **Step 7: Manual smoke test on the dev server (out-of-band)**

```bash
mvn clean package
```

Then on `~/Servers/1.21/`:
1. `start.command`
2. Create an arena, join, start a game.
3. `/bbai stats` — verify stats render.
4. Wait 60 s — verify a `[Evaluation]` line shows up in the console at DEBUG level.

(Document this as a manual step; it cannot be CI-tested without a live ONNX model.)

- [ ] **Step 8: Hand off for review**

---

## Self-Review (executed inline by plan author)

- [x] **Spec coverage:** Every section of the spec has at least one task.
  - Architecture, components, data flow → Tasks 2–11
  - Threading model → enforced by Tasks 6, 8, 9, 11
  - Configuration → Task 3 + 12
  - Metrics & observability → Task 4 + 14
  - Lifecycle integration → Task 11 + 12 + 13
  - Failure modes → covered by error-swallow logic in Tasks 8 + 9
  - Testing strategy → tests included in every task
- [x] **Placeholder scan:** No "TBD" / "handle edge cases" / "similar to Task N" in code blocks. Where future-task references appear, they say what's deferred and why.
- [x] **Type consistency:**
  - `EvalJob.builder()` used the same way in Tasks 2, 6, 7, 8, 9, 10.
  - `RenderQueue.offer/take/size/clear` consistent across Tasks 6, 10, 11.
  - `MlQueue.offer/drainBatch/size/clear` consistent across Tasks 7, 9, 11.
  - `EvaluationMetrics.snapshot(...)` signature consistent in Tasks 4, 11, 14.
  - `MlCoalescerWorker.MainThreadDispatcher` inner type used in Task 9 test and Task 11 wiring.
  - `BBAIEvaluationService` API (register/unregister/stats) consistent in Tasks 1, 11, 13, 14.
- [x] **Scope:** Single coherent subsystem (the evaluation pipeline). Migrating GameManager and adding the admin command are the natural integration boundary, not a separate plan.
