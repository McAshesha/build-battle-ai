# Test Coverage Expansion — Design

**Date:** 2026-06-07
**Status:** Approved (user pre-approved all sections inline)
**Scope:** Expand non-unit test coverage (smoke, integration, e2e, ml-it, bench, stress) across the BuildBattleAI plugin.

## 1. Motivation

The project has solid unit-test coverage (~65 files across 21 packages). Non-unit test kinds — smoke, integration, e2e, ml-it, benchmarks — have been bootstrapped but are very thin: 2 smoke, 1 ml-it forward pass, 3 e2e drivers that only verify "plugin enables and `/list` works", 2 JMH benches (renderer/palette only). The risk profile of the project (concurrent evaluation pipeline, multi-version Bukkit, ONNX inference, dual data backend) demands much wider coverage on the non-unit tiers.

The objective is to expand coverage **risk-driven**, not module-driven: each new test answers a concrete, currently-uncovered failure mode, and is realised in the test kind that catches that failure mode most cheaply.

## 2. Test Taxonomy

We move test selection from pattern-based Surefire `<includes>` to **JUnit 5 `@Tag`-based selection**. Existing pattern-based profiles (`e2e`, `ml-it`) keep working in parallel — every test gets one main `@Tag` (and possibly `nightly-only` as a secondary tag), and the existing pattern profiles are augmented to also use `<groups>`.

### Tags

| Tag | Purpose | Per-test cost | CI tier |
|---|---|---|---|
| `unit` | Existing in-process unit tests | <100ms | default + PR + nightly |
| `smoke` | Build/wiring integrity: plugin enables, basic command works | <500ms | PR + nightly |
| `integration` | In-JVM, MockBukkit + real PluginContext, ≥2 services together | 0.5–5s | PR + nightly |
| `e2e` | Subprocess Paper/Purpur server with full game scenarios | 60–240s | nightly + manual |
| `ml-it` | Real ONNX forward pass | 5–30s | PR (fast subset) + nightly (full) |
| `bench` | JMH measurements | 30s–10min per class | nightly |
| `stress` | Concurrency stress, lifecycle stress, leak detection | 5–60s | nightly |
| `nightly-only` | Secondary tag — excludes a test from PR-gate | — | nightly only |

### Directory layout (additions)

```
src/test/java/ru/ashesha/buildBattleAI/
├── ...existing unit packages (unchanged)...
├── smoke/
├── integration/{evaluation,game,ml,data,arena,config}/
├── stress/
└── e2e/  (extended)

src/jmh/java/ru/ashesha/buildBattleAI/bench/
├── ...existing RendererBenchmark, PaletteBenchmark...
├── EvaluationPipelineBenchmark.java
├── MlBatchingBenchmark.java
├── MlQueueBenchmark.java
└── MlServiceWarmupBenchmark.java

src/test/resources/ml/fixtures/   (new — 5-8 synthetic 224×224 RGB images for ML-IT)
.github/perf-baselines/jmh.json   (new — JMH baseline for regression detection)
```

### Maven profiles

| Profile | `<groups>` | `<excludedGroups>` | Notes |
|---|---|---|---|
| default (current) | — | `e2e,bench,stress,ml-it,nightly-only` | Updated — current excludes pattern-based; we keep the pattern excludes AND add tag excludes |
| `smoke` (new) | `smoke` | — | One-shot smoke run |
| `integration` (new) | `integration` | — | One-shot integration run |
| `pr-gate` (new, aggregate) | — | `e2e,bench,stress,nightly-only` | What runs in PR CI |
| `nightly` (new, aggregate) | — | `bench` | Bench runs via `exec:java`, not Surefire |
| `e2e` (existing) | `e2e` | — | Existing pattern includes preserved as fallback |
| `ml-it` (existing) | `ml-it` | — | Existing pattern includes preserved |
| `stress` (new) | `stress` | — | Standalone stress run |
| `bench` (existing) | — | — | Unchanged, JMH via `exec` |

### New test dependencies (test scope)

```xml
<dependency>
  <groupId>org.awaitility</groupId>
  <artifactId>awaitility</artifactId>
  <version>4.2.2</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>testcontainers</artifactId>
  <version>1.20.4</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>junit-jupiter</artifactId>
  <version>1.20.4</version>
  <scope>test</scope>
</dependency>
```

