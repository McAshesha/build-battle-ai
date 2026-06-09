# Production Bug Fixes Design

**Date:** 2026-06-09
**Status:** Approved
**Origin:** Five production bugs surfaced by the 2026-06-07 test-coverage expansion
audit. Each is currently documented in CLAUDE.md "Known production gaps documented
via `@Disabled` tests" and pinned in source via a reproducing test that is held
`@Disabled` until the fix lands.

## Goal

Close all five gaps in one development cycle, activate every pinned test, and
delete the "Known production gaps" block from CLAUDE.md.

## In-scope bugs

| ID | File:line | Failure mode |
|---|---|---|
| DATA-01 | `data/local/LocalRepository.java:91` | `load()` corruption logs to `System.err`, bypassing `PluginLogger.warn` |
| DATA-04 | `data/local/LocalRepository.java:152` | `flush()` IOException logs to `System.err`, bypassing `PluginLogger.error` |
| DATA-02 | `data/DataService.java:62,349` | `provider` field not `volatile`; async autosave lambda reads after `shutdown()` nulls it → NPE under race |
| GAME-11 | `game/GameManager.java:482-485` | `mirror.clearAll()` not in try/catch; throw leaves `gp.themeIndex` un-advanced while zone is already cleared in the world |
| ML-08 | `ml/MLService.java:846` | `row.get(j).floatValue()` swallows `NaN`/`Infinity` from corrupted `centroids.json`; non-finite values propagate to cosine-score hot path |

## Non-goals

- No refactor of `LocalRepository.flush()`, Gson config, or atomic-rename semantics
- No fix in GAME-11's two other `mirror.clearAll()` callsites (`handleScore`,
  `startGame` reset) — throws there land *after* score/state mutation, so the
  next render tick still observes a consistent state
- No rebuild of ML fallback-centroid synthesis
- No change to `EvaluationService` / coordinator / queues
- No new logging frameworks, no new config keys

## Architecture

Five point edits across three production files plus the constructor of one
provider. No new modules, no new abstractions. The fixes follow the existing
service patterns (`@RequiredArgsConstructor` + `PluginLogger` via ctor).

### File-level changes

**`data/local/LocalRepository.java`**
- Add field `private final PluginLogger logger;` (final, via package ctor)
- `load()` catch block: `logger.warn(...)` replaces `System.err.println(...)`
- `flush()` catch block: `logger.error(...)` replaces `System.err.println(...)`
- `@RequiredArgsConstructor(access = PACKAGE)` continues to generate the ctor —
  Lombok adds `logger` automatically when declared `final`

**`data/local/LocalDataProvider.java`**
- Add field `private final PluginLogger logger;`
- Take `PluginLogger` in public ctor (additive — not breaking, only one caller:
  `DataService.createLocalProvider`)
- Pass `logger` to `new LocalRepository<>(file, gson, keyType, valueType, logger)`

**`data/DataService.java`**
- `private DataProvider provider;` → `private volatile DataProvider provider;`
- `createLocalProvider(config)` passes `plugin.getPluginLogger()` into the
  `LocalDataProvider` ctor
- `scheduleAutoSave`: replace the bare `() -> provider.flush()` lambda with:
  ```java
  () -> {
      DataProvider p = provider;
      if (p != null)
          p.flush();
  }
  ```
  Rationale: `volatile` guarantees visibility, the local capture guarantees the
  null-check and the flush-call see the *same* snapshot — a second
  `provider`-read after the null-check would still race.

**`game/GameManager.java`**
- In `startGameTickTimer`, the build-time-expiry branch (current lines 481-485):
  wrap `mirror.clearAll()` in a try/catch. Caught throwable is logged via
  `plugin.getPluginLogger().error(...)`; control proceeds to `gp.advanceTheme(...)`
  + `gp.resetBuildTime(...)` regardless. The `clearZone(arenaWorld, ...)` call
  earlier in the branch already mutated the world; the contract is "if the
  world side-effect ran, the per-player counters must advance too."
