# CLAUDE.md

Guidance for Claude Code when working in this repo.

## Project Overview

BuildBattleAI is a Spigot plugin for a Build Battle variant where an AI classifier scores builds instead of player voting. It bundles a CPU voxel renderer (224×224 RGB) and an in-process ONNX Runtime inference of a custom ConvNeXt-Tiny embedder.

**Game flow:** players join arena → countdown at min-players → each player gets a theme → builds inside their plot → centralized `EvaluationService` polls dirty plot mirrors across every active arena, renders on a worker pool, batches one ML call → if the theme is in top-K the player scores, the zone is cleared and a new theme is assigned → highest score at the buzzer wins. Players must build *recognizable to a visual classifier*, not just pretty.

## Build & Test

```bash
mvn clean package                        # shaded JAR + tests + copy to ./Servers/{1.21,1.8}/plugins/
mvn compile / mvn test                   # quick checks
mvn test -Dtest=BlockPaletteTest         # one class
mvn test -Dtest="BlockPaletteTest#stoneHasColor"  # one method
```

`target/` holds two artifacts: full (Ignite shaded) and `-lite` (no Ignite). Local server: `./Servers/1.21/start.command`.

**JAR signing:** `mvn clean package -Psign -Djarsigner.keystore=./keystore.jks -Djarsigner.alias=bbai -Djarsigner.storepass=… -Djarsigner.keypass=…` (keystores `*.jks`/`*.p12` are gitignored — never commit).

**Obfuscation:** `-Pobfuscate-light` / `-Pobfuscate` / `-Pobfuscate-heavy`. Combinable with `-Psign`. Pipeline order inside `package`: shade → ProGuard → jarsigner → antrun. Mapping: `target/proguard-mapping.txt`.

**Resource filtering (DO NOT BREAK):** `src/main/resources/models/**` is excluded from Maven filtering in `pom.xml` — filtering would corrupt the bundled `custom_convnext_embeddings.onnx` (~107 MiB) and `centroids.json`. New binary resources must follow the same exclude/re-include pattern.

**Shade filter — ONNX debug symbols (DO NOT REMOVE):** the shade plugin drops `**/*.pdb` and `**/*.dSYM/**`. ORT ships Windows PDB (~65 MB) + macOS dSYM (~16 MB × 2 archs) that the JNI loader never reads — ~95 MB dead weight per JAR. If you add a native-bearing dep, audit it for similar symbol files.

## Language & Compatibility

**Java 8 only.** No `var`, records, text blocks, `List.of()`/`Map.of()`, switch expressions, pattern matching.

**Spigot/Paper 1.8 → 1.21.x.** Use **XSeries** (`XMaterial`, `XBlock`, `XSound`, …) for cross-version. Use **PacketEvents** wrappers instead of NMS/Bukkit packet APIs. `MessageService` micro-services resolve `ServerVersion` once in their constructor — follow that pattern (no runtime version checks in hot paths). Use `plugin.getContext().getServerVersion()` (lazy + cached) for version access — safe once `PluginService.enable()` has run. Verify any new Bukkit API exists in 1.8; `PlayerSnapshot` is the canonical 1.8-vs-1.9+ off-hand pattern.

## Service & API Usage (MANDATORY)

**Priority: project services >>> Spigot API, PacketEvents >>> NMS, XSeries >>> Spigot enums.** Every capability listed is already implemented with multi-version support (1.8–1.21), thread safety, and packet-level correctness. Don't bypass.

| Need | Use | NEVER use |
|---|---|---|
| Chat | `MessageService` → `ChatMicroService.sendChat` | `player.sendMessage()`, `player.spigot().sendMessage()` |
| Action bar | `BarMicroService.sendActionBar` | NMS / raw Bukkit |
| Title/subtitle | `TitleMicroService.sendTitle` | `player.sendTitle()`, NMS |
| Tab header/footer | `TabMicroService.sendTab` | `player.setPlayerListHeaderFooter()` |
| Player-list name | `NameMicroService.sendPlayerListName` | `player.setPlayerListName()` |
| Sidebar scoreboard | `BoardMicroService.createBoard` | Bukkit `Scoreboard` |
| Fake NPCs | `NPCService` (`BBAINPCService`) | Citizens, raw spawn packets |
| Floating text | `HologramService` (`BBAIHologramService`) | HolographicDisplays, raw armor stands |
| Image display | `PictureService` (`BBAIPictureService`) | Raw map packets, `MapView` |
| Dynamic void worlds | `WorldService` (`BBAIWorldService`) | Raw `WorldCreator`, custom generators |
| Rendering builds | `RenderService` | Constructing `CpuRenderer` directly |
| ML classification | `MLService` (`BBAIMLService`) | Direct ONNX, HTTP, custom model loading |
| Periodic render+ML for active games | `EvaluationService` (`registerSession` / `unregisterSession`) | Per-session render/ML timers, custom async render+predict |
| Game lifecycle | `GameManager` (`BBAIGameManager`) | Hand-rolled session state |
| Player/arena persistence | `DataService` (`BBAIDataService`) | Direct file I/O, raw Ignite |
| Settings / translations | `ConfigService` (`BBAIConfigService`) | Raw `FileConfiguration`, `plugin.getConfig()` |
| Materials/sounds/particles | `XMaterial` / `XSound` / `XParticle` | `org.bukkit.Material` / `Sound` / `Particle` |
| Packets | PacketEvents wrappers | NMS / CraftBukkit |
| Color codes | `MessageUtils.toColorComponent()` / `translateColors()` | Manual `§` / `ChatColor` |
| Reflection | `ReflectionUtils` | Manual `setAccessible` |
| Logging | `plugin.getPluginLogger()` | `java.util.logging.Logger`, `System.out` |
| Server version | `plugin.getContext().getServerVersion()` | `Bukkit.getBukkitVersion()`, NMS parsing |
| Sending packets | `plugin.getContext().sendPacket(player, packet)` | Raw PacketEvents `User.sendPacket()` |
| Player skin profile | `plugin.getContext().getUserProfile(player)` | Raw PacketEvents player manager |

