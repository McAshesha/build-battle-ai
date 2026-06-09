# Production Bug Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close all 5 production bugs (DATA-01, DATA-04, DATA-02, GAME-11, ML-08) surfaced by the 2026-06-07 test-coverage audit, activate every `@Disabled` test that pins them, and clear the "Known production gaps" block from CLAUDE.md.

**Architecture:** Five point edits across three production files. DATA-01 and DATA-04 share one fix (thread `PluginLogger` from `DataService` → `LocalDataProvider` → `LocalRepository`). DATA-02 is a one-line `volatile` + null-guard in `DataService`. GAME-11 is a try/catch around `mirror.clearAll()` in one branch of `GameManager.startGameTickTimer`. ML-08 extracts a `package-private` parsing hook in `MLService` and adds a non-finite guard.

**Tech Stack:** Java 8, Maven, Lombok (`@RequiredArgsConstructor`, `@Value`), Mockito 5.12 (inline mock-maker default → final classes mockable), JUnit Jupiter 5.10, Gson, Bukkit/Spigot scheduler.

**Spec:** `docs/superpowers/specs/2026-06-09-production-bug-fixes-design.md`

---

## File Structure

### Production files modified (3)

| File | Change |
|---|---|
| `src/main/java/ru/ashesha/buildBattleAI/data/local/LocalRepository.java` | Add `final PluginLogger logger` field via ctor; replace 2 × `System.err.println` |
| `src/main/java/ru/ashesha/buildBattleAI/data/local/LocalDataProvider.java` | Add `PluginLogger` ctor param; propagate into `LocalRepository` |
| `src/main/java/ru/ashesha/buildBattleAI/data/DataService.java` | `volatile DataProvider provider`; local-capture autosave lambda; pass `plugin.getPluginLogger()` to `LocalDataProvider` ctor |
| `src/main/java/ru/ashesha/buildBattleAI/game/GameManager.java` | try/catch around `mirror.clearAll()` at the build-time-expiry branch |
| `src/main/java/ru/ashesha/buildBattleAI/ml/MLService.java` | Extract package-private `parseCentroidsJson(Reader, PluginLogger)` returning `CentroidParseResult`; add `Double.isFinite` guard |

### Tests activated / rewritten (5 files)

| File | Change |
|---|---|
| `src/test/java/ru/ashesha/buildBattleAI/data/local/CorruptedJsonRecoveryIT.java` | Activate `warnOnCorruption` (impl real test); delete `systemErrReceivesOutputOnCorruption` (assertion is no longer correct); LocalRepository ctor signature changes — update existing tests |
| `src/test/java/ru/ashesha/buildBattleAI/data/local/DiskFailureEscalationIT.java` | Activate `flushFailureEscalatesToPluginLogger` (impl); LocalRepository ctor signature changes — update existing tests |
| `src/test/java/ru/ashesha/buildBattleAI/stress/data/DataServiceAutosaveShutdownRaceStress.java` | Remove `@Disabled` annotation |
| `src/test/java/ru/ashesha/buildBattleAI/game/BuildTimeExpiryAtomicityIT.java` | Activate `clearAllThrowingSkipsAdvanceTheme` (impl real test using Mockito spy — final class is mockable in Mockito 5) |
| `src/test/java/ru/ashesha/buildBattleAI/ml/CentroidsJsonRobustnessIT.java` | Activate 5 corruption-mode tests (impl bodies that call new `parseCentroidsJson(Reader, mockLogger)`) |

### Docs (1)

| File | Change |
|---|---|
| `CLAUDE.md` | Delete "Known production gaps documented via `@Disabled` tests" sub-section (no fixes left to document) |

---

## Task 1: DATA-01 / DATA-04 — Route LocalRepository I/O failures through PluginLogger

**Files:**
- Modify: `src/main/java/ru/ashesha/buildBattleAI/data/local/LocalRepository.java`
- Modify: `src/main/java/ru/ashesha/buildBattleAI/data/local/LocalDataProvider.java`
- Modify: `src/main/java/ru/ashesha/buildBattleAI/data/DataService.java:242-260` (the `createLocalProvider` method)
- Modify: `src/test/java/ru/ashesha/buildBattleAI/data/local/CorruptedJsonRecoveryIT.java`
- Modify: `src/test/java/ru/ashesha/buildBattleAI/data/local/DiskFailureEscalationIT.java`
- Check: `src/test/java/ru/ashesha/buildBattleAI/data/local/LocalRepositoryTest.java` for ctor-signature updates

- [ ] **Step 1: Inventory every `new LocalRepository(...)` call site**

Run:
```bash
grep -rn "new LocalRepository<" src/ --include="*.java"
```
Expected output: hits in `LocalDataProvider.java:62`, `CorruptedJsonRecoveryIT.java` (multiple), `DiskFailureEscalationIT.java` (multiple), possibly `LocalRepositoryTest.java`. Every one will need the new logger ctor arg.

- [ ] **Step 2: Rewrite `warnOnCorruption` test in CorruptedJsonRecoveryIT** (TDD: write the failing test first)

Replace lines 214-225 of `src/test/java/ru/ashesha/buildBattleAI/data/local/CorruptedJsonRecoveryIT.java`. The current body is `fail(...)`. Replace with:

