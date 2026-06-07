# Test Coverage Expansion — Phase 3 (Integration: game) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 8 integration test classes covering risks GAME-01/02/03/04/05/07/09/11 in the `game/` module — countdown / rejoin / theme-wrap / snapshot / score-callback / force-end / reload / build-time atomicity.

**Architecture:** Bukkit-free in-JVM tests in package `ru.ashesha.buildBattleAI.game` (same package as production code so they can access package-private constructors of `GameSession` / `GamePlayer` / `PlayerSnapshot`). Tests use Mockito for `BuildBattleAI` / `PluginContext` / `Arena` / `EvaluationService` / `MessageService` / etc. and `MockedStatic<Bukkit>` to neutralise the scheduler. **Only** `GAME-04` (snapshot) needs MockBukkit-style registry initialisation — pattern lives in `PlayerSnapshotTest`.

Each IT file is independent. Subagent dispatched once per file; each subagent reads the relevant production code via `Read`, writes the test via `Write`, verifies via `mvn`, and commits.

**Tech stack:** Java 8, JUnit Jupiter 5.10, Mockito 5.12 with `MockedStatic`, Awaitility 4.2.2 (only where actual concurrency).

**Spec:** `docs/superpowers/specs/2026-06-07-test-coverage-expansion-design.md` §6 phase 3. Risks:
- **GAME-01** countdown cancellation — `CountdownCancellationIT.cancelledCountdownDoesNotStart`
- **GAME-02** rejoin no-dup — `PlayerRejoinIT.rejoinDoesNotDuplicate`
- **GAME-03** theme wrap — `ThemeAssignmentIT.themesShorterThanPlots`
- **GAME-04** snapshot deep-clone — `PlayerSnapshotIntegrityIT.snapshotIsDeepClone`
- **GAME-05** stale callback — `ScoreCallbackStalenessIT.staleCallbackIgnored`
- **GAME-07** force-end ordering — `ForceEndSessionOrderingIT.unregisterBeforeCancel`
- **GAME-09** reload cancels timers — `PluginReloadDuringCountdownIT.reloadCancelsAllTimers`
- **GAME-11** build-time expiry atomicity — `BuildTimeExpiryAtomicityIT.expiryIsAtomic`

**Reminder:** assistant commits after each task (no push). Memory rule updated 2026-06-07.

---

## Shared notes

- **Package:** `ru.ashesha.buildBattleAI.game;` — required for package-private access.
- **File suffix:** `*IT.java` (Surefire `<includes>` already updated in Phase 2 to pick these up).
- **Class-level tag:** `@Tag("integration")`.
- **Verification:** `mvn -B -ntp clean test -Dtest=<ClassName>` then `mvn -B -ntp clean test -P pr-gate` to confirm aggregate count grew by exactly the number of new test methods.
- **Maven binary:** `/opt/homebrew/bin/mvn`.
- **Existing canonical patterns** (subagent MUST read first):
  - `src/test/java/ru/ashesha/buildBattleAI/game/GameSessionTest.java` — `MockedStatic<Bukkit>` template
  - `src/test/java/ru/ashesha/buildBattleAI/game/GameManagerTest.java` — `GameManager.enable()` mock chain
  - `src/test/java/ru/ashesha/buildBattleAI/game/PlayerSnapshotTest.java` — MockBukkit + JDK Proxy seed (only needed for GAME-04)

---

## Task 1: `CountdownCancellationIT` (GAME-01)

**Risk:** A countdown that's cancelled (player leaves, manager shutdown, etc.) must NOT later fire `startGame()` via its delayed task.

**Approach:** Don't call `startCountdown` (which would schedule a real `runTaskTimer`). Instead:
1. Construct `GameSession` directly with a mocked `Arena`.
2. Set `session.state(ArenaState.COUNTDOWN)` and `session.countdownTaskId(42)` (fake task id).
3. `MockedStatic<Bukkit>` → `getScheduler()` returns a `mock(BukkitScheduler.class)`.
4. Call `session.cancelAllTasks()`.
5. Verify `scheduler.cancelTask(42)` was called AND `session.countdownTaskId()` is reset to `-1` AND `session.state()` has NOT progressed to `PLAYING`.