Access pattern:
```java
plugin.getContext().getMessageService().sendChat(player, "&aHello!");
plugin.getContext().getMlService().predictRgb(rgb, 224, 224, 2);
plugin.getContext().getGameManager().joinArena(player, "arena_1");
plugin.getContext().getEvaluationService().stats();   // any thread
```

## Architecture

**Entry point:** `BuildBattleAI` (extends `JavaPlugin`). `onLoad()` runs `JarIntegrityVerifier.verify()`; tampered signed JARs disable themselves in `onEnable()`. Unsigned dev builds always pass. `PluginContext` is created in `onLoad()` and enabled in `onEnable()`.

**Lifecycle coordinator:** `PluginContext` (`core/`) owns all services. Every service implements internal `PluginService` (`enable()`, `shutdown()`, default `reload()` = shutdown + enable). `PluginService` is intentionally NOT public API. `enable()` order: ConfigService → DataService → WorldService → ArenaManager → **MLService → RenderService → EvaluationService → GameManager** → MessageService → NPCService → HologramService → PictureService → CommandService → ListenerService, then registers commands/listeners. `shutdown()` walks in reverse. Invariant: after `shutdown` the plugin holds no runtime resources; after `enable` it runs as if from cold start. **ML + Render must come before EvaluationService; GameManager must come after EvaluationService** because `GameManager.startGame(…)` immediately calls `evaluationService.registerSession(…)`.

**Service-owned base abstractions:**
- `CommandService.PluginCommand` (`commands/`) — extends Bukkit `Command`, no `plugin.yml` entry needed. Existing: `ArenaCommand` (`/bbai create|list|delete|join|leave|lang|stats`), `FlatSubcommand` (per-subcommand delegators registered when `commands.style: flat`). `/bbai stats` dumps one `EvaluationStats` snapshot.
- `ListenerService.PluginListener` (`listeners/`) — unified base implementing Bukkit `Listener` + PacketEvents `PacketListener`. Existing: `ArenaSetupListener`, `GameListener`, `OffHandSwapListener` (registered only on `ServerVersion.V_1_9+`).
- Do **not** add `register()`/`unregister()` on subclasses — registration is the service's job. New registrations go in `PluginContext.enable()`.