```java
    @Test
    void warnOnCorruption() throws IOException {
        File file = new File(tempDir, "warn_check_player.json");
        Files.write(file.toPath(),
                "{ definitely not json".getBytes(StandardCharsets.UTF_8));

        PluginLogger logger = mock(PluginLogger.class);

        LocalRepository<UUID, PlayerData> repo =
                new LocalRepository<>(file, gson, UUID.class, PlayerData.class, logger);
        repo.load();

        // Verify PluginLogger.warn was invoked with a message containing the file name.
        ArgumentCaptor<String> formatCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger).warn(formatCaptor.capture(), any(Object[].class));
        assertTrue(formatCaptor.getValue().contains("%s") || formatCaptor.getValue().contains(file.getName()),
                "warn() format must reference the file (got: " + formatCaptor.getValue() + ")");
    }
```

Also update the imports at the top of the file:
```java
import ru.ashesha.buildBattleAI.core.PluginLogger;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
```

Remove the `@Disabled("DATA-01: ...")` annotation on the method. Remove the now-obsolete TODO comment.

- [ ] **Step 3: Delete `systemErrReceivesOutputOnCorruption` test**

In the same file, delete the entire method (lines 236-259) plus its leading Javadoc. After the fix, `System.err` no longer receives the message — keeping the test would be a false-positive regression.

Also remove now-unused imports if any (`PrintStream`, `ByteArrayOutputStream`).

- [ ] **Step 4: Update existing CorruptedJsonRecoveryIT ctor calls**

In CorruptedJsonRecoveryIT, find every line that calls `new LocalRepository<>(file, gson, UUID.class, PlayerData.class)` (occurs at lines ~95-97, 122-123, 156-157, 165-167, 188-189, 247-248). Each needs an extra `, logger` argument. Add a logger field:

At the top of the class (after `private Gson gson;`):
```java
    private PluginLogger logger;
```

In `setUp()`:
```java
    @BeforeEach
    void setUp() {
        gson = new GsonBuilder().setPrettyPrinting().create();
        logger = mock(PluginLogger.class);
    }
```

Then replace every `new LocalRepository<>(file, gson, UUID.class, PlayerData.class)` with `new LocalRepository<>(file, gson, UUID.class, PlayerData.class, logger)`.

- [ ] **Step 5: Rewrite `flushFailureEscalatesToPluginLogger` in DiskFailureEscalationIT**

Replace lines 200-209 of `src/test/java/ru/ashesha/buildBattleAI/data/local/DiskFailureEscalationIT.java`. Remove `@Disabled` and supply the real body:

```java
    @Test
    void flushFailureEscalatesToPluginLogger() throws IOException {
        assumePosixSupported();

        File dataFile = new File(tempDir, "players.json");
        PluginLogger logger = mock(PluginLogger.class);

        LocalRepository<UUID, PlayerData> repo =
                new LocalRepository<>(dataFile, gson, UUID.class, PlayerData.class, logger);

        UUID uuid = UUID.randomUUID();
        repo.put(uuid, new PlayerData(uuid, "DiskFullPlayer"));

        // Make the directory read-only so the .tmp file cannot be created.
        makeReadOnly(tempDir);

        repo.flush(); // must not throw

        // Verify error log call referencing the file name.
        ArgumentCaptor<String> formatCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger).error(formatCaptor.capture(), any(Object[].class));
        assertTrue(formatCaptor.getValue().contains("%s") || formatCaptor.getValue().contains(dataFile.getName()),
                "error() format must reference the file (got: " + formatCaptor.getValue() + ")");
    }
```

Update imports at the top of the file:
```java
import ru.ashesha.buildBattleAI.core.PluginLogger;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
```

- [ ] **Step 6: Update existing DiskFailureEscalationIT ctor calls**

Same procedure as Step 4: add `private PluginLogger logger;` field, initialise in `setUp()`, update every `new LocalRepository<>(dataFile, gson, UUID.class, PlayerData.class)` to add `, logger` as the 5th argument. Affected lines: ~92-93, 119-120, 158-159.

- [ ] **Step 7: Update LocalRepositoryTest ctor calls (if any)**

Run:
```bash
grep -n "new LocalRepository" src/test/java/ru/ashesha/buildBattleAI/data/local/LocalRepositoryTest.java
```
For each hit, add `, logger` as the 5th argument. If the test class has no logger field yet, add one (`private final PluginLogger logger = mock(PluginLogger.class);`) and the corresponding imports.

- [ ] **Step 8: Run the tests — expect compile failure**

Run:
```bash
mvn -B -ntp -P pr-gate test-compile
```
Expected: compile error in `LocalDataProvider.java` (still calls 4-arg ctor) and in `LocalRepository.java` itself (5th param does not exist yet). This is the failing-test state.

- [ ] **Step 9: Add `logger` field to LocalRepository**

Edit `src/main/java/ru/ashesha/buildBattleAI/data/local/LocalRepository.java`. After the `valueType` field (current line 58), insert:

```java
    /** Logger used to report I/O failures to the server console. */
    @NonNull
    private final PluginLogger logger;
```

Add the import at the top:
```java
import ru.ashesha.buildBattleAI.core.PluginLogger;
```

Lombok's `@RequiredArgsConstructor` will automatically pick up the new `final` field and generate a 5-arg ctor.