- No other GAME-11 callsite is touched. Out-of-scope rationale in Non-goals.

**`ml/MLService.java`**
- Extract the body of `loadCentroidsFromJson()` into a new package-private
  static method that returns a value class:
  ```java
  @Value
  static class CentroidParseResult {
      boolean ok;
      List<String> classes;   // null when !ok
      float[][] vectors;      // null when !ok
      static CentroidParseResult fail() { return new CentroidParseResult(false, null, null); }
      static CentroidParseResult success(List<String> c, float[][] v) { return new CentroidParseResult(true, c, v); }
  }

  static CentroidParseResult parseCentroidsJson(Reader reader, PluginLogger logger);
  ```
  Lombok `@Value` keeps the result class tight (immutable, equals/hashCode/toString
  generated). Both helper class and method are package-private — visible only to
  tests in `ru.ashesha.buildBattleAI.ml`.
- Inside the parsing loop, replace the unconditional `row.get(j).floatValue()`
  with a guarded read:
  ```java
  double d = row.get(j);
  if (!Double.isFinite(d)) {
      logger.warn("Centroid %d ('%s') component %d is non-finite (%s) — using fallback.",
              i, classes.get(i), j, String.valueOf(d));
      return CentroidParseResult.fail();
  }
  v[j] = (float) d;
  ```
- `loadCentroidsFromJson()` becomes a thin shim: opens the resource stream,
  delegates to `parseCentroidsJson(reader, plugin.getPluginLogger())`, applies
  the result, closes the stream in `finally`. `Throwable` catch remains.

### Test activation

Every `@Disabled` test that pins a fixed bug must lose its annotation in the
*same commit* that lands the fix. Tests that already exist but currently
*observe* the wrong behavior (e.g. `CorruptedJsonRecoveryIT` watching
`System.err`) are flipped to assert against the mocked `PluginLogger`.

| Test | Action | Commit |
|---|---|---|
| `data/local/CorruptedJsonRecoveryIT` | Flip assertions: capture `PluginLogger.warn` (already-passing tests that targeted `System.err` get rewritten) | 1 |
| `data/local/DiskFailureEscalationIT` line 202 | Remove `@Disabled`; implement body — mock `PluginLogger.error` capture | 1 |
| `stress/data/DataServiceAutosaveShutdownRaceStress` line 133 | Remove `@Disabled` — body already complete, just toggle | 2 |
| `game/BuildTimeExpiryAtomicityIT` line 296 | Remove `@Disabled`; implement body — inject a `MutablePlotScene` whose `clearAll()` throws (subclass), drive the build-time-expiry path, assert `themeIndex` advanced | 3 |
| `ml/CentroidsJsonRobustnessIT` lines 238/255/279/296/313 | Remove all 5 `@Disabled`; each test calls the new `MLService.parseCentroidsJson(Reader, mockLogger)` directly with the corresponding corrupted payload | 4 |

## Commits

1. **`fix(data): route LocalRepository I/O failures to PluginLogger`**
   - `LocalRepository.java`: add `logger` field, swap two `System.err` calls
   - `LocalDataProvider.java`: take `PluginLogger` in ctor, propagate
   - `DataService.java`: `createLocalProvider` passes `plugin.getPluginLogger()`
   - Tests: `CorruptedJsonRecoveryIT` assertions flipped; `DiskFailureEscalationIT` activated
   - **Closes:** DATA-01, DATA-04

2. **`fix(data): make DataService.provider volatile + null-guard autosave lambda`**
   - `DataService.java`: `volatile` keyword + local-capture lambda
   - Tests: `DataServiceAutosaveShutdownRaceStress` activated
   - **Closes:** DATA-02