**Services overview** (each domain has an `api/` subpackage with `BBAI*` interface — except `RenderService`, accessed directly):
- `ConfigService` (`config/`) — root config + `lang/<lang>.yml` (missing-key auto-fill from default lang) + `arena/<name>.yml`. UTF-8 everywhere. Enabled first.
- `DataService` (`data/`) — persistent player/arena stats. Backends via `data.provider`: `local` (JSON + atomic rename) or `ignite` (Apache Ignite 2.16.0; modes `server`/`thick-client`/`thin-client`). `data.enabled: false` → all methods no-op/defaults. Models: `PlayerData`, `ArenaStats`.
- `WorldService` (`world/`) — dynamic void world management. All Bukkit ops on main thread. Worlds NOT auto-unloaded on shutdown.
- `ArenaManager` (`arena/`) — YAML loading with strict validation (collects all missing required fields, logs each as ERROR, skips invalid arenas), non-linear `/bbai create <name>` wizard (panel with every setting clickable in any order), deletion with world cleanup. Arena worlds: `bbai_<arena_name>`. Data model in `arena/api/`: `Arena` + nested `Position`/`PlotData`/`PictureRegion`. `PlotData` = spawn + 2 build-zone corners + exactly 3 cameras + a `PictureRegion` (flat 1×1 or 2×2 in-world rectangle). Optional fields default automatically.
- `GameManager` (`game/`) — owns sessions keyed by arena name. State machine `ArenaState`: `WAITING → COUNTDOWN → PLAYING → ENDING → WAITING` (ENDING→WAITING after ~10 s results display). Timers it owns: countdown (1 Hz) and game tick (1 Hz, decrements per-player build time). **It no longer owns render/ML timers** — `startGame(…)` calls `evaluationService.registerSession(session, (playerId, themeIndex) -> handleScore(…))`; the callback is marshalled to main thread by `EvaluationService`, so `handleScore` bumps score, clears the zone via `clearZone(…)`, `mirror.clearAll()` under write-lock, advances theme. Unregisters the session on every end/forced-shutdown path. Themes come from `MLService.classNames()` (shuffled) or `FALLBACK_THEMES` if ML disabled. Block-listener fast paths (`markPlayerZoneDirty`, `applyMirrorPlace`, `applyMirrorBreak`) feed the per-plot `MutablePlotScene` and flip the dirty flag. `PlayerSnapshot` deep-clones full pre-game state including off-hand (gated on `V_1_9`). `BBAIGameManager.skipTheme(player)` lets a player burn the current theme. Forced shutdown cancels timers, unregisters all sessions, clears zones, restores all snapshots.
- `GameSession` (`game/`) — runtime container for one arena (state, players, themes, mirrors). No longer exposes render-tick state; camera rotation lives on `SessionHandle` in the evaluation package.
- `game/feedback/` — AI "thinking out loud" presentation layer (`FeedbackController`, `FeedbackConfig`, `ThemeFormatter`, `ThoughtBank`, `SkipThemeItem`). Owns per-session scoreboard, randomised chat thoughts, tab list lines, AI sound effects, the triumph title on a correct guess, and the slot-8 "skip theme" feather. Config snapshotted at session start (so `/bbai reload` mid-round can't flip switches). All toggleable via `game.feedback.*`.
- `EvaluationService` (`evaluation/`) — single owner of the render+ML pipeline for active games. On enable: immutable `EvalConfig` from `config.yml` (`evaluation.*`), `ConcurrentHashMap<arenaName, SessionHandle>`, `EvaluationMetrics`, bounded `RenderQueue`, bounded `MlQueue`, **N daemon render workers** (`bbai-eval-render-<i>`), one daemon **ML coalescer** (`bbai-eval-ml`), a Bukkit-scheduled `EvaluationCoordinator` (main-thread), and an optional async metrics logger. Per coordinator tick: main thread advances each session's 3-slot camera rotation and offers an `EvalJob` per *dirty* player whose last enqueue is ≥ `min-cadence-ms` ago (cadence stretches automatically under backpressure → drop counters). `RenderQueue` is FIFO + bounded + **deduped by player UUID** (newer job marks the queued one stale; workers skip stale on dequeue). `RenderWorker` holds `mirror.readLock()` for the whole `RenderService.render(…)`, allocates a **fresh** `byte[224*224*3]` per frame (never reuse — the buffer travels through the next queue as multi-consumer batch input), packages into `EvalFrame`, offers to `MlQueue`. `MlCoalescerWorker` (single-threaded by design for deterministic batch sizes) calls `MlQueue.drainBatch(maxBatchSize, maxWaitMs)`, runs one `MLService.predictBatchRgb(…)`, and for every frame whose top-K contains its expected theme (case-insensitive) dispatches the score callback on the Bukkit main thread. Lookup-miss (arena unregistered between enqueue and drain) is silently skipped. `EvaluationMetrics` uses `LongAdder` + atomic-pair latencies + `AtomicLongArray` batch histogram; `snapshot()` is immutable with defensive copy. `stats()` is callable any-thread; during shutdown it tolerates null fields (the `enabled` flag is the synchronisation flag — `BukkitTask.cancel()` has no happens-before guarantee on the async metrics logger). Public API (`BBAIEvaluationService`): `registerSession` / `unregisterSession` are **main-thread only**; `stats()` is any-thread. Config keys under `evaluation.*` with defaults: `min-cadence-ms=5000`, `coordinator-tick-period=5` ticks, `render-workers=1` (the renderer's `ForkJoinPool` already saturates cores), `render-queue-capacity=64`, `ml-batch-max-size=8`, `ml-batch-max-wait-ms=200`, `ml-queue-capacity=64`, `ml-top-k=2`, `metrics-log-period-seconds=60` (0 = off).
- `RenderService` (`render/`) — owns `CpuRenderer` + dedicated `ForkJoinPool`. `renderer` field is `volatile`; shutdown race produces explicit `IllegalStateException` (also converted from `RejectedExecutionException`). API: `capture(region)` (main-thread Bukkit snapshot), `render(scene, camX, camY, camZ, yaw, pitch)` (allocating; 224×224 row-major HWC RGB), `render(…, outBuf)` (non-allocating — never share `outBuf` across concurrent calls). No `api/` interface — accessed directly.
- `CpuRenderer` — CPU voxel ray caster (DDA traversal, semi-transparent front-to-back compositing, per-face directional shading, quadrant-sampled AO, sub-block AABB tests, emissive bypass). Each instance owns a daemon `ForkJoinPool` (`bbai-renderer-<i>`). Brightness multipliers are *raised* over vanilla (top=1.0, bottom=0.6, X=0.7, Z=0.85) — without textures, vanilla multipliers confuse the classifier. **Don't "fix" them back.**
- `MutablePlotScene` (`render/data/`) — per-plot mirror replacing main-thread `capture(region)` in the render tick. `short[]` of `XMaterial` ordinals + parallel `byte[] legacyBlockData` (1.8–1.12) **or** `String[] blockStates` (1.13+), selected via `legacy` constructor flag. **Threading: single writer (main thread) + multiple readers (async render).** Cell writes are lock-free (JLS §17.7 atomic single-store); render reads take `readLock()` for the whole frame; `clearAll()` takes the write-lock. State-string allocations gated on `BlockPalette.needsBlockState(material)`. Out-of-bounds writes silently no-op. Factory: `forPlot(Arena.PlotData, boolean legacy)`.
- Other render members: `SceneData` (thread-safe block-access interface; impls `ChunkScene` / `FlatScene` / `MutablePlotScene`), `BlockPalette`, `BlockShape`, `BlockRenderState`, `LegacyBlockStates`, `RendererUtils` (`WIDTH=224`, `HEIGHT=224`, `FOV=70.0`, `toBufferedImage`, `buildHeightMap`; hard cap `MAX_HEIGHTMAP_AREA = 512*512`).
- `MessageService` (`message/`) — delegates to 6 micro-services via Lombok `@Delegate`: chat, bar, title, tab, name, board. Each resolves `ServerVersion` once in its constructor (no runtime checks).
- Entity packet services (`entity/{npc,hologram,picture}/`) — all PacketEvents-based. NPCs are stateless (only `entityId` + skin profile; per-viewer position/equipment). Holograms = stacked invisible armor stands at 0.3 intervals. Pictures = `width × height` grid of invisible item frames with filled maps.
- `MLService` (`ml/`) — custom ConvNeXt-Tiny embedder via ONNX Runtime, **in-process, no HTTP**. Bundled `/models/custom_convnext_embeddings.onnx` (~107 MiB) + `/models/centroids.json` (15 themes, 128-dim L2-normalized). Backend probing on enable: **CoreML → CUDA → DirectML → ROCm → CPU**; first one that loads + warms up at `batch=1` and `batch=TTA_VIEWS=4` wins. Session: `allow_spinning=0` on both thread pools (critical — otherwise ORT pins a CPU core 100% even when idle, starving the Bukkit main thread), `intraOpNumThreads = min(4, max(2, cores/2))`, `interOpNumThreads = 1`, memory-pattern + arena allocator on. DirectML disables memory-pattern; CoreML opts into `CREATE_MLPROGRAM`. Embedding dim 128. API: `embed*` / `predict*` / `*Rgb` / `*Batch` / `*WithTTA` (4 augmentations: shared bilinear resize → 246×246, random crop / hflip / brightness ∈ [0.85, 1.15], ImageNet normalize; one super-batch `run()`, sum embeddings + L2-normalize). Disabled mode (model missing or no backend loads): zero embeddings, deterministic-but-meaningless rankings, `backend() == "DISABLED"`. ORT sessions are concurrency-safe for `run()`; **all methods are blocking — call from async only**.
- `PluginLogger` (`core/`) — wrapper around `java.util.logging.Logger`. Levels `DEBUG`/`INFO`/`WARN`/`ERROR`/`OFF`. Debug emitted at Java `INFO` with `[DEBUG]` prefix (Bukkit consoles drop below INFO). Action lambdas pre-resolved on level change; no per-call checks. Configured via `log-level`.