- [ ] **Step 10: Replace `System.err` calls in LocalRepository**

In `load()` catch (current line 89-92), replace:
```java
        } catch (Throwable e) {
            // Log but don't fail — start with empty cache on corrupt file
            System.err.println("[BuildBattleAI] Failed to load " + file.getName() + ": " + e.getMessage());
        }
```
with:
```java
        } catch (Throwable e) {
            // Log but don't fail — start with empty cache on corrupt file
            logger.warn("Failed to load %s: %s", file.getName(), e.getMessage());
        }
```

In `flush()` catch (current line 151-156), replace:
```java
        } catch (IOException e) {
            System.err.println("[BuildBattleAI] Failed to save " + file.getName() + ": " + e.getMessage());
            // Clean up temp file on failure
            if (temp.exists())
                temp.delete();
        }
```
with:
```java
        } catch (IOException e) {
            logger.error("Failed to save %s: %s", file.getName(), e.getMessage());
            // Clean up temp file on failure
            if (temp.exists())
                temp.delete();
        }
```

- [ ] **Step 11: Add `logger` field to LocalDataProvider**

Edit `src/main/java/ru/ashesha/buildBattleAI/data/local/LocalDataProvider.java`. Add an import:
```java
import ru.ashesha.buildBattleAI.core.PluginLogger;
```

Add a final field after `gson`:
```java
    /** Logger forwarded to each repository for I/O failure reporting. */
    private final PluginLogger logger;
```

Update the ctor (current line 44-47):
```java
    public LocalDataProvider(@NonNull File dataDir, @NonNull PluginLogger logger) {
        this.dataDir = dataDir;
        this.logger = logger;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }
```

Update the `getRepository` method (current line 62):
```java
            repo = new LocalRepository<>(file, gson, keyType, valueType, logger);
```

- [ ] **Step 12: Pass logger into LocalDataProvider from DataService**

Edit `src/main/java/ru/ashesha/buildBattleAI/data/DataService.java`. Find `createLocalProvider` (line 242). Look for the line that constructs `LocalDataProvider`. It currently reads roughly:

```java
        return new LocalDataProvider(dataDir);
```

Replace with:
```java
        return new LocalDataProvider(dataDir, plugin.getPluginLogger());
```

- [ ] **Step 13: Run only the data-local tests**

Run:
```bash
mvn -B -ntp test -Dtest='CorruptedJsonRecoveryIT,DiskFailureEscalationIT,LocalRepositoryTest' -DfailIfNoTests=false
```
Expected: all green, including the newly-activated `warnOnCorruption` and `flushFailureEscalatesToPluginLogger`.

- [ ] **Step 14: Full pr-gate verify**

Run:
```bash
mvn -B -ntp -P pr-gate clean verify
```
Expected: BUILD SUCCESS, 12 837+ tests, 0 failures. Should complete in ~1:30 min on a local Mac.

- [ ] **Step 15: Commit**

Run:
```bash
git add src/main/java/ru/ashesha/buildBattleAI/data/local/LocalRepository.java \
        src/main/java/ru/ashesha/buildBattleAI/data/local/LocalDataProvider.java \
        src/main/java/ru/ashesha/buildBattleAI/data/DataService.java \
        src/test/java/ru/ashesha/buildBattleAI/data/local/CorruptedJsonRecoveryIT.java \
        src/test/java/ru/ashesha/buildBattleAI/data/local/DiskFailureEscalationIT.java \
        src/test/java/ru/ashesha/buildBattleAI/data/local/LocalRepositoryTest.java

git commit -m "$(cat <<'EOF'
fix(data): route LocalRepository I/O failures to PluginLogger

DATA-01: load() corruption now logs via PluginLogger.warn, not System.err.
DATA-04: flush() IOException now logs via PluginLogger.error, not System.err.

Thread PluginLogger from DataService → LocalDataProvider ctor → LocalRepository
ctor. Activates 2 @Disabled tests and reworks 1 stale test that asserted
against System.err.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: DATA-02 — Make DataService.provider volatile + null-guard autosave lambda

**Files:**
- Modify: `src/main/java/ru/ashesha/buildBattleAI/data/DataService.java:62` and `:347-352`
- Modify: `src/test/java/ru/ashesha/buildBattleAI/stress/data/DataServiceAutosaveShutdownRaceStress.java:133-135`

- [ ] **Step 1: Activate the stress test (TDD: failing test first)**

Edit `src/test/java/ru/ashesha/buildBattleAI/stress/data/DataServiceAutosaveShutdownRaceStress.java`. Delete lines 133-134:
```java
    @Disabled("DATA-02: real race — autosave lambda NPEs when shutdown() nulls provider; "
            + "reproduce: mvn -B -ntp clean test -Dtest=DataServiceAutosaveShutdownRaceStress -P stress")
```

Also remove the now-unused import:
```java
import org.junit.jupiter.api.Disabled;
```

- [ ] **Step 2: Reproduce the failure**

Run:
```bash
mvn -B -ntp test -Dtest=DataServiceAutosaveShutdownRaceStress -P stress -DfailIfNoTests=false
```
Expected: BUILD FAILURE with `NullPointerException: Cannot invoke "ru.ashesha.buildBattleAI.data.DataProvider.flush()" because "this.provider" is null` — confirming the race is real before the fix.

- [ ] **Step 3: Add `volatile` to the provider field**

Edit `src/main/java/ru/ashesha/buildBattleAI/data/DataService.java`. Change line 62 from:
```java
    private DataProvider provider;
