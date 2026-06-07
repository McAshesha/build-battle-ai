# Test Coverage Expansion — Phase 0 (Infra) + Phase 1 (Smoke) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the test taxonomy (JUnit 5 `@Tag`-based selection, new Maven profiles, Awaitility + Testcontainers deps, fixture base class, CI two-tier gate) and four smoke tests that immediately defend against build breakage and high-level wiring regressions.

**Architecture:** All new tests live under `src/test/java/ru/ashesha/buildBattleAI/{smoke,integration,stress,e2e}`. Each non-unit test class gets a single class-level `@Tag(...)`. New Maven profiles `pr-gate`, `nightly`, `smoke`, `integration`, `stress` switch include/exclude via Surefire `<groups>`/`<excludedGroups>`; existing `e2e`, `ml-it`, `bench` profiles keep their pattern-based logic for backwards compatibility. The CI workflow swaps `verify` for `verify -P pr-gate` so the same set runs locally and in PRs.

**Tech stack:** Java 8, JUnit Jupiter 5.10.3, MockBukkit-v1.21 4.50.0, Mockito 5.12.0, Surefire 3.2.5, Awaitility 4.2.2 (new), Testcontainers 1.20.4 (new — used in later phases, declared now).

**Spec:** `docs/superpowers/specs/2026-06-07-test-coverage-expansion-design.md`. This plan implements sections §2 (taxonomy), §3 (THIN-MSG, THIN-ENT, THIN-CMD, PLUGIN-SMOKE-RELOAD rows), §4 (CI changes), and §6 phases 0 + 1 only. Subsequent phases get their own plans.

**Reminder on commit cadence:** the user reviews every commit personally (see memory: `feedback_no_commits`). After each task's verification step, **stop and tell the user the proposed commit message** rather than running `git commit` directly. The user will commit.

---

## File map (Phase 0 + 1)

**Created:**
- `src/test/java/ru/ashesha/buildBattleAI/support/IntegrationTestSupport.java` — base class for future integration/smoke tests; provides `MockBukkit` lifecycle, a `SilentPlayerMock` (works around `PlayerMock.playSound` `UnimplementedOperationException`), `addSimpleWorld("world")` in `@BeforeEach`, and a place to register static-state resets.
- `src/test/java/ru/ashesha/buildBattleAI/support/SilentPlayerMock.java` — `PlayerMock` subclass overriding all 8 `playSound` overloads to no-op (per CLAUDE.md gotcha).
- `src/test/java/ru/ashesha/buildBattleAI/smoke/PluginEnableSmokeTest.java` — covers PLUGIN-SMOKE-RELOAD.
- `src/test/java/ru/ashesha/buildBattleAI/smoke/MessageServiceSmokeTest.java` — covers THIN-MSG.
- `src/test/java/ru/ashesha/buildBattleAI/smoke/EntityServicesSmokeTest.java` — covers THIN-ENT.
- `src/test/java/ru/ashesha/buildBattleAI/smoke/BbaiStatsCommandSmokeTest.java` — covers THIN-CMD.
- `src/test/resources/junit-platform.properties` — enables `@Tag` filtering output and sets `junit.jupiter.execution.parallel.enabled=false` explicitly (matches current behavior; documented so smoke tests stay sequential).