**Utilities (`util/`):** `EntityUtils` (shared monotonic `nextEntityId()` for all packet entity services), `ReflectionUtils`, `MessageUtils`, `RendererUtils`, `MapPalette` (1.8-compatible), `JarIntegrityVerifier`, `SoundPalette`.

**Threading rules:**
- Capture Bukkit world data only on the main thread; render and palette logic is async-safe.
- **Plot mirror writes** (`MutablePlotScene.setBlock`/`clearBlock`) **must run on the main thread.** Single-writer/multi-reader is the entire safety contract. Reads from any thread take `mirror.readLock()`. `clearAll()` takes the write-lock internally.
- `MLService.*` is blocking — never call from the main thread. In game flow this is fully owned by `EvaluationService` (its `bbai-eval-render-<i>` and `bbai-eval-ml` daemons), so business code never spins up its own render+predict tasks. Outside game flow, the canonical pattern is `runTaskAsynchronously(…)` wrapping render + predict, then `runTask(…)` back to main for any Bukkit-state mutation.
- `EvaluationService.registerSession` / `unregisterSession` are **main-thread only**; `stats()` is any-thread.
- `EvalFrame.rgb` buffers are **single-owner immutables once enqueued**: render workers MUST allocate a fresh buffer per frame (no `RenderService.render(…, outBuf)` reuse), because the coalescer reads the same buffer at an unknown later time, possibly as part of a multi-frame batch.