```
to:
```java
    /**
     * The active storage backend, or {@code null} if disabled.
     *
     * <p>Declared {@code volatile} so {@link #shutdown()} writing {@code null}
     * is immediately visible to the async autosave lambda — see DATA-02.
     */
    private volatile DataProvider provider;
```

- [ ] **Step 4: Replace autosave lambda with null-guarded local capture**

Still in `DataService.java`. Find `scheduleAutoSave` (current line 338). Replace the inner `runTaskTimerAsynchronously` call body — current lines 347-352:
```java
        autoSaveTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                () -> provider.flush(),
                intervalTicks,
                intervalTicks
        );
```
with:
```java
        autoSaveTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                () -> {
                    // Capture provider into a local — shutdown() may null the
                    // field concurrently (DATA-02). volatile guarantees the
                    // local-and-the-flush-call see the same snapshot.
                    DataProvider p = provider;
                    if (p != null)
                        p.flush();
                },
                intervalTicks,
                intervalTicks
        );
```

- [ ] **Step 5: Verify the stress test now passes**

Run:
```bash
mvn -B -ntp test -Dtest=DataServiceAutosaveShutdownRaceStress -P stress -DfailIfNoTests=false
```
Expected: BUILD SUCCESS, all 100 cycles × 8 threads × 1000 flushes (~800 000 invocations) complete without NPE.

- [ ] **Step 6: Full pr-gate verify**

Run:
```bash
mvn -B -ntp -P pr-gate clean verify
```
Expected: BUILD SUCCESS, 12 837+ tests, 0 failures.

- [ ] **Step 7: Commit**

Run:
```bash
git add src/main/java/ru/ashesha/buildBattleAI/data/DataService.java \
        src/test/java/ru/ashesha/buildBattleAI/stress/data/DataServiceAutosaveShutdownRaceStress.java

git commit -m "$(cat <<'EOF'
fix(data): volatile DataService.provider + null-guard autosave lambda

DATA-02: under concurrent shutdown(), the async autosave lambda could read
provider=null and NPE. provider is now volatile, and the lambda captures it
into a local before the null-check + flush so both observations agree.

Activates DataServiceAutosaveShutdownRaceStress (100 cycles × 8 threads ×
1000 flushes = ~800k invocations against shutdown).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: GAME-11 — Guard mirror.clearAll() in build-time-expiry path

**Files:**
- Modify: `src/main/java/ru/ashesha/buildBattleAI/game/GameManager.java:481-485`
- Modify: `src/test/java/ru/ashesha/buildBattleAI/game/BuildTimeExpiryAtomicityIT.java:296-316`

- [ ] **Step 1: Implement the disabled test (TDD: failing test first)**

Edit `src/test/java/ru/ashesha/buildBattleAI/game/BuildTimeExpiryAtomicityIT.java`. Replace lines 296-316 (the `@Disabled` `clearAllThrowingSkipsAdvanceTheme`) with a real test that uses Mockito 5's default inline mock-maker — `MutablePlotScene` being `final` is no longer a blocker:

```java
    @Test
    @DisplayName("GAME-11: mirror.clearAll() throwing still advances themeIndex (atomic expiry)")
    void clearAllThrowingSkipsAdvanceTheme() throws Exception {
        List<String> themes = Arrays.asList(
                "cat", "sword", "ball", "house", "tree", "glasses",
                "ship", "tower", "car", "plane");
        Arena.PlotData plot = mock(Arena.PlotData.class);
        when(plot.corner1X()).thenReturn(0);  when(plot.corner2X()).thenReturn(9);
        when(plot.corner1Y()).thenReturn(60); when(plot.corner2Y()).thenReturn(70);
        when(plot.corner1Z()).thenReturn(0);  when(plot.corner2Z()).thenReturn(9);
        when(plot.spawn()).thenReturn(null);

        @SuppressWarnings("unchecked")
        List<Arena.PlotData> plots = mock(List.class);
        when(plots.get(0)).thenReturn(plot);

        Arena arena = buildArena(plots);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            GameManager manager = buildManager(scheduler, bukkit);

            GameSession session = new GameSession(arena);
            session.setThemes(themes);
            session.gameTimeRemaining(300);
            session.state(ArenaState.PLAYING);

            UUID pid = UUID.randomUUID();
            PlayerSnapshot snapshot = mock(PlayerSnapshot.class);
            GamePlayer gp = new GamePlayer(pid, "Bob", 0, snapshot, 0);
            session.addPlayer(gp);

            // Install a mirror whose clearAll() throws. Mockito 5's default
            // inline mock-maker can mock the final MutablePlotScene class.
            MutablePlotScene throwingMirror = mock(MutablePlotScene.class);
            org.mockito.Mockito.doThrow(new RuntimeException("simulated clearAll failure"))
                    .when(throwingMirror).clearAll();
            session.installMirror(0, throwingMirror);

            injectSession(manager, session);

            Runnable tick = captureGameTickRunnable(manager, session, scheduler);

            int themeIndexBefore = gp.themeIndex();

            // Execute one game tick — clearAll throws, but advanceTheme MUST still run.
            tick.run();

            // ── invariant: theme advanced despite clearAll failure ──────────
            assertEquals(
                    (themeIndexBefore + 1) % themes.size(),
                    gp.themeIndex(),
                    "themeIndex must advance even if mirror.clearAll() throws");

            // ── invariant: clearAll was attempted ───────────────────────────
            org.mockito.Mockito.verify(throwingMirror).clearAll();

            // ── invariant: build time was reset for the new round ───────────
            assertEquals(arena.buildTime(), gp.buildTimeRemaining(),
                    "buildTimeRemaining must be reset after clearAll failure too");
        }
    }
```