No new runtime dependencies.

### JMH baseline mechanism

- Each JMH bench is run with `-rf json -rff target/jmh-<bench>.json`.
- The baseline `bench-baseline.json` lives in `.github/perf-baselines/`.
- Nightly compares fresh vs baseline. Threshold: **+25% on p50, +35% on p99** triggers a `perf-regression` GitHub issue.
- Baseline updates via a manual `update-perf-baseline.yml` workflow (`workflow_dispatch`).

## 3. Risk Catalog

Each row is a concrete, currently-uncovered failure mode. Test classes/methods listed are the **minimum** units that should cover the risk. `Crit` = `Y` (critical), `M` (medium), `N` (nice-to-have).

### Evaluation pipeline

| ID | Risk | Test kind | Tier | Crit | Target test |
|---|---|---|---|---|---|
| EVAL-001 | RenderQueue.offer() failure must roll back `pending` dedup entry; concurrent coordinator+worker race | integration | PR | Y | `integration/evaluation/RenderQueueDedupConcurrencyIT.offerFailureRollsBackDedup` |
| EVAL-002 | `EvalFrame.rgb` must be a fresh buffer per frame; render workers must NOT reuse | integration | PR | Y | `integration/evaluation/RenderWorkerBufferOwnershipIT.everyFrameGetsFreshBuffer` |
| EVAL-003 | Unregistering a session mid-flight: in-flight ML batch must still complete; missing arena lookup is silent skip, batch is still counted | integration | PR | Y | `integration/evaluation/EvaluationCoordinatorLifecycleIT.unregisterDuringInflight` |
| EVAL-004 | `EvaluationStats` snapshot remains internally consistent under concurrent load (rendersCompleted ≥ matchesDispatched) | stress | nightly | Y | `stress/EvaluationStatsConcurrencyStress.snapshotMonotonicUnderLoad` |
| EVAL-005 | RenderQueue-full drop: counter increments AND `lastEvalAtNanos` is NOT updated (otherwise next tick re-enqueues immediately = busy loop) | integration | PR | Y | `integration/evaluation/EvaluationCoordinatorLifecycleIT.queueFullDoesNotUpdateLastEvalAt` |
| EVAL-006 | Throwing score-callback does NOT kill `MlCoalescerWorker`; metrics still increment | integration | PR | Y | `integration/evaluation/MlCoalescerCallbackResilienceIT.throwingCallbackSurvives` |
| EVAL-010 | `stats()` is safe to call at any moment, including during concurrent shutdown | stress | nightly | Y | `stress/EvaluationServiceShutdownStress.statsConcurrentWithShutdown` |
| EVAL-011 | Dropped job is re-considered in the NEXT coordinator tick (not the one after) | integration | PR | Y | `integration/evaluation/EvaluationCoordinatorLifecycleIT.droppedJobReenqueuedNextTick` |
| EVAL-012 | Multiple consumers of `RenderQueue.take()` do not duplicate or lose jobs (dedup contract holds) | stress | nightly | Y | `stress/RenderQueueMultiConsumerStress.multipleConsumersDoNotDuplicate` |

### Game

| ID | Risk | Test kind | Tier | Crit | Target test |
|---|---|---|---|---|---|
| GAME-01 | Cancelled countdown does not fire delayed `startGame` | integration | PR | Y | `integration/game/CountdownCancellationIT.cancelledCountdownDoesNotStart` |
| GAME-02 | Rapid disconnect/rejoin of same UUID produces no duplicate in `session.players()` | integration | PR | Y | `integration/game/PlayerRejoinIT.rejoinDoesNotDuplicate` |
| GAME-03 | Theme assignment wraps correctly when themes.count < plots.count | integration | PR | M | `integration/game/ThemeAssignmentIT.themesShorterThanPlots` |
| GAME-04 | `PlayerSnapshot` is a deep clone — modifying snapshot does not affect player; off-hand handled on 1.9+ | integration | PR | Y | `integration/game/PlayerSnapshotIntegrityIT.snapshotIsDeepClone` |
| GAME-05 | Stale score-callback (themeIndex changed before callback runs) is silently ignored | integration | PR | Y | `integration/game/ScoreCallbackStalenessIT.staleCallbackIgnored` |
| GAME-07 | `forceEndSession()` unregisters from `EvaluationService` BEFORE cancelling timers (no late callbacks into null session) | integration | PR | Y | `integration/game/ForceEndSessionOrderingIT.unregisterBeforeCancel` |
| GAME-08 | Multi-arena concurrent zone clears do not serialise on shared world lock; multi-arena scores are independent | stress | nightly | M | `stress/MultiArenaConcurrentZoneClearStress.concurrentClearsAreIndependent` |
| GAME-09 | Plugin reload cancels all active countdown/game-tick timers — no orphan firing post-reload | integration | PR | M | `integration/game/PluginReloadDuringCountdownIT.reloadCancelsAllTimers` |
| GAME-11 | build-time expiry is atomic: if `mirror.clearAll()` throws, themeIndex and buildTime are either both-rolled-back or both-committed | integration | PR | M | `integration/game/BuildTimeExpiryAtomicityIT.expiryIsAtomic` |