## Code Quality & Style

**Comments:** Javadoc every new public class/interface/method/field. Use inline comments to explain *why* (non-obvious logic, algorithmic choices, edge cases, threading) — not *what*. All comments in English.

**Test coverage:** Every new feature/utility/behavioral change ships with tests. Cover happy path + edge cases + error conditions + boundary values. Renderer/palette/ML/game code that runs without Bukkit → JUnit. Bukkit-dependent code → Mockito (see existing `commands/`, `listeners/`, `game/` tests). If a class truly cannot be unit-tested without a live server, document that.

**Brace-free single-statement bodies:** For `if`, `for`, `while`, etc., if the body is a single statement do **not** use `{}`, but **always** put the body on the next line:
```java
if (color == TRANSPARENT)
    return color;
```
Never collapse `if (x) return y;` onto one line. Never `if (x) { return y; }` for a single statement.

## Key Conventions

- `XMaterial` (XSeries) instead of `org.bukkit.Material` in renderer code. XSeries is shaded/relocated to `ru.ashesha.buildBattleAI.libs.xseries`.
- Lombok for boilerplate (`@RequiredArgsConstructor`, `@Getter`, `@UtilityClass`, `@Accessors(fluent = true)`). Avoid Lombok when constructors have real initialization logic.
- Package root: `ru.ashesha.buildBattleAI`. Each domain has its own `api/` (`BBAI*` interfaces). RenderService is the lone exception (no `api/`).
- `plugin.yml` metadata is Maven-filtered from `pom.xml` — edit values in `pom.xml`.
- `FlatScene` array layout is X-major: `data[(x-minX)*sizeY*sizeZ + (y-minY)*sizeZ + (z-minZ)]`.
- **Service constructors must not call `plugin.getContext()`.** Services are instantiated inside `PluginContext`'s constructor while `BuildBattleAI#context` is still `null`. Defer anything needing the context (including `getServerVersion()`, `getUserProfile()`, `sendPacket()`) to `enable()`. Version-dependent state lives in a non-final field set in `enable()`.
- **ML output ↔ renderer input:** `RenderService.render(…)` returns exactly the layout `MLService.predictRgb`/`embedRgb` expects (224×224 row-major RGB). Don't decode through `BufferedImage` on the hot path — use the raw-RGB methods.
- **`commands.style`** (`config.yml`): `subcommand` (everything under `/bbai`) or `flat` (each public action also registered as a top-level command via `FlatSubcommand`). Default: `subcommand`.

## Testing Infrastructure

**Stack:** JUnit Jupiter 5.10.x, Mockito 5.12, MockBukkit `mockbukkit-v1.21:4.50.0`, paper-api `1.21.5-R0.1-SNAPSHOT` (test scope only — main scope uses Spigot API `1.21.8-R0.1-SNAPSHOT`). Awaitility 4.2 and Testcontainers 1.20 in test scope.