Important details:
- Replace the entire current method body INCLUDING the `@Disabled` annotation (delete both)
- Keep the existing `@DisplayName` (or replace as shown)
- The test will be in the same class — uses the existing `buildManager`, `buildArena`, `injectSession`, `captureGameTickRunnable` helpers

- [ ] **Step 2: Run only this test — expect failure**

Run:
```bash
mvn -B -ntp test -Dtest='BuildTimeExpiryAtomicityIT#clearAllThrowingSkipsAdvanceTheme' -DfailIfNoTests=false
```
Expected: BUILD FAILURE — `clearAll()` throws inside the tick, `advanceTheme` is skipped, `gp.themeIndex()` remains at 0. Assertion fails with `expected: <1> but was: <0>`.

If the test fails because of an inner exception propagation (the throw escapes the entire tick lambda), that ALSO confirms the bug — the production code lets the throw bubble up, killing the tick. Either symptom proves GAME-11.

- [ ] **Step 3: Apply the production fix**

Edit `src/main/java/ru/ashesha/buildBattleAI/game/GameManager.java`. Find `startGameTickTimer` (current line 433). Locate the build-time-expiry branch (current lines 473-496). Replace lines 479-484:

```java
                    // Mirror is session-scoped: wipe it so the next render-tick
                    // doesn't keep showing the expired build to the ML model.
                    MutablePlotScene m = session.mirror(gp.plotIndex());
                    if (m != null)
                        m.clearAll();
                    gp.clearZoneDirty();
                    gp.advanceTheme(session.themes().size());
```
with:
```java
                    // Mirror is session-scoped: wipe it so the next render-tick
                    // doesn't keep showing the expired build to the ML model.
                    // GAME-11: clearAll() must not skip the advanceTheme/reset
                    // pair — the world zone is already cleared above, so the
                    // per-player counters MUST advance to keep state consistent.
                    MutablePlotScene m = session.mirror(gp.plotIndex());
                    if (m != null) {
                        try {
                            m.clearAll();
                        } catch (Throwable t) {
                            plugin.getPluginLogger().error(
                                    "mirror.clearAll() failed for arena %s player %s: %s",
                                    arena.name(), gp.playerId(), t.getMessage());
                        }
                    }
                    gp.clearZoneDirty();
                    gp.advanceTheme(session.themes().size());
```

- [ ] **Step 4: Re-run the test — expect pass**

Run:
```bash
mvn -B -ntp test -Dtest='BuildTimeExpiryAtomicityIT' -DfailIfNoTests=false
```
Expected: BUILD SUCCESS, both `happyPathBothOperationsComplete` AND `clearAllThrowingSkipsAdvanceTheme` pass.

- [ ] **Step 5: Full pr-gate verify**

Run:
```bash
mvn -B -ntp -P pr-gate clean verify
```
Expected: BUILD SUCCESS, 12 837+ tests, 0 failures.

- [ ] **Step 6: Commit**

Run:
```bash
git add src/main/java/ru/ashesha/buildBattleAI/game/GameManager.java \
        src/test/java/ru/ashesha/buildBattleAI/game/BuildTimeExpiryAtomicityIT.java

git commit -m "$(cat <<'EOF'
fix(game): guard mirror.clearAll() in build-time expiry path (GAME-11)

If mirror.clearAll() throws during build-time expiry, advanceTheme + resetBuildTime
must still run — the in-world build zone is already cleared by clearZone() earlier
in the tick, so leaving the per-player counters un-advanced creates a "cleared zone,
same theme" inconsistent state.

Wraps clearAll() in try/catch with PluginLogger.error reporting. Activates
BuildTimeExpiryAtomicityIT.clearAllThrowingSkipsAdvanceTheme via Mockito 5's
default inline mock-maker (final MutablePlotScene is mockable in Mockito 5+).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: ML-08 — Reject non-finite centroid components + add parser injection hook

**Files:**
- Modify: `src/main/java/ru/ashesha/buildBattleAI/ml/MLService.java` (extract method + add value class)
- Modify: `src/test/java/ru/ashesha/buildBattleAI/ml/CentroidsJsonRobustnessIT.java` (5 disabled tests)

- [ ] **Step 1: Activate one of the disabled tests (TDD: failing test first)**

Edit `src/test/java/ru/ashesha/buildBattleAI/ml/CentroidsJsonRobustnessIT.java`. Replace the `truncatedJsonFallsBackGracefully` method (lines 237-244) with:

```java
    @Test
    void truncatedJsonFallsBackGracefully() {
        PluginLogger mockLogger = mock(PluginLogger.class);
        Reader reader = new StringReader("{\"classes\":[\"cube\"],\"centroids\":[[0.1,0.2");

        MLService.CentroidParseResult result =
                MLService.parseCentroidsJson(reader, mockLogger);

        assertFalse(result.isOk(),
                "Truncated JSON must produce a failed parse result");
        assertNull(result.getClasses(),
                "Failed result must carry null classes");
        assertNull(result.getVectors(),
                "Failed result must carry null vectors");
    }