### ML

| ID | Risk | Test kind | Tier | Crit | Target test |
|---|---|---|---|---|---|
| ML-01 | Provider chain falls back when warmup at batch=4 fails on the preferred provider | integration (mock OrtSession) | PR | Y | `integration/ml/ProviderFallbackIT.warmupFailureTriggersFallback` |
| ML-02 | Disabled mode: all 12 public methods (single/batch/RGB/TTA variants) return safe zero-results | integration | PR | Y | `integration/ml/DisabledModeFullCoverageIT.allMethodsSafeWhenDisabled` (parameterised over methods) |
| ML-05 | Concurrent `embedBatchRgb` from N threads × M iterations: no NaN, no race, all results L2-normalised | stress | nightly | Y | `stress/MlConcurrentInferenceStress.concurrentInferenceProducesValidEmbeddings` |
| ML-06 | JMH: latency per batch={1,4,8,16}, with/without TTA, baseline recorded | bench | nightly | M | `bench/MlBatchingBenchmark` |
| ML-07 | `enable()`/`shutdown()` × 100 — no native-handle leak (OrtSession closed, RSS bounded) | stress | nightly | Y | `stress/MlServiceLifecycleLeakStress.lifecycleCycleNoLeak` |
| ML-08 | Corrupted `centroids.json` (truncated / wrong dim / NaN) → graceful fallback OR explicit fail-fast with logged error | integration | PR | M | `integration/ml/CentroidsJsonRobustnessIT.corruptedCentroidsHandled` |
| ML-INT-EXT | Real ONNX: (a) ranking sanity on synthetic fixtures, (b) batched inference matches single-call inference, (c) TTA improves top-K hit-rate over fixture set | ml-it | PR: (a)+(b); nightly: (c) | Y | extend `ml/MLIntegrationTest` with 3 new `@Test`s; method (c) tagged `@Tag("nightly-only")` |

### Data

| ID | Risk | Test kind | Tier | Crit | Target test |
|---|---|---|---|---|---|
| DATA-01 | Corrupted JSON: `PluginLogger.warn` is emitted (not stderr), in-memory cache starts empty, plugin does NOT crash | integration | PR | Y | `integration/data/CorruptedJsonRecoveryIT.warnOnCorruption` |
| DATA-02 | Autosave runnable scheduled at the moment of `shutdown()`: no NPE if provider has been nulled | stress | nightly | M | `stress/DataServiceAutosaveShutdownRaceStress.shutdownDuringFlush` |
| DATA-04 | Disk full / read-only filesystem: `IOException` is escalated to `PluginLogger.error` (currently goes only to stderr — small production change required alongside the test); in-memory writes still accepted | integration (jimfs or temp tmpfs) | PR | Y | `integration/data/DiskFailureEscalationIT.diskFullEscalatesToLogger` |
| DATA-06 | Ignite thin-client CRUD against a Testcontainers Ignite 2.16.0 server; reconnect after kill | integration (Testcontainers) | nightly | Y | `integration/data/IgniteThinClientIT.crudAndReconnect` (tagged `nightly-only` due to Docker requirement) |

### Arena / World