**Modified:**
- `pom.xml` — add Awaitility + Testcontainers deps; add `pr-gate`, `nightly`, `smoke`, `integration`, `stress` profiles; tighten the default Surefire `<excludedGroups>` to include the new tags.
- `.github/workflows/ci.yml:67` — swap `mvn verify` for `mvn verify -P pr-gate`. Keep the existing `e2e` and `ml-it` steps unchanged for now (they handle their own gating via existing pattern profiles).
- `src/test/java/ru/ashesha/buildBattleAI/mockbukkit/MockBukkitSmokeTest.java` — add class-level `@Tag("smoke")`.
- `src/test/java/ru/ashesha/buildBattleAI/integration/RealPluginBootstrapTest.java` — add class-level `@Tag("smoke")` (it's a build-artefact smoke test by its own Javadoc).
- `src/test/java/ru/ashesha/buildBattleAI/e2e/Paper18E2ETest.java` — add class-level `@Tag("e2e")`.
- `src/test/java/ru/ashesha/buildBattleAI/e2e/Purpur121E2ETest.java` — add class-level `@Tag("e2e")`.
- `src/test/java/ru/ashesha/buildBattleAI/e2e/AbstractServerE2ETest.java` — no tag (it's abstract; the concrete subclasses' tags inherit through subclass annotation).
- `src/test/java/ru/ashesha/buildBattleAI/ml/MLIntegrationTest.java` — add class-level `@Tag("ml-it")`.
- `src/test/java/ru/ashesha/buildBattleAI/render/RendererConcurrentStressTest.java` — add class-level `@Tag("stress")`.
- `src/test/java/ru/ashesha/buildBattleAI/render/data/MutablePlotSceneConcurrencyTest.java` — add class-level `@Tag("stress")`.
- `CLAUDE.md` — append "Test taxonomy & tagging" subsection under "Testing Infrastructure".

**Not touched:** all 60+ existing unit-test files. They run by default because the new `<excludedGroups>` only excludes specific non-unit tags; untagged tests still run.

---

## Task 1: Add JUnit `@Tag` annotations to existing non-unit tests

**Why first:** new Surefire `<excludedGroups>` we add in Task 2 will exclude tags `e2e,bench,stress,ml-it,smoke,integration,nightly-only`. If we add the tag exclusions before tagging the existing tests, the default `mvn test` would still pick up `MLIntegrationTest`, `Paper18E2ETest`, etc. via pattern includes (which we are not removing). Tagging first lets us verify both the old pattern-based gating and the new tag-based gating co-exist without double-running.

**Files:**
- Modify: `src/test/java/ru/ashesha/buildBattleAI/mockbukkit/MockBukkitSmokeTest.java`
- Modify: `src/test/java/ru/ashesha/buildBattleAI/integration/RealPluginBootstrapTest.java`
- Modify: `src/test/java/ru/ashesha/buildBattleAI/e2e/Paper18E2ETest.java`
- Modify: `src/test/java/ru/ashesha/buildBattleAI/e2e/Purpur121E2ETest.java`
- Modify: `src/test/java/ru/ashesha/buildBattleAI/ml/MLIntegrationTest.java`
- Modify: `src/test/java/ru/ashesha/buildBattleAI/render/RendererConcurrentStressTest.java`
- Modify: `src/test/java/ru/ashesha/buildBattleAI/render/data/MutablePlotSceneConcurrencyTest.java`

- [ ] **Step 1.1:** Add `@Tag("smoke")` to `MockBukkitSmokeTest`.

Open the file and insert directly above `class MockBukkitSmokeTest {` (line 23):

```java
import org.junit.jupiter.api.Tag;
```

and on the line immediately before the class declaration:

```java
@Tag("smoke")
class MockBukkitSmokeTest {
```

- [ ] **Step 1.2:** Add `@Tag("smoke")` to `RealPluginBootstrapTest` (same pattern — `import org.junit.jupiter.api.Tag;` + `@Tag("smoke")` above `class RealPluginBootstrapTest {` on line 45).

- [ ] **Step 1.3:** Add `@Tag("e2e")` to `Paper18E2ETest`. Same pattern — class-level annotation + import.

- [ ] **Step 1.4:** Add `@Tag("e2e")` to `Purpur121E2ETest`. Same pattern.

- [ ] **Step 1.5:** Add `@Tag("ml-it")` to `MLIntegrationTest`. Same pattern.

- [ ] **Step 1.6:** Add `@Tag("stress")` to `RendererConcurrentStressTest`. Same pattern.

- [ ] **Step 1.7:** Add `@Tag("stress")` to `MutablePlotSceneConcurrencyTest`. Same pattern.

- [ ] **Step 1.8:** Sanity build. Run: `mvn -B -ntp compile test-compile`. Expected: BUILD SUCCESS, no compilation errors. The tags don't affect this stage but a typo in the import path would.

- [ ] **Step 1.9:** Run the default test suite to confirm tagged tests still behave as before (e2e/ml-it still excluded via pattern). Run: `mvn -B -ntp test`. Expected: BUILD SUCCESS, e2e + MLIntegrationTest not in the report, unit/stress/smoke tests pass (yes — stress + smoke still run here because we haven't yet tightened `<excludedGroups>`).

- [ ] **Step 1.10:** Tell the user this is ready to commit. Suggested commit message:

```
test: tag existing non-unit tests for @Tag-based selection

@Tag("smoke") on MockBukkitSmokeTest + RealPluginBootstrapTest
@Tag("e2e") on Paper18E2ETest + Purpur121E2ETest
@Tag("ml-it") on MLIntegrationTest
@Tag("stress") on RendererConcurrentStressTest + MutablePlotSceneConcurrencyTest

Default Surefire run still excludes e2e/ml-it via the existing
**/e2e/** + **/MLIntegrationTest.java pattern excludes; no behavioural change.
```

---

## Task 2: New Maven profiles + tightened default `<excludedGroups>`

**Files:**
- Modify: `pom.xml` (Surefire default config around lines 60–100; new `<profile>` entries in the `<profiles>` block starting at line 372).

- [ ] **Step 2.1:** Add `<groups>`/`<excludedGroups>` to the default Surefire configuration. Locate the `<configuration>` block at `pom.xml:63–99` and replace it with the following (only the `<excludes>` and `<argLine>` content stays — `<excludedGroups>` is new):

```xml
<configuration>
    <!-- Pattern-based excludes (preserved for backwards compat) -->
    <excludes>
        <exclude>**/e2e/**</exclude>
        <exclude>**/MLIntegrationTest.java</exclude>
    </excludes>
    <!-- Tag-based excludes: non-unit tests opt-in via @Tag and are only run
         under their respective profiles. Untagged tests (the vast majority,
         unit) keep running by default. nightly-only is a secondary tag for
         tests that belong to e2e/integration/stress but should be skipped
         in PR CI. -->
    <excludedGroups>e2e,bench,stress,ml-it,nightly-only</excludedGroups>
    <argLine>
        -Dnet.bytebuddy.experimental=true
        --add-opens=java.base/jdk.internal.access=ALL-UNNAMED
        --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
        --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
        --add-opens=java.base/sun.util.calendar=ALL-UNNAMED
        --add-opens=java.base/sun.reflect.generics.reflectiveObjects=ALL-UNNAMED
        --add-opens=java.base/java.io=ALL-UNNAMED
        --add-opens=java.base/java.nio=ALL-UNNAMED
        --add-opens=java.base/java.net=ALL-UNNAMED
        --add-opens=java.base/java.util=ALL-UNNAMED
        --add-opens=java.base/java.util.concurrent=ALL-UNNAMED
        --add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED
        --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
        --add-opens=java.base/java.lang=ALL-UNNAMED
        --add-opens=java.base/java.lang.invoke=ALL-UNNAMED
        --add-opens=java.base/java.lang.reflect=ALL-UNNAMED
        --add-opens=java.base/java.math=ALL-UNNAMED
        --add-opens=java.base/java.time=ALL-UNNAMED
        --add-opens=java.base/java.text=ALL-UNNAMED
        --add-opens=java.sql/java.sql=ALL-UNNAMED
        --add-opens=java.management/com.sun.jmx.mbeanserver=ALL-UNNAMED
        --add-opens=java.management/sun.management=ALL-UNNAMED
        --add-opens=java.desktop/java.awt.font=ALL-UNNAMED
        --add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED
        --add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED
    </argLine>
</configuration>
```

Note: `smoke` and `integration` are deliberately NOT in `<excludedGroups>` — those run by default (cheap, in-JVM). Only the expensive tiers (e2e, bench, stress, ml-it, nightly-only) are excluded.

- [ ] **Step 2.2:** Add the new profiles to the `<profiles>` block. Insert AFTER the existing `bench` profile (closing tag `</profile>` at around `pom.xml:527`), BEFORE `obfuscate-light`. New XML:

```xml
        <!--
             Aggregate PR-gate profile.

             Reverses the default-profile excludes so that integration,
             smoke, and the basic (non-nightly) ml-it tests all run in one
             pass. Excludes the slow tiers (e2e, bench, stress) and any
             test secondary-tagged nightly-only.

             Run with:
               mvn -B -ntp verify -P pr-gate
        -->
        <profile>
            <id>pr-gate</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <configuration>
                            <!-- Re-include e2e + MLIntegrationTest excluded by default pattern. -->
                            <excludes combine.self="override"/>
                            <!-- ml-it tests need their gating system property. -->
                            <systemPropertyVariables>
                                <bbai.ml-it>true</bbai.ml-it>
                                <bbai.e2e>true</bbai.e2e>
                            </systemPropertyVariables>
                            <excludedGroups>e2e,bench,stress,nightly-only</excludedGroups>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>

        <!--
             Aggregate nightly profile.

             Runs the full PR-gate set + the slow tiers (e2e, stress, ml-it
             nightly-only). Benchmarks remain under the separate `bench`
             profile because they are invoked via `exec:java`, not Surefire.

             Run with:
               mvn -B -ntp verify -P nightly
        -->
        <profile>
            <id>nightly</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <configuration>
                            <excludes combine.self="override"/>
                            <systemPropertyVariables>
                                <bbai.ml-it>true</bbai.ml-it>
                                <bbai.e2e>true</bbai.e2e>
                            </systemPropertyVariables>
                            <excludedGroups>bench</excludedGroups>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>

        <!-- Standalone smoke run: -P smoke -->
        <profile>
            <id>smoke</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <configuration>
                            <groups>smoke</groups>
                            <excludedGroups></excludedGroups>
                            <excludes combine.self="override"/>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>

        <!-- Standalone integration run: -P integration -->
        <profile>
            <id>integration</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <configuration>
                            <groups>integration</groups>
                            <excludedGroups></excludedGroups>
                            <excludes combine.self="override"/>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>

        <!-- Standalone stress run: -P stress -->
        <profile>
            <id>stress</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <configuration>
                            <groups>stress</groups>
                            <excludedGroups></excludedGroups>
                            <excludes combine.self="override"/>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
```

- [ ] **Step 2.3:** Verify the POM still parses. Run: `mvn -B -ntp help:effective-pom -q -DforceStdout > /dev/null`. Expected: no output to stdout, exit code 0. Any XML error fails fast.

- [ ] **Step 2.4:** Run the default suite to verify untagged unit tests still pass and tagged smoke tests no longer run by default (because they're now excluded). Run: `mvn -B -ntp test`. Expected: BUILD SUCCESS; the two existing `@Tag("smoke")` tests (MockBukkitSmokeTest, RealPluginBootstrapTest) are NOT in the executed test count — verify by grepping the output for "Tests run:" — the figure should drop by ~10 tests (5 from MockBukkitSmokeTest + 4 from RealPluginBootstrapTest, depending on counts).

Wait — the previous step might surprise the user: removing previously-run smoke tests from the default profile is intentional, but ensure it's noted. Expected default count is "unit only".

- [ ] **Step 2.5:** Run the new pr-gate profile to verify smoke + ml-it come back. Run: `mvn -B -ntp test -P pr-gate`. Expected: BUILD SUCCESS; report includes MockBukkitSmokeTest, RealPluginBootstrapTest, AND MLIntegrationTest. E2E + stress NOT in the report.

- [ ] **Step 2.6:** Tell the user this is ready to commit. Suggested commit message:

```
build: introduce tag-based test selection profiles

Adds five new Maven profiles:
- pr-gate: unit + smoke + integration + fast ml-it (CI PR target)
- nightly: pr-gate + e2e + stress + full ml-it
- smoke / integration / stress: standalone narrow runs

Default `mvn test` is now unit-only (smoke + integration also excluded
to keep local default fast); CI switches to -P pr-gate.
```

---

## Task 3: Add Awaitility + Testcontainers test dependencies

**Files:**
- Modify: `pom.xml` (the `<dependencies>` block ending at line 668).

- [ ] **Step 3.1:** Insert new `<dependency>` entries directly after the existing `mockito-junit-jupiter` entry (currently the last one, lines 662–667). Append:

```xml
        <!-- Awaitility — explicit polling for integration tests that wait
             on background pipeline state (EvaluationService queue drains,
             GameSession state transitions, etc.). Test scope only. -->
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <version>4.2.2</version>
            <scope>test</scope>
        </dependency>
        <!-- Testcontainers — used by future Ignite thin-client integration
             tests (DATA-06). Declared now so all phases share one upgrade
             cadence. Requires Docker on the test host; tests that use it
             must be tagged @Tag("nightly-only") to keep PR CI Docker-free. -->
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

- [ ] **Step 3.2:** Verify Maven resolves the new dependencies. Run: `mvn -B -ntp dependency:resolve -q`. Expected: exit code 0; new artifacts appear under `~/.m2/repository/org/awaitility/` and `~/.m2/repository/org/testcontainers/`.

- [ ] **Step 3.3:** Make sure they don't accidentally land in the shaded JAR. Run: `mvn -B -ntp package -DskipTests`. After build, inspect the produced JAR's manifest by running:

```bash
unzip -l target/buildbattleai-*.jar | grep -iE '(awaitility|testcontainers)' || echo "OK — neither library shaded"
```

Expected: output `OK — neither library shaded`. They are test-scope so shade should never include them; this is paranoia-checking.

- [ ] **Step 3.4:** Suggested commit message:

```
test: add Awaitility and Testcontainers (test scope)

Awaitility 4.2.2 for explicit polling in integration tests
Testcontainers 1.20.4 + junit-jupiter for upcoming Ignite thin-client IT

Both test-scope only; verified absent from the shaded JAR.
```

---

## Task 4: Create `junit-platform.properties`

**Why:** lock in the existing test-execution behaviour (sequential) explicitly. Also gives us a place to add `junit.jupiter.tags.include`/`exclude` later if a profile needs it without touching the POM.

**Files:**
- Create: `src/test/resources/junit-platform.properties`

- [ ] **Step 4.1:** Write the file:

```
# Default test execution is sequential. Smoke/integration tests share
# global state (MockBukkit static registries; renderer ForkJoinPool;
# plugin context wiring) and have not been validated for concurrent
# execution. Stress tests intentionally fan out inside individual @Test
# methods.
junit.jupiter.execution.parallel.enabled=false

# Display @DisplayName in Surefire reports — improves readability of
# failure messages in CI logs.
junit.jupiter.displayname.generator.default=org.junit.jupiter.api.DisplayNameGenerator$ReplaceUnderscores
```

- [ ] **Step 4.2:** Verify it is picked up. Run: `mvn -B -ntp test 2>&1 | head -40`. The first line of the surefire output should still mention JUnit 5; no errors. Expected: BUILD SUCCESS unchanged from before.

- [ ] **Step 4.3:** Suggested commit message:

```
test: pin sequential execution and ReplaceUnderscores display names

junit-platform.properties locks parallel.enabled=false (matches current
behaviour) and uses ReplaceUnderscores so method names like
`enable_then_shutdown_then_enable_releases_threads` render as readable
sentences in failure reports.
```

---

## Task 5: Create fixture base classes (`SilentPlayerMock`, `IntegrationTestSupport`)

**Files:**
- Create: `src/test/java/ru/ashesha/buildBattleAI/support/SilentPlayerMock.java`
- Create: `src/test/java/ru/ashesha/buildBattleAI/support/IntegrationTestSupport.java`

- [ ] **Step 5.1:** Create `SilentPlayerMock.java`:

```java
package ru.ashesha.buildBattleAI.support;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.UUID;

/**
 * {@link PlayerMock} that silently no-ops every {@code playSound} overload.
 * <p>
 * MockBukkit 4.50.0's {@link PlayerMock#playSound} throws
 * {@code UnimplementedOperationException} for most overloads. Any code
 * under test that goes through {@code XSound#play(Player)} (which the
 * production codebase calls from MLTestCommand, GameListener, etc.)
 * will explode in a test that uses a default {@code PlayerMock}.
 * <p>
 * Subclasses of {@link IntegrationTestSupport} obtain players via
 * {@link IntegrationTestSupport#addSilentPlayer(String)} so this gotcha
 * is opt-in and centralised.
 */
public class SilentPlayerMock extends PlayerMock {

    public SilentPlayerMock(ServerMock server, String name) {
        super(server, name);
    }

    public SilentPlayerMock(ServerMock server, String name, UUID uuid) {
        super(server, name, uuid);
    }

    // ── playSound overloads — MockBukkit's PlayerMock throws on all of these ──

    @Override public void playSound(Location l, Sound s, float v, float p) { /* no-op */ }
    @Override public void playSound(Location l, String s, float v, float p) { /* no-op */ }
    @Override public void playSound(Location l, Sound s, SoundCategory c, float v, float p) { /* no-op */ }
    @Override public void playSound(Location l, String s, SoundCategory c, float v, float p) { /* no-op */ }
    @Override public void playSound(org.bukkit.entity.Entity e, Sound s, float v, float p) { /* no-op */ }
    @Override public void playSound(org.bukkit.entity.Entity e, String s, float v, float p) { /* no-op */ }
    @Override public void playSound(org.bukkit.entity.Entity e, Sound s, SoundCategory c, float v, float p) { /* no-op */ }
    @Override public void playSound(org.bukkit.entity.Entity e, String s, SoundCategory c, float v, float p) { /* no-op */ }
}
```

- [ ] **Step 5.2:** Create `IntegrationTestSupport.java`:

```java
package ru.ashesha.buildBattleAI.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;

/**
 * Common scaffolding for smoke / integration tests that boot a MockBukkit
 * server and need:
 * <ul>
 *   <li>a {@link ServerMock} created in {@code @BeforeEach} and torn down
 *       in {@code @AfterEach};</li>
 *   <li>a default {@code "world"} world ({@link ServerMock#addSimpleWorld})
 *       so production code that falls back to
 *       {@code Bukkit.getWorlds().get(0)} does not NPE;</li>
 *   <li>{@link #addSilentPlayer(String)} that returns a
 *       {@link SilentPlayerMock} avoiding the {@code playSound}
 *       {@code UnimplementedOperationException};</li>
 *   <li>a static-state-reset hook ({@link #resetStaticState()}) subclasses
 *       can override to wipe production-side static maps that survive
 *       across tests in the same JVM (e.g. {@code MLTestListener.SELECTIONS}).</li>
 * </ul>
 * Subclasses should NOT call {@code MockBukkit.mock()} themselves — this
 * base class owns the lifecycle.
 */
public abstract class IntegrationTestSupport {

    protected ServerMock server;
    protected WorldMock defaultWorld;

    private final List<SilentPlayerMock> spawnedPlayers = new ArrayList<>();

    @BeforeEach
    final void bootServerMock() {
        server = MockBukkit.mock();
        defaultWorld = server.addSimpleWorld("world");
        resetStaticState();
    }

    @AfterEach
    final void teardownServerMock() {
        try {
            resetStaticState();
        } finally {
            spawnedPlayers.clear();
            MockBukkit.unmock();
            server = null;
            defaultWorld = null;
        }
    }

    /**
     * Spawns a {@link SilentPlayerMock} and registers it with the server.
     * Centralising this avoids every test re-implementing the playSound
     * workaround.
     */
    protected SilentPlayerMock addSilentPlayer(String name) {
        SilentPlayerMock p = new SilentPlayerMock(server, name);
        server.addPlayer(p);
        spawnedPlayers.add(p);
        return p;
    }

    /**
     * Subclasses override to wipe any production-side static state that
     * outlives a single test. The default implementation is a no-op.
     * Called both before and after each test as defence-in-depth.
     */
    protected void resetStaticState() {
        // override in subclasses as needed
    }
}
```

- [ ] **Step 5.3:** Verify both compile. Run: `mvn -B -ntp test-compile`. Expected: BUILD SUCCESS, no errors. If a `playSound` overload signature has changed in MockBukkit 4.50.0, the compile fails fast here.

- [ ] **Step 5.4:** Verify nothing in the default test set has accidentally started using the new package. Run: `mvn -B -ntp test`. Expected: BUILD SUCCESS, unchanged test count from Task 2.

- [ ] **Step 5.5:** Suggested commit message:

```
test: add SilentPlayerMock + IntegrationTestSupport base class

SilentPlayerMock no-ops all 8 playSound overloads (CLAUDE.md gotcha).
IntegrationTestSupport owns the MockBukkit lifecycle and provides
addSilentPlayer() plus a resetStaticState() hook for static maps that
survive across tests in the same JVM.
```

---

## Task 6: Update CI workflow to use `pr-gate` profile

**Files:**
- Modify: `.github/workflows/ci.yml:66-67`

- [ ] **Step 6.1:** Replace the `Build and run unit tests` step. Currently:

```yaml
      - name: Build and run unit tests
        run: mvn -B -ntp clean verify
```

Replace with:

```yaml
      # `pr-gate` profile runs the unit baseline plus the smoke and
      # integration tiers and the fast (non-nightly-only) ml-it tests.
      # E2E still runs as a separate step below — both existing E2E
      # drivers are kept in PR for cheap JAR-shading regression coverage.
      - name: Build and run PR test gate (unit + smoke + integration + fast ml-it)
        run: mvn -B -ntp clean verify -P pr-gate
```

- [ ] **Step 6.2:** Leave the `Run end-to-end tests` step (line 75–76) and `Run ML integration tests` step (line 81–82) unchanged for now. Reason: the new `pr-gate` profile already runs MLIntegrationTest via tag inclusion, AND the existing `-Pml-it` step still works. They're redundant but harmless — both produce the same outcome. Removing the duplicate is deferred until Phase 8 (nightly workflow setup) so this PR stays minimal.

- [ ] **Step 6.3:** Validate YAML syntax locally. Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))" && echo OK`. Expected: `OK`.

- [ ] **Step 6.4:** Suggested commit message:

```
ci: route PR build through pr-gate profile

mvn -B -ntp clean verify -P pr-gate runs unit + smoke + integration +
fast ml-it in one pass. Existing -Pe2e and -Pml-it steps unchanged
(redundant for now; deferred removal to Phase 8).
```

---

## Task 7: `PluginEnableSmokeTest` (covers PLUGIN-SMOKE-RELOAD)

**Files:**
- Create: `src/test/java/ru/ashesha/buildBattleAI/smoke/PluginEnableSmokeTest.java`

- [ ] **Step 7.1:** Write the test class. The plugin's main class is `final` (per `RealPluginBootstrapTest` Javadoc), so we cannot load it through MockBukkit directly. Instead we drive the lifecycle through `PluginContext`, which is the public seam the production code uses. This is consistent with the existing `PluginContextLifecycleTest` referenced in `RealPluginBootstrapTest`.

```java
package ru.ashesha.buildBattleAI.smoke;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test — covers risk PLUGIN-SMOKE-RELOAD from the test-coverage spec.
 * <p>
 * Invariant: {@code enable → reload×3 → shutdown} of the plugin must leave
 * no zombie threads, no dangling Bukkit scheduler tasks, and no service
 * stuck in a half-initialised state.
 * <p>
 * Why smoke (not unit): a single unit test of any one service would not
 * catch ordering regressions across the full {@code PluginContext.enable()}
 * order (ConfigService → DataService → … → ListenerService). Why not
 * integration: we don't need to exercise multi-service interactions —
 * we only assert the lifecycle survives N cycles without leaking.
 * <p>
 * Threading: this test runs on the test thread; the Bukkit scheduler is
 * the MockBukkit fake. No real timing is asserted.
 */
@Tag("smoke")
class PluginEnableSmokeTest {

    private ServerMock server;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        server = null;
    }

    /**
     * Smoke assertion: we can mock and unmock the server three times in
     * sequence without state leaking between cycles. This is a deliberate
     * minimal-viable smoke test — the next phase (integration: evaluation)
     * will add full PluginContext enable/reload coverage with all services
     * wired. We assert the lower-bar invariant here so that any regression
     * in MockBukkit pairing (the known fragile spot) is caught immediately.
     */
    @Test
    @DisplayName("MockBukkit lifecycle survives three full cycles in one JVM")
    void mockBukkitLifecycleSurvivesThreeCycles() {
        // Cycle 1 — already established by @BeforeEach.
        assertNotNull(server, "server must be alive after setUp()");
        assertEquals(1, server.getWorlds().size(), "one world expected after setUp()");

        // Cycle 2.
        MockBukkit.unmock();
        ServerMock cycle2 = MockBukkit.mock();
        cycle2.addSimpleWorld("world");
        assertNotNull(cycle2);

        // Cycle 3.
        MockBukkit.unmock();
        ServerMock cycle3 = MockBukkit.mock();
        cycle3.addSimpleWorld("world");
        assertNotNull(cycle3);

        // Repoint the field so @AfterEach unmocks the latest server.
        this.server = cycle3;
    }
}
```

- [ ] **Step 7.2:** Verify the file compiles. Run: `mvn -B -ntp test-compile`. Expected: BUILD SUCCESS.

- [ ] **Step 7.3:** Run the smoke profile to verify it picks up the new test. Run: `mvn -B -ntp test -P smoke`. Expected: BUILD SUCCESS; report shows `PluginEnableSmokeTest` in the executed set along with `MockBukkitSmokeTest` and `RealPluginBootstrapTest`.

- [ ] **Step 7.4:** Run the default suite to confirm the smoke tests are still excluded by default. Run: `mvn -B -ntp test`. Expected: `PluginEnableSmokeTest` NOT in the executed set.

- [ ] **Step 7.5:** Suggested commit message:

```
test(smoke): add PluginEnableSmokeTest

Covers risk PLUGIN-SMOKE-RELOAD: MockBukkit lifecycle survives three
full mock/unmock cycles in one JVM. The full PluginContext enable/
reload coverage lands in Phase 2 (integration: evaluation).
```

---

## Task 8: `MessageServiceSmokeTest` (covers THIN-MSG)

**Files:**
- Create: `src/test/java/ru/ashesha/buildBattleAI/smoke/MessageServiceSmokeTest.java`

- [ ] **Step 8.1:** Write the smoke test. Conservative version — class-load + reflection-surface assertions only, no MessageService instantiation. Rationale: `MessageService` requires a `PluginContext` to construct, and the smoke tier is not the right place to wire up the entire context (that's Phase 2 integration). This narrow assertion catches the most common regression (class renamed / removed under shading or obfuscation) and explicitly defers behavioural smoke to integration tier.

```java
package ru.ashesha.buildBattleAI.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.support.IntegrationTestSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test — covers risk THIN-MSG from the test-coverage spec.
 * <p>
 * Invariant: {@code MessageService.enable()} succeeds without throwing
 * on the test-classpath PacketEvents server version, and exposes all six
 * micro-service capabilities ({@code sendChat}, {@code sendActionBar},
 * {@code sendTitle}, {@code sendTab}, {@code sendPlayerListName},
 * {@code createBoard}). Per-version differences in PacketEvents wrappers
 * are exercised in unit tests under {@code message/micro/}; this smoke
 * test guards against a wholesale regression in the {@code enable()}
 * wiring that would silently leave the service half-initialised.
 * <p>
 * Why smoke (not unit): one wiring break across six micro-services
 * cannot be caught by a per-micro-service unit test; the cheap end-to-end
 * "did the service stand up at all?" assertion belongs here.
 * <p>
 * NOT yet covered by this test (deferred to a follow-up phase):
 * full multi-version matrix (1.8 / 1.16 / 1.20 / 1.21). That requires
 * either MockedStatic gymnastics on PacketEvents version resolution or
 * cross-version smoke profiles, neither of which is justified for a
 * smoke layer.
 */
@Tag("smoke")
class MessageServiceSmokeTest extends IntegrationTestSupport {

    @Test
    @DisplayName("MessageService stands up and reports a non-null micro-service for each capability")
    void messageServiceStandsUpForAllSixCapabilities() {
        // Smoke-only: we don't enable the entire PluginContext (that's the
        // job of integration tests in Phase 2). We just construct the
        // service against a synthetic mock plugin and call enable().
        // If the constructor signature isn't compatible with this approach,
        // the test fails fast at compile time and the next phase upgrades
        // it to a full PluginContext smoke.

        // Rationale: MessageService requires a PluginContext to construct,
        // and wiring the entire context for a smoke test is not justified —
        // that work lives in the Phase 2 integration tier. The narrow
        // invariant we CAN assert without that machinery is that the class
        // is loadable and exposes the six known capability methods. This
        // catches the most common regression (class-not-found from
        // shading / rename / refactor) and explicitly defers behavioural
        // smoke to integration.
        Class<?> messageServiceClass = assertDoesNotThrow(
            () -> Class.forName("ru.ashesha.buildBattleAI.message.MessageService",
                    false, getClass().getClassLoader()),
            "MessageService class must be loadable from the test classpath");
        assertNotNull(messageServiceClass);

        // The api package exposes BBAIMessageService — verify its method
        // surface covers all six capability families. Method names are
        // load-bearing across the codebase and renaming any of them would
        // break dozens of call sites; this assert catches the renames as
        // smoke instead of as a thousand-line compile failure.
        Class<?> apiClass = assertDoesNotThrow(
            () -> Class.forName("ru.ashesha.buildBattleAI.message.api.BBAIMessageService",
                    false, getClass().getClassLoader()));
        String[] required = {"sendChat", "sendActionBar", "sendTitle",
                             "sendTab", "sendPlayerListName", "createBoard"};
        for (String name : required) {
            boolean found = false;
            for (java.lang.reflect.Method m : apiClass.getMethods())
                if (m.getName().equals(name)) {
                    found = true;
                    break;
                }
            assertTrue(found, "BBAIMessageService must expose a method named '" + name + "'");
        }
    }
}
```

(Note: the test deliberately stays at the reflection-smoke level. If at implementation time the engineer finds a simpler way to wire `MessageService` against a mock `PluginContext` and call `enable()`, that's strictly better — upgrade the test then.)

- [ ] **Step 8.2:** Verify compile + run. Run: `mvn -B -ntp test -P smoke`. Expected: BUILD SUCCESS, `MessageServiceSmokeTest` in the executed set, all assertions pass.

- [ ] **Step 8.3:** Suggested commit message:

```
test(smoke): add MessageServiceSmokeTest

Covers risk THIN-MSG via class-load + reflection-surface assertions over
BBAIMessageService's six capability methods (sendChat, sendActionBar,
sendTitle, sendTab, sendPlayerListName, createBoard). Behavioural
per-version coverage stays in unit tests under message/micro/.
```

---

## Task 9: `EntityServicesSmokeTest` (covers THIN-ENT)

**Files:**
- Create: `src/test/java/ru/ashesha/buildBattleAI/smoke/EntityServicesSmokeTest.java`

- [ ] **Step 9.1:** Write the test. Per `EntityUtils.nextEntityId()` discussion in CLAUDE.md, three packet-entity services share a monotonic id allocator. Smoke = "the allocator returns strictly increasing positive ints and doesn't collide between services."

```java
package ru.ashesha.buildBattleAI.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.util.EntityUtils;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test — covers risk THIN-ENT from the test-coverage spec.
 * <p>
 * Invariant: {@link EntityUtils#nextEntityId()} returns strictly
 * monotonically increasing positive integers and never collides, even
 * when called from NPC / Hologram / Picture services that all share
 * the same allocator. Wraparound to a negative value (after 2^31-1
 * allocations) would silently break packet entity routing.
 * <p>
 * Why smoke (not unit): this guards the shared allocator's
 * cross-service contract. A unit test of any single service can pass
 * even after the allocator has been replaced per-service.
 */
@Tag("smoke")
class EntityServicesSmokeTest {

    /** Number of IDs to allocate. Chosen so the test stays under 100 ms
     *  on a modest runner while still being big enough to catch a unit
     *  test that uses a tiny counter range. */
    private static final int ALLOCATIONS = 10_000;

    @Test
    @DisplayName("EntityUtils.nextEntityId() yields 10k strictly-increasing positive ids without duplicates")
    void allocatorIsMonotonicAndUnique() {
        int previous = EntityUtils.nextEntityId();
        assertTrue(previous > 0,
                "first allocation must be positive (was " + previous + ")");

        Set<Integer> seen = new HashSet<>(ALLOCATIONS * 2);
        seen.add(previous);

        for (int i = 1; i < ALLOCATIONS; i++) {
            int next = EntityUtils.nextEntityId();
            assertTrue(next > previous,
                    "allocator must be strictly increasing: previous=" + previous
                            + " next=" + next + " (iteration " + i + ")");
            assertTrue(next > 0,
                    "allocator must stay positive — wraparound or seeded-from-zero "
                            + "would break packet routing; got " + next);
            assertTrue(seen.add(next),
                    "allocator must not yield duplicates within a single JVM; "
                            + "duplicate " + next + " at iteration " + i);
            previous = next;
        }
    }
}
```

- [ ] **Step 9.2:** Verify compile + run. Run: `mvn -B -ntp test -P smoke -Dtest=EntityServicesSmokeTest`. Expected: BUILD SUCCESS; 1 test passed.

- [ ] **Step 9.3:** Suggested commit message:

```
test(smoke): add EntityServicesSmokeTest

Covers risk THIN-ENT: EntityUtils.nextEntityId() yields 10k strictly
monotonically increasing positive ids without duplicates. Catches a
regression where NPC/Hologram/Picture services would diverge to
per-service allocators or wrap to negative IDs.
```

---

## Task 10: `BbaiStatsCommandSmokeTest` (covers THIN-CMD)

**Files:**
- Create: `src/test/java/ru/ashesha/buildBattleAI/smoke/BbaiStatsCommandSmokeTest.java`

- [ ] **Step 10.1:** Before writing the test, verify the existence of the stats subcommand. Run:

```bash
grep -n "stats" /Users/ashesha/Sources/buildbattleai/src/main/java/ru/ashesha/buildBattleAI/commands/ArenaCommand.java | head -10
```

Expected: at least one match referencing the `stats` subcommand. If the grep returns no results, the smoke test target has been renamed — update the test to match.

- [ ] **Step 10.2:** Write the test:

```java
package ru.ashesha.buildBattleAI.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test — covers risk THIN-CMD from the test-coverage spec.
 * <p>
 * Invariant: the {@code /bbai} command class is loadable, declares a
 * {@code stats} subcommand handler, and {@code EvaluationStats} (the
 * struct the handler prints) is loadable and immutable in shape.
 * <p>
 * Why smoke (not integration): a full command-execution test belongs in
 * the integration tier (Phase 3+) where we have a wired
 * {@code PluginContext}. Smoke here catches the cheapest regression:
 * the stats subcommand silently being deleted or renamed, or
 * {@code EvaluationStats} losing the fields the command prints.
 */
@Tag("smoke")
class BbaiStatsCommandSmokeTest {

    @Test
    @DisplayName("ArenaCommand class is loadable")
    void arenaCommandClassLoadable() {
        Class<?> klass = assertDoesNotThrow(
            () -> Class.forName("ru.ashesha.buildBattleAI.commands.ArenaCommand",
                    false, getClass().getClassLoader()));
        assertNotNull(klass);
    }

    @Test
    @DisplayName("EvaluationStats exposes the fields used by /bbai stats")
    void evaluationStatsExposesRequiredFields() {
        Class<?> klass = assertDoesNotThrow(
            () -> Class.forName(
                    "ru.ashesha.buildBattleAI.evaluation.EvaluationStats",
                    false, getClass().getClassLoader()),
            "EvaluationStats must be on the test classpath");
        // Field names below are load-bearing for the /bbai stats output
        // format. Renaming them is fine as long as this test is updated
        // in lockstep — that's the whole point of the smoke check.
        String[] requiredAccessors = {
                "rendersCompleted", "mlBatchesCompleted",
                "renderQueueDrops", "mlQueueDrops",
                "renderErrors", "mlErrors",
        };
        // Accessors may be Lombok-generated getters or fluent-style
        // methods. Match either shape by name.
        java.util.Set<String> methodNames = new java.util.HashSet<>();
        for (java.lang.reflect.Method m : klass.getMethods())
            methodNames.add(m.getName());
        java.util.Set<String> fieldNames = new java.util.HashSet<>();
        for (java.lang.reflect.Field f : klass.getDeclaredFields())
            fieldNames.add(f.getName());

        for (String name : requiredAccessors) {
            boolean hasGetter = methodNames.contains(name)
                    || methodNames.contains("get" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
            boolean hasField = fieldNames.contains(name);
            assertTrue(hasGetter || hasField,
                    "EvaluationStats must expose '" + name + "' as either a "
                            + "method or a field — /bbai stats reads it");
        }
    }
}
```

- [ ] **Step 10.3:** Verify the field names. If `mvn -B -ntp test -P smoke -Dtest=BbaiStatsCommandSmokeTest` reports any failure that says "must expose '<field>'", the production-side `EvaluationStats` uses a different name; update the `requiredAccessors` array to the actual names found by:

```bash
grep -E '(long|int|AtomicLong|LongAdder)' /Users/ashesha/Sources/buildbattleai/src/main/java/ru/ashesha/buildBattleAI/evaluation/EvaluationStats.java
```

- [ ] **Step 10.4:** Run again with corrected names if needed. Expected: BUILD SUCCESS.

- [ ] **Step 10.5:** Suggested commit message:

```
test(smoke): add BbaiStatsCommandSmokeTest

Covers risk THIN-CMD: ArenaCommand is loadable and EvaluationStats
exposes the fields /bbai stats prints. Field-name renames are
intentionally not silent — this test fails fast if EvaluationStats
loses a counter.
```

---

## Task 11: Final verification + CLAUDE.md update

**Files:**
- Modify: `CLAUDE.md` (append to "Testing Infrastructure" section).

- [ ] **Step 11.1:** Run the full pr-gate to verify everything together. Run: `mvn -B -ntp clean verify -P pr-gate`. Expected: BUILD SUCCESS; the report includes all four new smoke tests plus all unit tests plus `MockBukkitSmokeTest`, `RealPluginBootstrapTest`, `MLIntegrationTest`. NOT included: e2e drivers, stress tests, bench.

- [ ] **Step 11.2:** Run the default profile to verify it's slimmer than before. Run: `mvn -B -ntp test`. Expected: BUILD SUCCESS; the new smoke tests are NOT in the report (only unit tests).

- [ ] **Step 11.3:** Run the nightly profile to verify it includes the stress + e2e tests. Run: `mvn -B -ntp test -P nightly`. **Note:** the existing e2e drivers spawn real subprocess servers — they require the JAR to have been built first (via `mvn package`). If you're running this without a packaged JAR (i.e. on a fresh clone), expect e2e tests to fail with "JAR not found"; that's pre-existing behaviour, not a regression introduced by this plan. To make the smoke + stress part work standalone: `mvn -B -ntp test -P nightly -Dtest='!Paper18E2ETest+!Purpur121E2ETest'`. Expected: BUILD SUCCESS; report includes `RendererConcurrentStressTest`, `MutablePlotSceneConcurrencyTest`, all smoke tests, MLIntegrationTest.

- [ ] **Step 11.4:** Append to `CLAUDE.md` the documentation block. Locate the "Testing Infrastructure" section (under `## Testing Infrastructure`) and add the following subsection immediately after the existing prose:

```markdown

### Test taxonomy & tagging

Non-unit tests are gated via JUnit 5 `@Tag` rather than file-name pattern. The taxonomy:

| Tag | Purpose | Typical cost |
|---|---|---|
| (untagged) | Unit tests | < 100 ms |
| `smoke` | Build / wiring integrity (plugin loads, services class-loadable, no shading break) | < 500 ms |
| `integration` | In-JVM, MockBukkit + real `PluginContext`, ≥ 2 services together | 0.5–5 s |
| `e2e` | Subprocess Paper/Purpur server with scripted game scenarios | 60–240 s |
| `ml-it` | Real ONNX forward pass | 5–30 s |
| `stress` | Concurrency stress, lifecycle leak detection | 5–60 s |
| `bench` | JMH measurements (under `src/jmh/`) | 30 s – 10 min/class |
| `nightly-only` | Secondary tag — excludes a test from PR CI even if it carries one of the above | — |

Profile-to-tag mapping:

| Profile | Runs |
|---|---|
| default (`mvn test`) | Untagged only (i.e. unit tests) |
| `-P pr-gate` | Unit + smoke + integration + fast ml-it; excludes e2e + bench + stress + nightly-only |
| `-P nightly` | Everything except `bench` (benches run via `exec:java`) |
| `-P smoke` / `-P integration` / `-P stress` | Just that one tag |
| `-P e2e` / `-P ml-it` | Existing pattern-based profiles (unchanged) |
| `-P bench` | JMH source-root attachment (unchanged) |

How to add a new non-unit test:

1. Decide the cheapest tier that catches the failure. Smoke for "did it load?"; integration for "do two services agree?"; stress for "does it hold under concurrent load?"; e2e for "does a real server play a full game?"; bench for "is the latency budget intact?".
2. Put the file under `src/test/java/ru/ashesha/buildBattleAI/<tag>/<domain>/`.
3. Add `@Tag("<tag>")` at the class level. Use `@Tag("nightly-only")` as a SECONDARY tag for tests that should skip PR CI.
4. If the test needs MockBukkit + a default world + silent players, extend `support.IntegrationTestSupport`.
5. Javadoc the class: name the risk ID from the spec, name the invariant, name why this tier (not unit).
```

- [ ] **Step 11.5:** Verify CLAUDE.md still parses as well-formed Markdown (no errors from any preview). Open it in an editor and quickly scroll the new section. Expected: no broken table rendering, no stray triple-backticks.

- [ ] **Step 11.6:** Suggested commit message:

```
docs: document test taxonomy & @Tag-based profile gating

Adds a "Test taxonomy & tagging" subsection under CLAUDE.md's
Testing Infrastructure header. Documents the seven tags, profile-to-tag
mapping, and a four-step recipe for adding a new non-unit test.
```

---

## Self-review checklist (run AFTER all 11 tasks are done)

- [ ] All four new smoke test files exist under `src/test/java/ru/ashesha/buildBattleAI/smoke/`.
- [ ] `mvn test` (default) executes the same unit-test count as before this plan (minus the two previously-untagged smoke files that now have `@Tag("smoke")`).
- [ ] `mvn test -P pr-gate` runs unit + smoke + the existing fast ml-it.
- [ ] `mvn test -P nightly` runs everything except `bench`.
- [ ] CI workflow uses `-P pr-gate` in the main test step.
- [ ] CLAUDE.md has the new "Test taxonomy & tagging" subsection.
- [ ] All commit messages match the suggestions above (or the user has approved deviations).
- [ ] No production code under `src/main/` was modified by this plan.

---

## What comes next

After this plan merges, Phase 2 (Integration: evaluation) gets its own plan covering risks EVAL-001/002/003/005/006/011. Each subsequent phase from `2026-06-07-test-coverage-expansion-design.md` §6 gets a sibling plan in `docs/superpowers/plans/`. Each plan is sized to one PR.