```

Also remove the `@Disabled(...)` annotation immediately above.

Update imports at the top of the file:
```java
import java.io.Reader;
import java.io.StringReader;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
```

- [ ] **Step 2: Run the test — expect compile failure**

Run:
```bash
mvn -B -ntp test-compile -DfailIfNoTests=false
```
Expected: compile error — `MLService.CentroidParseResult` does not exist, `MLService.parseCentroidsJson` does not exist. This is the failing-test state.

- [ ] **Step 3: Add the `CentroidParseResult` value class to MLService**

Edit `src/main/java/ru/ashesha/buildBattleAI/ml/MLService.java`. Add the import:
```java
import lombok.Value;
```

Insert the following nested class right before the existing `CentroidsBundle` (current line 795, the line that reads `@SuppressWarnings("MismatchedQueryAndUpdateOfCollection")`):

```java
    /**
     * Outcome of parsing a {@code centroids.json} payload. Either:
     * <ul>
     *   <li>{@code ok=true} with a non-null {@code classes} + {@code vectors} pair, or</li>
     *   <li>{@code ok=false} with both {@code classes} and {@code vectors} null —
     *       the caller must fall back to {@code initFallbackCentroids()}.</li>
     * </ul>
     *
     * <p>Package-private so tests in {@code ru.ashesha.buildBattleAI.ml} can
     * call {@link #parseCentroidsJson(Reader, PluginLogger)} with corrupted
     * payloads without touching the bundled resource stream.
     */
    @Value
    static class CentroidParseResult {
        boolean ok;
        List<String> classes;
        float[][] vectors;

        static CentroidParseResult fail() {
            return new CentroidParseResult(false, null, null);
        }

        static CentroidParseResult success(List<String> classes, float[][] vectors) {
            return new CentroidParseResult(true, classes, vectors);
        }
    }