| ID | Risk | Test kind | Tier | Crit | Target test |
|---|---|---|---|---|---|
| ARENA-01 | YAML validation reports ALL missing fields, doesn't short-circuit | integration | PR | M | `integration/arena/YamlValidationCompletenessIT.allMissingFieldsReported` |
| ARENA-03 | Non-linear wizard: tabs filled in any order; `isComplete()` correct after out-of-order edits | integration | PR | M | `integration/arena/WizardNonLinearIT.outOfOrderTabsCompleteCorrectly` |
| ARENA-06 | `deleteArena` is atomic: world unloaded, all entities cleaned, players kicked, state map cleared — in that order | integration | PR | Y | `integration/arena/ArenaDeletionAtomicityIT.deletionFullyCleansUp` |
| WORLD-01 | `createEmptyWorld` is idempotent; `trackedWorlds` stays consistent under repeated calls | integration | PR | M | (same class as ARENA-06) `integration/arena/ArenaDeletionAtomicityIT.createIsIdempotent` |

### Thin layer (message / entity / config / commands)

| ID | Risk | Test kind | Tier | Crit | Target test |
|---|---|---|---|---|---|
| THIN-MSG | All 6 micro-services initialise without exception on simulated 1.8/1.16/1.20/1.21 | smoke | PR | Y | `smoke/MessageServiceSmokeTest.allVersionsEnableCleanly` |
| THIN-ENT | NPC + Hologram + Picture create/destroy round-trip; entity-id allocator is monotonic, no collisions across services | smoke | PR | M | `smoke/EntityServicesSmokeTest.createDestroyRoundTrip` |
| THIN-CFG | Config reload (`shutdown` → `enable`) preserves arena configs persisted to disk | integration | PR | M | `integration/config/ConfigReloadIT.arenaConfigsSurviveReload` |
| THIN-CMD | `/bbai stats` produces a parseable `EvaluationStats` line; does not throw under concurrent load | smoke | PR | M | `smoke/BbaiStatsCommandSmokeTest.statsCommandSmoke` |

### Lifecycle / E2E

| ID | Risk | Test kind | Tier | Crit | Target test |
|---|---|---|---|---|---|
| PLUGIN-SMOKE-RELOAD | `enable → reload × 3 → shutdown` — no thread leak, no service in zombie state | smoke | PR | Y | `smoke/PluginEnableSmokeTest.reloadCyclesAreClean` |
| E2E-GAME-FULL | 2 players join → countdown → build matches a theme → score increments → game ends → winner announced | e2e | nightly | Y | `e2e/FullGameSessionE2ETest` (Purpur 1.21 only — ML required) |
| E2E-MULTI-ARENA | 2 arenas play concurrently; scores in arena A do not leak into arena B | e2e | nightly | M | `e2e/MultiArenaIsolationE2ETest` |
| E2E-FORCE-STOP | Force shutdown during PLAYING: snapshots restored, mirror clean, world unmodified | e2e | nightly | M | `e2e/ForceShutdownDuringPlayE2ETest` |

## 4. CI workflow changes

### `ci.yml` (PR-gate, target ≤ 12 min)

Replace `mvn -B verify` step with:

```yaml
- name: PR test gate (unit + smoke + integration + fast ml-it)
  run: mvn -B -ntp clean verify -P pr-gate

- name: Fast E2E (existing Paper 1.8 + Purpur 1.21 plugin-enable smoke)
  run: mvn -B -ntp surefire:test -P e2e -Dgroups="e2e & !nightly-only"
```

E2E tier policy:
- **In PR (`!nightly-only`):** the two existing drivers (`Paper18E2ETest`, `Purpur121E2ETest`). They catch JAR-shading / plugin-yml / native-loader breakage cheaply; their 240s startup is acceptable once per PR.
- **Nightly only (`nightly-only`):** the three new full-scenario E2E tests (`FullGameSessionE2ETest`, `MultiArenaIsolationE2ETest`, `ForceShutdownDuringPlayE2ETest`) — each adds another 60–120s of in-game scripting.

Both existing drivers keep their `@Tag("e2e")` and are NOT tagged `nightly-only`; the new ones get both `@Tag("e2e")` and `@Tag("nightly-only")`.

### `nightly.yml` (new — `cron: '0 3 * * *'` + `workflow_dispatch`)