3. **`fix(game): guard mirror.clearAll() in build-time expiry path`**
   - `GameManager.java`: try/catch around `mirror.clearAll()` in
     `startGameTickTimer` only
   - Tests: `BuildTimeExpiryAtomicityIT` body implemented + activated
   - **Closes:** GAME-11

4. **`fix(ml): reject non-finite centroid components and add parser injection hook`**
   - `MLService.java`: extract `parseCentroidsJson(Reader, PluginLogger)`,
     add `Double.isFinite` guard
   - Tests: 5 corruption-mode methods in `CentroidsJsonRobustnessIT` activated
   - **Closes:** ML-08

5. **`docs: clear resolved production gaps from CLAUDE.md`**
   - `CLAUDE.md`: drop the entire "Known production gaps documented via
     `@Disabled` tests" block (or leave only entries that aren't yet fixed —
     none, if this design completes)
   - **No production change**; pure doc sync

## Verification

Per-commit gate:
```bash
mvn -B -ntp -P pr-gate clean verify
```
Must be green before the next commit. After commit 2, also:
```bash
mvn -B -ntp -P stress test
```
to confirm `DataServiceAutosaveShutdownRaceStress` passes deterministically.

End-to-end gate after commit 5:
```bash
mvn -B -ntp -P pr-gate clean verify
grep -n "DATA-01\|DATA-02\|DATA-04\|GAME-11\|ML-08" CLAUDE.md src/main/java
```
Expected: zero matches in `src/main/java`; CLAUDE.md retains only
historical/changelog mentions if any.

## Error handling philosophy

Each fix preserves the existing "fail open, log loud" contract:

- `LocalRepository` already catches and continues on load/save failure —
  fix only routes the log to the right channel
- `DataService.shutdown` does not gain new responsibilities; the lambda gets
  defensive about a state it never *should* observe
- `GameManager` build-time-expiry is now atomic: the player's round always
  advances even if the mirror reset fails
- `MLService` falls back to synthetic centroids whenever the bundled file has
  *any* defect — non-finite values now count as a defect

No fix introduces a new failure mode or alters which thread runs what.

## Threading

| Concern | Before | After |
|---|---|---|
| DATA-01/04 logging | sync, off-thread `System.err` | sync via `PluginLogger` (same thread, different sink) |
| DATA-02 provider visibility | non-volatile field crossed thread boundary | `volatile` + local capture |
| GAME-11 expiry atomicity | throw skips state advance | catch ensures state advance |
| ML-08 parse hook | only callable through resource stream | new pkg-private overload accepts arbitrary `Reader` for tests |

`LocalRepository` continues to use a `ConcurrentHashMap` for cache, `volatile
dirty` for the dirty flag, `synchronized flush()` for I/O — none of that
changes.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| `volatile` not enough — autosave still races on `provider.flush()` if shutdown is mid-flush | Bukkit `BukkitTask.cancel()` semantics: scheduler stops *scheduling* further runs, but a currently-executing lambda runs to completion. Local capture pins the reference; the flush runs against the snapshot. Provider's own `flush()` is `synchronized` on `LocalRepository`, so shutdown's `provider.stop()` blocks at the synchronized monitor — no concurrent writer races. |
| Lombok adds `logger` ctor arg in wrong position → caller broken | Only one caller (`LocalDataProvider`), updated in the same commit. Field order in the source dictates ctor order; `logger` comes last. |
| GAME-11 catch-all masks a different bug | The catch logs at ERROR level via `PluginLogger.error` — visible in console + stack trace via existing logger formatter |
| ML-08 `parseCentroidsJson(Reader, PluginLogger)` becomes public API surface that future refactors break | `package-private` (no modifier), test lives in `ru.ashesha.buildBattleAI.ml` — never exposed beyond the package |
| Test activation reveals additional latent issues | Each commit gated on full `pr-gate`; if a previously-green test fails after activation, investigate before merging the commit |

## Out-of-scope deferrals

None. All five bugs are closed in this design.