**File:** `src/test/java/ru/ashesha/buildBattleAI/game/CountdownCancellationIT.java`

Skeleton:
```java
package ru.ashesha.buildBattleAI.game;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.arena.api.Arena;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration test — covers GAME-01.
 * Invariant: a cancelled countdown does not later fire startGame().
 * Mechanism under test: GameSession.cancelAllTasks() must cancel the
 * scheduled countdownTaskId and leave state in COUNTDOWN (caller
 * decides next state).
 */
@Tag("integration")
class CountdownCancellationIT {

    @Test
    @DisplayName("GAME-01: cancelAllTasks cancels the countdown task ID and clears the slot")
    void cancelledCountdownDoesNotStart() {
        Arena arena = mock(Arena.class);
        when(arena.maxPlayers()).thenReturn(2);

        GameSession session = new GameSession(arena);
        session.state(ArenaState.COUNTDOWN);
        session.countdownTaskId(42);
        session.gameTickTaskId(-1);
        session.endingTaskId(-1);

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            session.cancelAllTasks();
            verify(scheduler).cancelTask(42);
            verify(scheduler, never()).cancelTask(intThat(id -> id < 0));
        }
        assertEquals(-1, session.countdownTaskId(),
                "countdownTaskId must be reset to -1 after cancellation");
        assertEquals(ArenaState.COUNTDOWN, session.state(),
                "cancelAllTasks must NOT alter state — state transitions belong to GameManager");
    }
}
```

Verification: `mvn -B -ntp clean test -Dtest=CountdownCancellationIT`. Then pr-gate.

Commit: `test(integration): cover GAME-01 countdown cancellation`

---

## Task 2: `PlayerRejoinIT` (GAME-02)

**Risk:** rapid disconnect/rejoin of same UUID must not produce duplicates in `session.players()`.

**Approach:** `GameSession.players()` is a `LinkedHashMap<UUID, GamePlayer>`. Adding a `GamePlayer` with the same key twice replaces the entry — `LinkedHashMap` semantics guarantee no duplicates. This test asserts the contract directly on the session, AND verifies that `removePlayer` + `addPlayer` ping-pong (the rejoin sequence) ends with exactly one entry for the same UUID.

**Key question for the subagent:** does `GameSession` expose `players()` directly, or do additions go through a setter/method like `addPlayer`? Read the source.

**File:** `src/test/java/ru/ashesha/buildBattleAI/game/PlayerRejoinIT.java`

Skeleton (adapt `addPlayer/removePlayer` to actual API):

```java
package ru.ashesha.buildBattleAI.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.arena.api.Arena;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration test — covers GAME-02.
 * Invariant: rejoin of the same UUID produces no duplicate in players().
 */
@Tag("integration")
class PlayerRejoinIT {

    @Test
    @DisplayName("GAME-02: rejoining player does not duplicate in session.players()")
    void rejoinDoesNotDuplicate() {
        Arena arena = mock(Arena.class);
        when(arena.maxPlayers()).thenReturn(4);
        GameSession session = new GameSession(arena);

        UUID id = UUID.randomUUID();
        PlayerSnapshot snap = mock(PlayerSnapshot.class);
        GamePlayer gp1 = new GamePlayer(id, "Alice", 0, snap, 120);

        // First join.
        session.players().put(id, gp1);
        assertEquals(1, session.players().size());

        // Simulated disconnect.
        session.players().remove(id);
        assertEquals(0, session.players().size());

        // Rejoin — same UUID, new GamePlayer (fresh snapshot).
        GamePlayer gp2 = new GamePlayer(id, "Alice", 1, mock(PlayerSnapshot.class), 120);
        session.players().put(id, gp2);

        assertEquals(1, session.players().size(),
                "rejoin must not produce duplicates — LinkedHashMap semantics");
        assertSame(gp2, session.players().get(id),
                "the latest GamePlayer instance must be the one in the map");
    }

    @Test
    @DisplayName("GAME-02: re-adding without removing replaces the value (no duplicate)")
    void readdReplacesNotDuplicates() {
        Arena arena = mock(Arena.class);
        GameSession session = new GameSession(arena);

        UUID id = UUID.randomUUID();
        GamePlayer first = new GamePlayer(id, "Bob", 0, mock(PlayerSnapshot.class), 120);
        GamePlayer second = new GamePlayer(id, "Bob", 0, mock(PlayerSnapshot.class), 120);

        session.players().put(id, first);
        session.players().put(id, second);

        assertEquals(1, session.players().size());
        assertSame(second, session.players().get(id));
    }
}
```