```

Add the import for `PluginLogger` (already used elsewhere? check):
```bash
grep -n "import ru.ashesha.buildBattleAI.core.PluginLogger" src/main/java/ru/ashesha/buildBattleAI/ml/MLService.java
```
If not present, add it.

- [ ] **Step 4: Extract `parseCentroidsJson(Reader, PluginLogger)` from `loadCentroidsFromJson()`**

In the same file, locate `loadCentroidsFromJson` (current line 811). Replace the entire method body (lines 811-865) with:

```java
    /**
     * Populates {@link #classNames} / {@link #centroidVectors} from the
     * bundled {@code centroids.json} resource. Returns {@code true} on
     * success; {@code false} if the resource is missing, malformed, or shape-
     * mismatched, in which case the caller is expected to fall back to
     * synthetic centroids.
     */
    private boolean loadCentroidsFromJson() {
        InputStream in = MLService.class.getResourceAsStream(CENTROIDS_RESOURCE);
        if (in == null) {
            plugin.getPluginLogger().warn("Centroids resource not found at %s — using fallback centroids.",
                    CENTROIDS_RESOURCE);
            return false;
        }
        try {
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            CentroidParseResult result = parseCentroidsJson(reader, plugin.getPluginLogger());
            if (!result.isOk())
                return false;
            applyCentroids(result.getClasses(), result.getVectors());
            return true;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Parses a centroids JSON payload from an arbitrary {@link Reader}.
     * Extracted from {@link #loadCentroidsFromJson()} so tests can feed
     * synthetic corruption modes without owning the classpath resource.
     *
     * <p>Returns {@link CentroidParseResult#fail()} on:
     * <ul>
     *   <li>Gson parsing exceptions (truncated JSON, garbage bytes, wrong types)</li>
     *   <li>Null bundle (empty file, {@code null} literal, wrong root structure)</li>
     *   <li>Class-count / vector-count mismatch</li>
     *   <li>Wrong vector dimension</li>
     *   <li>Non-finite ({@code NaN} or {@code Infinity}) float component</li>
     * </ul>
     *
     * <p>This method is package-private — it is part of the {@code ml} package
     * test surface only. Do not call from production code outside {@code MLService}.
     *
     * @param reader the JSON payload (caller owns close)
     * @param logger logger for diagnostic messages on parse failure
     * @return parsed result (caller invokes {@link #applyCentroids} on success)
     */
    static CentroidParseResult parseCentroidsJson(Reader reader, PluginLogger logger) {
        try {
            Gson gson = new Gson();
            Type bundleType = new TypeToken<CentroidsBundle>() {
            }.getType();
            CentroidsBundle bundle = gson.fromJson(reader, bundleType);
            if (bundle == null || bundle.classes == null || bundle.centroids == null) {
                logger.warn("Centroids JSON missing required fields — using fallback.");
                return CentroidParseResult.fail();
            }
            if (bundle.classes.size() != bundle.centroids.size()) {
                logger.warn(
                        "Centroids JSON has %d classes but %d vectors — using fallback.",
                        bundle.classes.size(), bundle.centroids.size());
                return CentroidParseResult.fail();
            }

            float[][] vectors = new float[bundle.centroids.size()][];
            for (int i = 0; i < bundle.centroids.size(); i++) {
                List<Double> row = bundle.centroids.get(i);
                if (row.size() != EMBEDDING_DIM) {
                    logger.warn(
                            "Centroid %d ('%s') has dim %d, expected %d — using fallback.",
                            i, bundle.classes.get(i), row.size(), EMBEDDING_DIM);
                    return CentroidParseResult.fail();
                }
                float[] v = new float[EMBEDDING_DIM];
                for (int j = 0; j < EMBEDDING_DIM; j++) {
                    double d = row.get(j);
                    if (!Double.isFinite(d)) {
                        // ML-08: NaN/Infinity in centroid breaks cosine scoring.
                        logger.warn(
                                "Centroid %d ('%s') component %d is non-finite (%s) — using fallback.",
                                i, bundle.classes.get(i), j, String.valueOf(d));
                        return CentroidParseResult.fail();
                    }
                    v[j] = (float) d;
                }
                // Defensive re-normalization: the on-disk vectors are unit-
                // norm already, but a small numerical drift after float-cast
                // is cheap to fix and prevents a subtle skew in cosine scores.
                l2Normalize(v);
                vectors[i] = v;
            }
            return CentroidParseResult.success(new ArrayList<>(bundle.classes), vectors);
        } catch (Throwable t) {
            logger.warn("Failed to parse centroids JSON: %s — using fallback.", t.getMessage());
            return CentroidParseResult.fail();
        }
    }
```

Note that `l2Normalize` is currently an instance method (no `static`). Verify with:
```bash
grep -n "l2Normalize\|static.*l2Normalize\|private.*l2Normalize" src/main/java/ru/ashesha/buildBattleAI/ml/MLService.java
```
If it is an instance method, `parseCentroidsJson` cannot be `static` *and* call it. Make `l2Normalize` static if it does not capture any instance state (read the method body — it likely only manipulates the passed-in array). The signature `private static void l2Normalize(float[] v)` is the desired form.

- [ ] **Step 5: Run the first activated test**

Run:
```bash
mvn -B -ntp test -Dtest='CentroidsJsonRobustnessIT#truncatedJsonFallsBackGracefully' -DfailIfNoTests=false
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Activate the other four disabled tests**

In the same file, replace the four remaining `@Disabled` test bodies. For each:

`wrongDimensionVectorsFallsBackGracefully` (replace lines 254-261):
```java
    @Test
    void wrongDimensionVectorsFallsBackGracefully() {
        PluginLogger mockLogger = mock(PluginLogger.class);
        // 3 floats instead of 128 — dim mismatch
        Reader reader = new StringReader(
                "{\"classes\":[\"cube\"],\"centroids\":[[0.1,0.2,0.3]]}");

        MLService.CentroidParseResult result =
                MLService.parseCentroidsJson(reader, mockLogger);

        assertFalse(result.isOk(),
                "Wrong-dim vectors must produce a failed parse result");
    }
```

`nanInfinityValuesFallsBackGracefully` (replace lines 278-286):
```java
    @Test
    void nanInfinityValuesFallsBackGracefully() {
        PluginLogger mockLogger = mock(PluginLogger.class);
        // Build a full-dim payload but plant NaN at position 5.
        StringBuilder vec = new StringBuilder("[");
        for (int i = 0; i < 128; i++) {
            if (i > 0) vec.append(",");
            vec.append(i == 5 ? "NaN" : "0.1");
        }
        vec.append("]");
        Reader reader = new StringReader(
                "{\"classes\":[\"cube\"],\"centroids\":[" + vec + "]}");

        MLService.CentroidParseResult result =
                MLService.parseCentroidsJson(reader, mockLogger);

        assertFalse(result.isOk(),
                "NaN component must produce a failed parse result");
    }
```

`emptyFileFallsBackGracefully` (replace lines 295-301):
```java
    @Test
    void emptyFileFallsBackGracefully() {
        PluginLogger mockLogger = mock(PluginLogger.class);
        Reader reader = new StringReader("");

        MLService.CentroidParseResult result =
                MLService.parseCentroidsJson(reader, mockLogger);

        assertFalse(result.isOk(),
                "Empty payload must produce a failed parse result (Gson returns null bundle)");
    }
```

`wrongStructureFallsBackGracefully` (replace lines 312-319):
```java
    @Test
    void wrongStructureFallsBackGracefully() {
        PluginLogger mockLogger = mock(PluginLogger.class);
        Reader reader = new StringReader("{\"wrong_key\":42}");

        MLService.CentroidParseResult result =
                MLService.parseCentroidsJson(reader, mockLogger);

        assertFalse(result.isOk(),
                "Missing required keys must produce a failed parse result");
    }
```

For each method also remove the `@Disabled(...)` annotation immediately above.

- [ ] **Step 7: Run all CentroidsJsonRobustnessIT tests**

Run:
```bash
mvn -B -ntp test -Dtest='CentroidsJsonRobustnessIT' -DfailIfNoTests=false
```
Expected: BUILD SUCCESS, all corruption-mode tests pass including `productionCentroidsJsonPassesStructuralInvariants`.

- [ ] **Step 8: Full pr-gate verify**

Run:
```bash
mvn -B -ntp -P pr-gate clean verify
```
Expected: BUILD SUCCESS, 12 837+ tests, 0 failures.

- [ ] **Step 9: Commit**

Run:
```bash
git add src/main/java/ru/ashesha/buildBattleAI/ml/MLService.java \
        src/test/java/ru/ashesha/buildBattleAI/ml/CentroidsJsonRobustnessIT.java

git commit -m "$(cat <<'EOF'
fix(ml): reject non-finite centroid components + add parser injection hook (ML-08)

The centroids.json parser swallowed NaN/Infinity values into the cosine-
scoring hot path. Adds a Double.isFinite() guard inside the per-row loop.

Extracts a package-private CentroidParseResult parseCentroidsJson(Reader,
PluginLogger) hook so tests can drive arbitrary corruption modes without
owning the classpath resource. Activates 5 corruption-mode tests in
CentroidsJsonRobustnessIT (truncated/wrong-dim/NaN/empty/wrong-structure).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Doc sync — drop "Known production gaps" block from CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Locate the block**

Run:
```bash
grep -n "Known production gaps documented via" CLAUDE.md
```
Expected: one hit pointing at the start of the bullet list ("DATA-01: …", "DATA-02: …", "DATA-04: …", "GAME-11: …", "ML-08: …"). Also note the heading line (likely starts with `- **Known production gaps documented via \`@Disabled\` tests:**`).

- [ ] **Step 2: Delete the block**

Edit `CLAUDE.md`. Remove the entire bullet item starting with `- **Known production gaps documented via \`@Disabled\` tests:**` and all 5 sub-bullets beneath it. Leave the surrounding section intact.

For verification, the block to delete looks like:
```markdown
  - **Known production gaps documented via `@Disabled` tests:**
    - DATA-01: `LocalRepository.load` corruption logs to `System.err`, not `PluginLogger.warn`.
    - DATA-02: `DataService.shutdown` nulls non-volatile `provider` field without memory barrier → autosave runnable NPEs under concurrent shutdown. Fix: declare `provider` as `volatile` OR local-var guard in the lambda.
    - DATA-04: `LocalRepository.flush` `IOException` logs to `System.err`, not `PluginLogger.error`.
    - GAME-11: `GameManager.startGameTickTimer` build-time expiry: `mirror.clearAll()` is not wrapped in try/catch, so a throw leaves `themeIndex` un-advanced while side-state already cleared.
    - ML-08: No NaN/Infinity guard in `centroids.json` parser — corrupted floats propagate to the embedding-comparison hot path.
```

- [ ] **Step 3: Sanity-grep**

Run:
```bash
grep -n "DATA-01\|DATA-02\|DATA-04\|GAME-11\|ML-08" CLAUDE.md
```
Expected: zero hits.

Also confirm production source has no stale references:
```bash
grep -rn "DATA-01\|DATA-02\|DATA-04\|GAME-11\|ML-08" src/main/java
```
Expected: zero hits.

- [ ] **Step 4: Confirm no @Disabled remnants pointing at the 5 IDs**

Run:
```bash
grep -rn "@Disabled" src/test/java | grep -E "DATA-0[124]|GAME-11|ML-08"
```
Expected: zero hits.

- [ ] **Step 5: Full pr-gate verify**

Run:
```bash
mvn -B -ntp -P pr-gate clean verify
```
Expected: BUILD SUCCESS, 12 837+ tests, 0 failures, still under 12 min.

Also run the stress profile to confirm DATA-02 fix is durable:
```bash
mvn -B -ntp -P stress test
```
Expected: BUILD SUCCESS, 10 stress tests pass.

- [ ] **Step 6: Commit**

Run:
```bash
git add CLAUDE.md
git commit -m "$(cat <<'EOF'
docs: clear resolved production gaps from CLAUDE.md

All 5 bugs (DATA-01/02/04, GAME-11, ML-08) closed by the preceding 4 fix
commits, with their @Disabled tests activated. Nothing left to document
under "Known production gaps".

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Final verification

After all 5 commits land locally:

- [ ] **Step 1: Confirm full test suite is still green**

```bash
mvn -B -ntp -P pr-gate clean verify
```
Expected: BUILD SUCCESS, 12 837+ tests, 0 failures, < 12 min total.

- [ ] **Step 2: Confirm stress suite is still green**

```bash
mvn -B -ntp -P stress test
```
Expected: BUILD SUCCESS, 10 stress tests pass (DATA-02 stress runs 100 cycles).

- [ ] **Step 3: Confirm commit graph**

```bash
git log --oneline -6
```
Expected: 5 fix/doc commits on top of the spec commit `d56301d`, in order:
1. `docs: clear resolved production gaps from CLAUDE.md`
2. `fix(ml): reject non-finite centroid components + add parser injection hook (ML-08)`
3. `fix(game): guard mirror.clearAll() in build-time expiry path (GAME-11)`
4. `fix(data): volatile DataService.provider + null-guard autosave lambda`
5. `fix(data): route LocalRepository I/O failures to PluginLogger`
6. `docs: spec for fixing 5 production bugs (DATA-01/02/04, GAME-11, ML-08)`

- [ ] **Step 4: Do NOT push**

Per `feedback_no_commits` memory: the assistant must NEVER push. Leave the 5 new commits in the local branch for the user to push at their discretion.