```yaml
jobs:
  full-suite:
    steps:
      - name: Full suite (unit + smoke + integration + stress + e2e + ml-it)
        run: mvn -B -ntp clean verify -P nightly
      - name: JMH benches
        run: |
          mvn -B -ntp test-compile -P bench
          mvn -B -ntp -P bench exec:java \
            -Dexec.mainClass=org.openjdk.jmh.Main \
            -Dexec.args="-rf json -rff target/jmh.json -wi 3 -i 5 -f 1"
      - name: Compare against baseline
        run: tools/compare-jmh.sh .github/perf-baselines/jmh.json target/jmh.json
      - name: File perf-regression issue on failure
        if: failure()
        run: gh issue create --title "Perf regression $(date -u +%Y-%m-%d)" --label perf-regression --body-file target/jmh-regression.md
```

### `update-perf-baseline.yml` (new — manual)

Runs the same JMH suite, commits the resulting JSON to `.github/perf-baselines/jmh.json`. Used after deliberate perf changes.

## 5. Acceptance criteria

1. All 35 risks in §3 are covered by at least one test, each test's Javadoc names the risk ID and the invariant being checked.
2. `mvn -P pr-gate verify` is green and completes in < 12 minutes on a standard GitHub-hosted Linux runner.
3. `mvn -P nightly verify` (excluding bench) is green and completes in < 45 minutes.
4. JMH baselines exist at `.github/perf-baselines/jmh.json` and the nightly comparison job is wired up.
5. `CLAUDE.md` "Testing Infrastructure" / "Testing Gotchas" sections updated with:
   - Tag taxonomy
   - New profiles
   - "How to add a new non-unit test" walkthrough (which `@Tag`, which directory, which fixture base class to extend)
6. Every new test has Javadoc that includes:
   - Risk ID it covers
   - The invariant being asserted
   - Any threading/timing assumptions
   - Why this kind of test (not unit) is appropriate

## 6. Implementation order

Each phase is intended to be merged separately (own PR) so `main` stays green throughout.

| Phase | Title | Output | Risk reduction |
|---|---|---|---|
| 0 | Infrastructure | `@Tag` on existing tests, new profiles in `pom.xml`, Awaitility + Testcontainers test deps, fixture base classes (e.g. `IntegrationTestSupport`) | Foundational; no risk reduction yet |
| 1 | Smoke layer | `PluginEnableSmokeTest`, `MessageServiceSmokeTest`, `EntityServicesSmokeTest`, `BbaiStatsCommandSmokeTest` | PLUGIN-SMOKE-RELOAD, THIN-MSG, THIN-ENT, THIN-CMD |
| 2 | Integration: evaluation | 4 IT classes | EVAL-001/002/003/005/006/011 |
| 3 | Integration: game | 8 IT classes | GAME-01/02/03/04/05/07/09/11 |
| 4 | Integration: ml + data + arena + config | 9 IT classes | ML-01/02/08, DATA-01/04, ARENA-01/03/06, WORLD-01, THIN-CFG |
| 5 | Stress layer | 7 stress classes | EVAL-004/010/012, GAME-08, ML-05/07, DATA-02 |
| 6 | E2E expansion | 3 E2E classes + hooks in `AbstractServerE2ETest` (player connect, block place, log-marker awaiter) | E2E-GAME-FULL/MULTI-ARENA/FORCE-STOP |
| 7 | ML-IT expansion | Extend `MLIntegrationTest` with 3 new methods + fixtures in `src/test/resources/ml/fixtures/` | ML-INT-EXT |
| 8 | Bench + baselines | 4 new JMH classes, `tools/compare-jmh.sh`, initial baseline JSON, `update-perf-baseline.yml` | ML-06 + perf regression detection |
| 9 | Nightly workflow + docs | `.github/workflows/nightly.yml`, `CLAUDE.md` updates | CI tier integration |

## 7. Out of scope (explicitly)

- **Refactoring production code** to make it more testable. Tests adapt to existing seams (`PluginContext`, `BBAI*` interfaces, package-private hooks). If an invariant truly cannot be observed from outside, that's noted in the per-test Javadoc and deferred to a follow-up.
- **Coverage metrics** (JaCoCo). The goal is risk reduction, not a coverage number.
- **Property-based testing** (jqwik). Could land in a future iteration if specific risks need it; not in this scope.
- **Mutation testing** (PIT). Same — future iteration.
- **Visual snapshot diff tooling** for the renderer. Existing SHA-256 snapshot tests are sufficient.

## 8. Open questions

None at design time. Operational decisions (exact JMH `@Warmup`/`@Measurement` parameters, perf threshold tuning, Testcontainers image pinning) are made in the implementation plan that follows from this spec.