Commit: `test(integration): cover GAME-02 player rejoin no-duplicate`

---

## Task 3: `ThemeAssignmentIT` (GAME-03)

**Risk:** theme assignment must wrap correctly when `themes.size() < plots.size()`.

**Approach:** test the wrap arithmetic in isolation — `GameSession.getTheme(index)` returns `themes.get(index % themes.size())`, and `GamePlayer.advanceTheme(themeCount)` does `themeIndex = (themeIndex + 1) % themeCount`. Pure arithmetic, no Bukkit.

**File:** `src/test/java/ru/ashesha/buildBattleAI/game/ThemeAssignmentIT.java`

Skeleton:

```java
package ru.ashesha.buildBattleAI.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.arena.api.Arena;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration test — covers GAME-03.
 * Invariant: theme indices wrap when themes.size() &lt; plots.size().
 */
@Tag("integration")
class ThemeAssignmentIT {

    @Test
    @DisplayName("GAME-03: getTheme(i) wraps via index % themes.size()")
    void themesShorterThanPlots() {
        Arena arena = mock(Arena.class);
        when(arena.maxPlayers()).thenReturn(4); // 4 plots
        GameSession session = new GameSession(arena);

        // Only 2 themes — must wrap for plots 2 and 3.
        List<String> themes = Arrays.asList("castle", "tree");
        session.setThemes(themes);

        assertEquals("castle", session.getTheme(0));
        assertEquals("tree", session.getTheme(1));
        assertEquals("castle", session.getTheme(2), "index 2 must wrap to themes[0]");
        assertEquals("tree", session.getTheme(3), "index 3 must wrap to themes[1]");
        assertEquals("castle", session.getTheme(8), "index 8 must wrap to themes[0]");
    }

    @Test
    @DisplayName("GAME-03: advanceTheme wraps via (themeIndex + 1) % themeCount")
    void advanceThemeWrapsToZero() {
        UUID pid = UUID.randomUUID();
        GamePlayer gp = new GamePlayer(pid, "Charlie", 0, mock(PlayerSnapshot.class), 120);

        // Drive themeIndex to themeCount - 1, then advance — must wrap to 0.
        int themeCount = 3;
        gp.advanceTheme(themeCount); // 0 -> 1
        gp.advanceTheme(themeCount); // 1 -> 2
        gp.advanceTheme(themeCount); // 2 -> 0 (wrap)
        assertEquals(0, gp.themeIndex(), "advanceTheme must wrap at themeCount");
    }

    @Test
    @DisplayName("GAME-03: getTheme on empty theme list returns 'unknown'")
    void emptyThemeListReturnsUnknown() {
        Arena arena = mock(Arena.class);
        GameSession session = new GameSession(arena);
        // setThemes not called — themes is empty by default
        assertEquals("unknown", session.getTheme(0),
                "empty theme list must return 'unknown' (sentinel from getTheme guard)");
    }
}
```