**Version pinning (don't bump unilaterally):** MockBukkit ships a bundled registry-JSON snapshot tied to a specific paper-api version. `4.50.0` ↔ `paper-api 1.21.5` is the only stable pair. Bumping either requires bumping both in lockstep. **MockBukkit MUST appear before paper-api/spigot-api in `pom.xml`** so its paper-api wins on the test classpath.

**Choosing the right tool:**
- Plain **Mockito** for listener handlers with pure delegation — mock `BuildBattleAI`, stub the `getContext()` chain, call the `@EventHandler` directly.
- **MockBukkit** when the production path touches Bukkit registries (`Bukkit.getItemFactory()`, `Bukkit.getPlayer(uuid)`, `Bukkit.getWorld(name)`, or constructs an `ItemStack` whose `getItemMeta()` is read back). Paper 1.21.5's `ItemStack.getItemMeta()` is effectively final and cannot be Mockito-stubbed.
- **`MockedStatic`** for code resolving packet factories or static Bukkit/PacketEvents accessors during `enable()`.
- **`MockedConstruction` (chained)** for structural lifecycle tests of `PluginContext.enable/shutdown/reload`.

### Test taxonomy & tagging

Non-unit tests are gated via JUnit 5 `@Tag`, not file-name pattern:

| Tag | Purpose | Cost |
|---|---|---|
| (untagged) | Unit | < 100 ms |
| `smoke` | Build/wiring integrity (plugin loads, services class-loadable, shading intact) | < 500 ms |
| `integration` | In-JVM, MockBukkit + real `PluginContext`, ≥ 2 services together | 0.5–5 s |
| `e2e` | Subprocess Paper/Purpur server with scripted scenarios | 60–240 s |
| `ml-it` | Real ONNX forward pass | 5–30 s |
| `stress` | Concurrency stress, lifecycle leak detection | 5–60 s |
| `bench` | JMH (under `src/jmh/`) | 30 s – 10 min/class |
| `nightly-only` | Secondary tag — excludes from PR CI even if tagged otherwise | — |

| Profile | Runs |
|---|---|
| default (`mvn test`) | Untagged + smoke + integration |
| `-P pr-gate` | Unit + smoke + integration + fast ml-it; excludes e2e + bench + stress + nightly-only |
| `-P nightly` | Everything except `bench` (benches run via `exec:java`) |
| `-P smoke` / `integration` / `stress` | Just that tag (overrides parent excludedGroups) |
| `-P e2e` / `-P ml-it` | Re-includes the tag + file-pattern includes; `-P ml-it` also excludes `nightly-only` |
| `-P bench` | JMH source-root attachment |

Surefire `<includes>` (default) discovers: `*Test.java`, `*Tests.java`, `Test*.java`, `*IT.java`, `*Stress.java`. New stress tests MUST end in `*Stress.java` or `*StressTest.java`; new integration tests use `*IT.java`. New suffixes require extending `<includes>` in `pom.xml`.

**Adding a new non-unit test:** pick the cheapest tier that catches the failure (smoke = "did it load?", integration = "do two services agree?", stress = "does it hold under load?", e2e = "full game on a real server?", bench = "is latency intact?"). Put the file under `src/test/java/ru/ashesha/buildBattleAI/<tag>/<domain>/`; **for package-private production members (e.g. `RenderQueue`, `MlQueue`, `EvalJob`)** put it in the production package so it has access — the `@Tag` (not the directory) drives discovery. Add `@Tag("<tag>")` at class level; use `@Tag("nightly-only")` as a secondary tag to skip PR CI. For MockBukkit + default world + silent players extend `support.IntegrationTestSupport`. For E2E driver tests extend `e2e.AbstractServerE2ETest` and override `serverDirectory()` + `serverFlavor()`; reuse `launchServerWithPluginRefresh`, `stopServerGracefully`, `preSeedArenaYaml`, `buildMinimalArenaYaml`, `waitForMarker`, `sendCommand`, `output()`, `extractStatsCounter`. Javadoc the risk ID + invariant + threading assumptions + why that tier (not unit).

**JMH benches.** `src/jmh/java/` is the source root. `bench/` package for public-API benches (`RendererBenchmark`, `PaletteBenchmark`, `MlBatchingBenchmark`, `MlServiceWarmupBenchmark`); production package for benches needing package-private access (`evaluation/EvaluationPipelineBenchmark`, `evaluation/MlQueueBenchmark`). Build: `mvn test-compile -Pbench`. Run: `mvn -Pbench exec:java -Dexec.args="<ClassName> -wi 3 -i 5 -f 1 -rf json -rff target/jmh.json"`. Nightly compares against `.github/perf-baselines/jmh.json` via `tools/compare-jmh.sh` (thresholds: +25% mean for `avgt`, +35% for sample-mode p99). Refresh the baseline via the manual `update-perf-baseline.yml` workflow after deliberate perf changes.

## Testing Gotchas

- `XMaterial.AIR.ordinal()` is **not** 0 (ordinal 0 is `ACACIA_BOAT`). When seeding `FlatScene` arrays, always fill with `(short) XMaterial.AIR.ordinal()` — don't rely on default-zero.
- `FlatScene.BlockDataSnapshot` inherits `@Accessors(fluent = true)` — `snapshot.material()`, not `snapshot.getMaterial()`.
- `PlayerMock.playSound(...)` throws `UnimplementedOperationException` for most overloads. If code under test calls `XSound#play` or `SoundPalette.*.play(player)`, subclass `PlayerMock` and override **all eight** `playSound` overloads. Reference: `MLTestCommandTest.SilentPlayerMock`-style pattern in `support/`.
- **No worlds by default in MockBukkit.** `Bukkit.getWorlds().get(0)` throws on empty `ServerMock` — call `server.addSimpleWorld(name)` in `@BeforeEach` when the code falls back to it.
- Static listener state survives across tests in the same JVM. Wipe via reflection in `@BeforeEach`/`@AfterEach`; do not add production-only reset hooks.
- `MLServiceTest` exercises the **disabled-mode** path (no ONNX on the test classpath). Full inference is integration-only — `/bbaitest` style commands on the local 1.21 server or `-P ml-it` workflow.
- `GameListenerTest` mocks `BBAIGameManager` via the **concrete `GameManager` class** because the listener casts to call package-visible helpers (`getPlayerPlotIndex`, `markPlayerZoneDirty`, `applyMirrorPlace`, `applyMirrorBreak`).
- Evaluation pipeline tests live under `test/.../evaluation/`. Coordinator/workers/queues are deliberately Bukkit-free — interface collaborators + plain JUnit. Lifecycle/stats tests use MockBukkit + `MockedConstruction` for the chained service set.
- **IDE Lombok diagnostics are noise.** Eclipse JDT / VS Code's Java extension don't run the Lombok processor by default and flag `unknown method getPluginLogger()` etc. on test files. Trust `mvn test-compile`.
- **Stale Lombok build artifacts.** If `mvn test-compile` fails on existing tests after an unrelated edit, run `mvn clean test-compile` once — incremental compilation can leave stale generated accessors.
- **E2E driver zombies.** `Process.destroyForcibly()` on the bash wrapper does NOT reap the spawned Purpur JVM. On test failure: `pkill -9 -f purpur` and remove `Servers/1.21/world/session.lock` before the next run. The `bash start.command` indirection also blocks SIGTERM forwarding — `ForceShutdownDuringPlayE2ETest.sigtermLeavesNoCorruption` is `@Disabled` on this basis.
- **E2E arena YAML format.** Generate via `AbstractServerE2ETest.buildMinimalArenaYaml(name, maxPlayers)` — NOT a custom YAML list. Plots must be keyed (`plots.'1'.spawn`), not list-typed, because `ArenaManager.deserializeArena` uses path lookups.

## Obfuscation Awareness

ProGuard config in `proguard/`: `base.pro` (shared keep rules) + `light.pro`/`standard.pro`/`heavy.pro`. All levels are rename-only (`-dontshrink -dontoptimize`) because the shaded JAR's incomplete class hierarchy prevents safe shrinking. Stack trace mapping: `target/proguard-mapping.txt`.

Auto-kept: `**.api.**` interfaces, `implements Listener` / `extends Command`, `@EventHandler` methods, `Serializable` machinery. **Manually-kept (don't touch):** `ai.onnxruntime.**` (native), `libs.**`, `org.apache.ignite.**`, `javax.cache.**` (ServiceLoader + reflection on own class names).

When adding code accessed by reflection-string, add explicit `-keep` entries in `base.pro`.

## CI / CD

All automation lives under `.github/`. VPS-touching workflows share the `deploy-mc-1.8` concurrency group so two operations never collide.

| Workflow | Trigger | Purpose |
|---|---|---|
| `ci.yml` | PR + push main, manual | `mvn verify -Ppr-gate` → `surefire:test -Pe2e` → `surefire:test -Pml-it`. On push main also deploys the **lite** JAR to the VPS via an inline ControlMaster SSH block. ONNX model restored from the `ml-model-v1` Release tag, cached between runs. |
| `nightly.yml` | `cron: 0 3 * * *`, manual | Full `-P nightly` suite (unit + smoke + integration + stress + e2e + ml-it incl. `nightly-only`), then full JMH suite via `exec:java` with `-rf json -rff target/jmh.json`, then `tools/compare-jmh.sh` against `.github/perf-baselines/jmh.json`. On regression, files a `perf-regression`-labelled issue with `target/jmh-regression.md` body. Uploads raw `jmh-results-<run_id>` artifact (30-day retention). Concurrency group `nightly-suite`, `cancel-in-progress: false`. |
| `update-perf-baseline.yml` | Manual | Re-runs JMH and commits the fresh JSON to `.github/perf-baselines/jmh.json` (idempotent — skips on byte-identical). Required `reason` input embedded in the commit message. Use after deliberate perf changes. |
| `release.yml` | Manual | Builds **signed + obfuscated** full and lite JARs, publishes a GitHub Release with both JARs + both ProGuard mapping files (`proguard-mapping-<ver>.txt` + `proguard-mapping-lite-<ver>.txt`), then commits the snapshot bump. Inputs: `version` (optional — defaults to current pom minus `-SNAPSHOT`), `release_notes`, `obfuscation_level`. The release commit + tag point at the released pom; the snapshot-bump commit comes **after** the tag so the tag is reproducible. |
| `deploy-release.yml` | Manual | Pulls a previously-published Release's JAR (flavour: `lite` or `full`, tag: empty = latest) and swaps it onto the VPS. For rollbacks and out-of-band redeploys. |
| `server-{start,stop,restart}.yml` | Manual | Idempotent `systemctl` ops on `mc-1.8.service`. Start/restart truncate `logs/latest.log` and poll for `Done (…)` within 120 s. |
| `claude.yml`, `claude-code-review.yml` | PR comments/events | Anthropic Claude reviewer integrations — not part of the build pipeline. |

**Composite action `.github/actions/vps-ssh/`** — single source of truth for VPS SSH ControlMaster setup (keyscan retry under `MaxStartups`, ControlMaster backoff loop, `ssh_base` output containing `-i / -o IdentitiesOnly / -o ControlPath / …`). Consumed by `deploy-release.yml`, `server-start.yml`, `server-stop.yml`, `server-restart.yml`. **`ci.yml` is the lone holdout** — its `deploy` job still inlines the same logic for historical reasons; new workflows MUST consume the composite. Always pair it with a final `ssh -O exit` + `rm -f ~/.ssh/cd_ed25519` cleanup step (`if: always()`).

**Required repository secrets:**

| Secret | Used by | Notes |
|---|---|---|
| `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY` | every VPS workflow | private key text (ed25519 PEM). |
| `JAR_KEYSTORE_BASE64` | `release.yml` | base64 of `keystore.jks` (use `base64 -w0`). Decoded into `$RUNNER_TEMP`. |
| `JAR_KEYSTORE_PASS`, `JAR_KEY_PASS`, `JAR_KEY_ALIAS` | `release.yml` | match `-Psign` local values (alias is `bbai` by default). |
| `RELEASE_PAT` | `release.yml` checkout step | PAT belonging to a user in `main` branch-protection bypass set; scopes `contents:write` + `metadata:read`. Default `GITHUB_TOKEN` cannot push tag + bump commits past classic branch protection (`GH006`). `actions/checkout` persists this PAT into `.git/config`, so the later `git push` in the same job uses it. |

**Branch protection model:** `main` uses **classic** branch protection. `enforce_all_for_admins` is intentionally **unchecked** — that's how the `RELEASE_PAT` admin bypasses it. Do not switch to Rulesets without redoing the bypass configuration.

**Operational invariants:**
- Releases are **append-only** — the workflow refuses to overwrite an existing tag. To redo a release, bump the version first.
- `release.yml` collects artifacts via `mvn help:evaluate -Dexpression=project.build.finalName`, not via a `find` glob — ProGuard leaves a `*_proguard_base.jar` pre-obfuscation backup, and a naive glob would publish the unobfuscated backup.
- ONNX model (`custom_convnext_embeddings.onnx`, ~107 MiB) lives on the `ml-model-v1` Release tag, **not committed**. CI and release restore it via `gh release download` with `actions/cache` keyed on `MODEL_RELEASE_TAG`. Bump that env var in **all four** workflows in lockstep when the model is replaced.
- CD ships only the **lite** JAR to the VPS — the VPS uses the local JSON data backend, so shaded Ignite in the full JAR is dead weight.

## Dependencies (provided scope = server supplies them)

- **Spigot API 1.21.8** (provided)
- **PacketEvents 2.12.0** (shaded) → `ru.ashesha.buildBattleAI.libs.packetevents`
- **XSeries 13.6.0** (shaded) → `ru.ashesha.buildBattleAI.libs.xseries`
- **Adventure / Kyori 4.25.0** (shaded) → `ru.ashesha.buildBattleAI.libs.kyori`. Forced up from PacketEvents' transitive 4.21.0 — `ObjectContents` was added in 4.25.0; without override the plugin crashes on MC 1.21.6+ with `NoClassDefFoundError`.
- **Gson 2.11.0** (shaded) → `ru.ashesha.buildBattleAI.libs.gson`
- **ONNX Runtime 1.21.0** (compile, NOT relocated) — ships CPU on all platforms + CoreML on macOS + DirectML on Windows. `onnxruntime_gpu` would add ~600 MB and tie us to a CUDA toolkit version, so we use the plain artifact and let CUDA fail gracefully during probing.
- **Apache Ignite 2.16.0** (shaded, NOT relocated) — Ignite relies on ServiceLoader/JMX/reflection on own class names; zero Ignite classes load when `data.provider: local`.
- **Lombok 1.18.44** (provided / compile-time only).
- **JUnit Jupiter 5.10.x**, **Mockito 5.12** (test) — Mockito requires `-Dnet.bytebuddy.experimental=true` (in surefire config).
- **MockBukkit `mockbukkit-v1.21:4.50.0`**, **paper-api `1.21.5-R0.1-SNAPSHOT`** (test, listed BEFORE spigot-api) — version-pinned together (see above).
- **Awaitility 4.2.x**, **Testcontainers 1.20.x** (test) — polling helpers and future Ignite thin-client integration tests.