**Pre-read check:** the subagent must confirm `session.setThemes(List)` exists. If the API differs (e.g. `themes(List)` fluent setter, or themes injected via constructor), adapt. The exploration report didn't dig into the setter API for `setThemes` — verify by reading `GameSession.java` around line 130-145.

Commit: `test(integration): cover GAME-03 theme assignment wrap arithmetic`

---

## Task 4: `PlayerSnapshotIntegrityIT` (GAME-04)

**Risk:** `PlayerSnapshot.capture(player, version)` must be a DEEP clone — mutating the snapshot fields must not affect the live player.

**Approach:** read `PlayerSnapshotTest` for the MockBukkit + JDK Proxy seed pattern. Need MockBukkit because `PotionEffectType` static init touches `Registry.EFFECT`.

Test focus:
1. `capture(player, ServerVersion.V_1_21)` produces a snapshot whose `inventoryContents` array is a different reference from `player.getInventory().getContents()`.
2. Mutating `snapshot.inventoryContents()[0]` to a different `ItemStack` does NOT change what `player.getInventory().getContents()[0]` returns.
3. On `ServerVersion.V_1_8`, `snapshot.offHand()` is `null`. On `V_1_9`+, it is non-null when the player has an off-hand item.

**File:** `src/test/java/ru/ashesha/buildBattleAI/game/PlayerSnapshotIntegrityIT.java`

This task is harder than the others. The subagent SHOULD:
1. Read `PlayerSnapshotTest` in full and copy/adapt the `@BeforeAll` / `@AfterAll` registry-proxy setup.
2. Build a Player mock with stubbed `getInventory().getContents()` returning a small `ItemStack[]`, capture, mutate, assert.
3. If MockBukkit's static init proves brittle (timing, classloading), STOP and report — we may need to skip GAME-04 in this phase and defer to a dedicated mini-plan.

Commit: `test(integration): cover GAME-04 PlayerSnapshot deep-clone integrity`

---

## Task 5: `ScoreCallbackStalenessIT` (GAME-05)

**Risk:** a callback whose `themeIndex` was advanced before it ran is silently ignored — `handleScore` returns early without bumping score.

**Approach:** `handleScore` is private. Capture the registered `EvaluationCallback` lambda via `ArgumentCaptor` on `evaluationService.registerSession(session, ANY_CALLBACK)`. Then invoke the captured callback with a STALE `themeIndex` and assert the player's score did NOT change.

**Pre-read needed:** the `EvaluationCallback` functional signature (Phase 2 found it's 4-arg `(UUID, int, List<TopKEntry>, boolean)`).

**File:** `src/test/java/ru/ashesha/buildBattleAI/game/ScoreCallbackStalenessIT.java`

Skeleton (adapt based on actual API discovered during pre-read):

```java
package ru.ashesha.buildBattleAI.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationCallback;
// other imports as needed during implementation

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("integration")
class ScoreCallbackStalenessIT {

    @Test
    @DisplayName("GAME-05: callback with stale themeIndex is silently ignored")
    void staleCallbackIgnored() {
        // 1. Build a real GamePlayer with themeIndex = 5.
        // 2. Reflectively populate GameManager.sessions with a real GameSession
        //    holding that GamePlayer.
        // 3. Capture the EvaluationCallback registered in startGame via
        //    ArgumentCaptor on evaluationService.registerSession(...).
        //    Alternative: skip startGame and reach handleScore via reflection.
        // 4. Invoke handleScore(arenaName, playerId, 4 /*stale, not 5*/) and
        //    assert gp.score() is still 0.
        // 5. Invoke handleScore(arenaName, playerId, 5 /*correct*/) and
        //    assert gp.score() is now 1.
        //
        // Implementer: read GameManager.handleScore source first to decide
        // between (a) reflective access to private handleScore vs (b)
        // ArgumentCaptor on registerSession to capture the lambda. The
        // ArgumentCaptor path is more robust to internal renames but
        // requires driving startGame end-to-end, which pulls in many
        // collaborator stubs. The reflection path is faster to write.
        //
        // Use the reflection path unless the pre-read reveals a Lombok-
        // generated public accessor.
        throw new UnsupportedOperationException(
                "implementer fills this in after reading GameManager.handleScore");
    }
}
```

The subagent MUST replace the body. The reflection path looks like:

```java
Method handleScore = GameManager.class.getDeclaredMethod(
        "handleScore", String.class, UUID.class, int.class);
handleScore.setAccessible(true);

Field sessionsField = GameManager.class.getDeclaredField("sessions");
sessionsField.setAccessible(true);
@SuppressWarnings("unchecked")
java.util.Map<String, GameSession> sessions =
        (java.util.Map<String, GameSession>) sessionsField.get(gameManager);
sessions.put("arena-1", session);

handleScore.invoke(gameManager, "arena-1", playerId, 4 /*stale*/);
assertEquals(0, gp.score(), "stale callback must not score");

handleScore.invoke(gameManager, "arena-1", playerId, 5 /*correct*/);
assertEquals(1, gp.score(), "correct themeIndex must score");
```

Construct `gameManager` via the canonical `GameManagerTest` mock chain.

Commit: `test(integration): cover GAME-05 stale score callback ignored`

---

## Task 6: `ForceEndSessionOrderingIT` (GAME-07)

**Risk:** `forceEndSession` must `evaluationService.unregisterSession(arenaName)` BEFORE `session.cancelAllTasks()`. Otherwise a callback could fire between cancelAllTasks and unregisterSession, against a session that's already had its timers cancelled.

**Approach:** Mockito `InOrder` across the evaluation-service unregister and the `MockedStatic<Bukkit>` scheduler.cancelTask calls. Drive `forceEndSession` via `GameManager.shutdown()` or by reflectively calling the private method.

**File:** `src/test/java/ru/ashesha/buildBattleAI/game/ForceEndSessionOrderingIT.java`

Approach (subagent fills):

1. Construct `GameManager` with full mock chain (see Task 5).
2. Stub `evaluationService.unregisterSession(any())` to record an InOrder.
3. Build a real `GameSession` with `countdownTaskId = 10`, register in `sessions` reflectively.
4. With `MockedStatic<Bukkit>` returning a mocked `BukkitScheduler`:
   - Call `manager.shutdown()` (which internally walks active sessions and force-ends each).
   - Capture `InOrder` verification:
     ```java
     InOrder order = inOrder(evaluationService, scheduler);
     order.verify(evaluationService).unregisterSession("arena-1");
     order.verify(scheduler).cancelTask(10);
     ```

**Edge case the subagent must handle:** the call to `evaluationService.unregisterSession` may happen INSIDE the loop iteration, and `cancelAllTasks` also happens INSIDE that same iteration. Both must be observable through the mocks.

Commit: `test(integration): cover GAME-07 forceEnd unregister-before-cancel ordering`

---

## Task 7: `PluginReloadDuringCountdownIT` (GAME-09)

**Risk:** `manager.shutdown()` (called by `PluginContext.shutdown` during plugin reload) must cancel all active countdown and game-tick timers.

**Approach:** seed `sessions` reflectively with sessions in various states (COUNTDOWN, PLAYING) each with distinct `countdownTaskId` / `gameTickTaskId` values. Call `manager.shutdown()`. Assert every task ID was cancelled.

**File:** `src/test/java/ru/ashesha/buildBattleAI/game/PluginReloadDuringCountdownIT.java`

```java
@Test
@DisplayName("GAME-09: shutdown cancels countdown + game-tick timers across all sessions")
void reloadCancelsAllTimers() {
    // Mock chain — GameManagerTest pattern.
    GameSession s1 = new GameSession(mockArena1);
    s1.state(ArenaState.COUNTDOWN);
    s1.countdownTaskId(101);
    s1.gameTickTaskId(-1);

    GameSession s2 = new GameSession(mockArena2);
    s2.state(ArenaState.PLAYING);
    s2.countdownTaskId(-1);
    s2.gameTickTaskId(202);

    // Reflectively put both sessions into manager.sessions.

    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        manager.shutdown();
        verify(scheduler).cancelTask(101);
        verify(scheduler).cancelTask(202);
        verify(scheduler, never()).cancelTask(-1);
    }
}
```

Commit: `test(integration): cover GAME-09 plugin reload cancels timers`

---

## Task 8: `BuildTimeExpiryAtomicityIT` (GAME-11)

**Risk:** when build-time expires for a player, the code path advances `themeIndex` AND calls `mirror.clearAll()`. If `clearAll()` throws, both must be rolled back — OR both must be committed. Production currently must pick one; the test pins the actual behaviour.

**Approach:**

1. **Pre-read** the build-time expiry path in `GameManager`. Look for `decrementBuildTime` and the branch where `buildTimeRemaining <= 0` triggers theme rotation + zone clear. Find the exact sequence: is `advanceTheme` called BEFORE or AFTER `mirror.clearAll()`? Is there a try-catch around `clearAll()`?

2. **Decide on the test contract** based on the actual code:
   - If the code calls `mirror.clearAll()` FIRST then `advanceTheme()`: the test asserts that a throwing `clearAll()` leaves `themeIndex` unchanged.
   - If the code calls `advanceTheme()` FIRST then `clearAll()`: the test asserts that a throwing `clearAll()` does NOT roll back `themeIndex` (or that it does, if the code uses try-catch).

3. **Test body**: build a real `GamePlayer` with `themeIndex = 0`, `buildTimeRemaining = 1`. Build a real `GameSession` with `themes = ["a", "b"]`. Mock `MutablePlotScene` to throw `RuntimeException` on `clearAll()`. Drive one game tick (reflectively or via a public method). Assert the actual atomicity behaviour matches production.

If the implementer finds a real production bug (e.g. half-committed state), they should:
- Write the test that EXPOSES the bug (assert what SHOULD happen).
- Mark the test `@Disabled("GAME-11: open bug — half-committed state")` with an explanatory comment.
- Report the finding.

**File:** `src/test/java/ru/ashesha/buildBattleAI/game/BuildTimeExpiryAtomicityIT.java`

Commit:
- If the production behaviour is sane: `test(integration): cover GAME-11 build-time expiry atomicity`
- If a bug is found: `test(integration): cover GAME-11 expiry — document non-atomic behaviour`

---

## Task 9: Final verification

After all 8 IT files are committed:

```bash
mvn -B -ntp clean verify -P pr-gate
```

Expected: BUILD SUCCESS, total tests ≈ 12776 + (1+2+3+...+test counts from each IT) — the subagent reports its own count per task.

Then:

```bash
mvn -B -ntp clean test -P integration
```

Expected: 7 (Phase 2 ITs) + 8+ (Phase 3 ITs) = 15+ tests.

Commit any cleanup as `chore: ...`.

---

## Self-review checklist

- [ ] All 8 IT files exist under `src/test/java/ru/ashesha/buildBattleAI/game/`.
- [ ] All 8 declare `package ru.ashesha.buildBattleAI.game;` (NOT `integration.game`).
- [ ] All 8 carry `@Tag("integration")`.
- [ ] No production code modified.
- [ ] `mvn -P pr-gate` green; aggregate count grew as expected.
- [ ] Each IT committed separately with descriptive message.
- [ ] GAME-04 either landed or was explicitly deferred with a follow-up issue.

---

## Out of scope

- GAME-06 (not in spec)
- GAME-08 (stress — lives in Phase 5)
- GAME-10/12+ (don't exist in spec)
- Phase 4+ (separate plans)

## What comes next

Phase 4 (ml/data/arena/config integration). Then Phase 5 (stress), Phase 6 (E2E), Phase 7 (ML-IT), Phase 8 (bench + nightly workflow + final CLAUDE.md).
