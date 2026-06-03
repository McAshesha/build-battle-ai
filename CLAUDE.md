# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

BuildBattleAI is a Spigot plugin for a Minecraft Build Battle variant where builds are judged by an AI classifier instead of player voting. It includes a CPU-based voxel renderer that captures in-game builds as 224×224 RGB byte buffers and a native, in-process ONNX Runtime inference service that scores those buffers against a custom-trained ConvNeXt-Tiny embedder.

**Game concept:** Players join an arena → countdown starts when minimum players reached → each player receives a theme → builds in their plot zone → the centralized `EvaluationService` continuously scans dirty plot mirrors across **all** active arenas, renders them on a dedicated worker pool, and batches the resulting frames through one ML inference → if the theme is in the top-K predictions the player scores a point, the zone is cleared, and a new theme is assigned → when the game timer expires, the player with the most points wins. Players must build *recognizably for a visual classifier*, not just aesthetically.

## Build & Test Commands

```bash
mvn clean package                        # Build shaded JAR, run tests, copy to ~/Servers/1.21/plugins/
mvn compile                              # Quick compile check
mvn test                                 # Run all tests
mvn test -pl . -Dtest=BlockPaletteTest   # Run a single test class
mvn test -pl . -Dtest="BlockPaletteTest#stoneHasColor"  # Run a single test method
```

The build produces two artifacts in `target/`: the full JAR (with Apache Ignite shaded) and a `-lite` JAR (without Ignite/javax.cache). The full JAR is auto-copied to `~/Servers/1.21/plugins/` and `~/Servers/1.8/plugins/` via maven-antrun-plugin. Local test server: `~/Servers/1.21/start.command`.

**JAR signing:** `mvn clean package -Psign -Djarsigner.keystore=./keystore.jks -Djarsigner.alias=bbai -Djarsigner.storepass=<pass> -Djarsigner.keypass=<pass>` (generate keystore once via `keytool -genkeypair`).

**Obfuscation profiles:** `-Pobfuscate-light` (rename only), `-Pobfuscate` (rename + repackage), `-Pobfuscate-heavy` (max). Combinable with `-Psign`. Pipeline order within `package`: shade → ProGuard → jarsigner → antrun. Mapping for deobfuscating stack traces: `target/proguard-mapping.txt`. Keystores (`*.jks`, `*.p12`) are gitignored — never commit them.

**Resource filtering caveat (DO NOT BREAK):** `src/main/resources/models/**` is *explicitly excluded* from Maven resource filtering in `pom.xml` — filtering would silently corrupt the bundled `custom_convnext_embeddings.onnx` (~107 MiB) and `centroids.json`. If you add another binary resource, exclude it from the filtered `<resource>` block and re-include it under the unfiltered one.

## Language & Compatibility

**Java 8 only.** Do not use `var`, records, text blocks, `List.of()`, `Map.of()`, switch expressions, pattern matching, or any Java 9+ features.

**Multi-version: 1.8+.** The plugin must run on Spigot/Paper 1.8 through 1.21.x. Never use Bukkit/Spigot APIs that only exist in newer versions without a version check or abstraction layer. Key rules:
- Use **XSeries** (`XMaterial`, `XBlock`, `XSound`, etc.) for all cross-version abstractions.
- Use **PacketEvents** wrappers instead of raw NMS or Bukkit packet APIs.
- `MessageService` already handles version branching via PacketEvents `ServerVersion` checks — follow the same pattern (resolve once inside `enable()`, no runtime version checks in hot paths).
- For server-version access, use `plugin.getContext().getServerVersion()` (lazily resolved + cached) — safe once `PluginService.enable()` has run.
- When adding new Bukkit API calls, verify they exist in 1.8 Spigot. `PlayerSnapshot` is the canonical pattern for off-hand on 1.9+ vs. 1.8.

## Service & API Usage Rules (MANDATORY)

**Priority: project services >>> Spigot API, PacketEvents >>> NMS, XSeries >>> Spigot enums.** Every capability listed below is already implemented with full multi-version support (1.8–1.21), thread safety, and packet-level correctness. Never bypass these abstractions with raw Bukkit/NMS calls.

| Need | Use this | NEVER use |
|---|---|---|
| Chat messages to players | `MessageService` → `ChatMicroService` (`sendChat`) | `player.sendMessage()`, `player.spigot().sendMessage()` |
| Action bar | `MessageService` → `BarMicroService` (`sendActionBar`) | NMS action bar packets, raw Bukkit APIs |
| Title / subtitle | `MessageService` → `TitleMicroService` (`sendTitle`) | `player.sendTitle()`, NMS title packets |
| Tab header / footer | `MessageService` → `TabMicroService` (`sendTab`) | `player.setPlayerListHeaderFooter()` |
| Player list name | `MessageService` → `NameMicroService` (`sendPlayerListName`) | `player.setPlayerListName()` |
| Sidebar scoreboard | `MessageService` → `BoardMicroService` (`createBoard`) | Bukkit `Scoreboard` API |
| Fake NPCs | `NPCService` via `BBAINPCService` | Citizens, raw entity spawn packets |
| Floating text | `HologramService` via `BBAIHologramService` | HolographicDisplays, raw armor stand packets |
| Image display (maps) | `PictureService` via `BBAIPictureService` | Raw map packets, Bukkit `MapView` |
| Dynamic void worlds | `WorldService` via `BBAIWorldService` | Raw `WorldCreator`, manual chunk generators |
| Rendering builds | `RenderService` (never construct `CpuRenderer` directly) | — |
| ML classification | `MLService` via `BBAIMLService` | Direct ONNX Runtime, HTTP, in-process model loading |
| Periodic render+ML pipeline for game plots | `EvaluationService` via `BBAIEvaluationService` (`registerSession` / `unregisterSession`) | Per-session render/ML timers, manual `runTaskAsynchronously` of `render+predict` for game flow |
| Game session lifecycle | `GameManager` via `BBAIGameManager` | Hand-rolled session state, scattered scheduler tasks |
| Player/arena data persistence | `DataService` via `BBAIDataService` | Direct file I/O, raw Ignite API |
| Plugin settings / translations | `ConfigService` via `BBAIConfigService` | Raw `FileConfiguration`, `plugin.getConfig()` |
| Materials, sounds, particles | `XMaterial`, `XSound`, `XParticle` (XSeries) | `org.bukkit.Material`, `org.bukkit.Sound`, `org.bukkit.Particle` |
| Packets | PacketEvents wrappers | NMS / CraftBukkit internals |
| Color-coded text | `MessageUtils.toColorComponent()` / `translateColors()` | Manual `§` / `ChatColor` manipulation |
| Reflection | `ReflectionUtils` | Manual `Class.getDeclaredField` / `setAccessible` |
| Logging | `plugin.getPluginLogger()` (`PluginLogger`) | Raw `java.util.logging.Logger`, `System.out` |
| Server version | `plugin.getContext().getServerVersion()` | `Bukkit.getBukkitVersion()`, NMS version parsing |
| Sending packets | `plugin.getContext().sendPacket(player, packet)` | Raw PacketEvents `User.sendPacket()` |
| Player skin profile | `plugin.getContext().getUserProfile(player)` | Raw PacketEvents player manager |

Access services from game logic via `plugin.getContext()`:
```java
plugin.getContext().getMessageService().sendChat(player, "&aHello!");
plugin.getContext().getRenderService().render(scene, camX, camY, camZ, yaw, pitch);
plugin.getContext().getMlService().predictRgb(rgb, 224, 224, 2);
plugin.getContext().getGameManager().joinArena(player, "arena_1");
plugin.getContext().getEvaluationService().stats();   // EvaluationStats snapshot (any thread)
```

## Architecture

**Entry point:** `BuildBattleAI` (extends `JavaPlugin`). `onLoad()` runs `JarIntegrityVerifier.verify()`; on failure (tampered signed JAR) the plugin aborts in `onEnable()` via `disablePlugin(this)`. Unsigned JARs (dev builds) always pass. `PluginContext` is created in `onLoad()` and enabled in `onEnable()`.

**Lifecycle coordinator:** `PluginContext` (in `core/`) owns all services. Every service implements internal `PluginService` (`enable()`, `shutdown()`, default `reload()` = `shutdown` + `enable`). `PluginContext.enable()` runs services in this order: ConfigService → DataService → WorldService → ArenaManager → **MLService → RenderService → EvaluationService → GameManager** → MessageService → NPCService → HologramService → PictureService → CommandService → ListenerService, then registers commands and listeners. `shutdown()` walks the list in reverse. `PluginService` is intentionally NOT part of the public API. Invariant: after `shutdown` the plugin holds no runtime resources; after `enable` it runs as if from a fresh server start. **Important:** ML + Render must be enabled before `EvaluationService` (which consumes both); `GameManager` is enabled after `EvaluationService` because `GameManager.startGame(...)` calls `evaluationService.registerSession(...)` immediately.

**Service-owned base abstractions:**
- `CommandService.PluginCommand` (`commands/`) — extends Bukkit `Command`. No `plugin.yml` declaration needed; subclass + register via `getCommandService().register(...)`. Existing: `ArenaCommand` (`/bbai create|list|delete|join|leave|stats`), `MLTestCommand` (`/bbaitest`), `WorldTpCommand` (`/worldtp`). `/bbai stats` prints a one-shot `EvaluationStats` dump (queue depths, completed renders / ML batches, drops, errors, avg latencies, batch-size histogram).
- `ListenerService.PluginListener` (`listeners/`) — unified base implementing Bukkit `Listener` + PacketEvents `PacketListener`. Single listener handles `@EventHandler` + packet overrides. Existing: `ArenaSetupListener`, `GameListener`, `MLTestListener`.
- Do **not** add `register()`/`unregister()` on subclasses — registration is the service's responsibility. Commands and listeners only take `plugin` in their constructor. New registrations go in `PluginContext.enable()` (Phase 2).

**Services overview** (each domain package has an `api/` subpackage with the public interface — `BBAI*`):
- `ConfigService` (`config/`) — root config (`config.yml`), language directory (`lang/<lang>.yml` with missing-key auto-fill from default language), arena directory (`arena/<name>.yml`). All YAML I/O is explicit UTF-8. Enabled first; other services can safely read config during their `enable()`.
- `DataService` (`data/`) — persistent player/arena statistics. Two interchangeable backends via `data.provider` in `config.yml`: `local` (JSON + atomic rename) or `ignite` (Apache Ignite 2.16.0; modes `server`/`thick-client`/`thin-client`). Disabled when `data.enabled: false` (all methods return defaults/no-ops). Models: `PlayerData`, `ArenaStats`.
- `WorldService` (`world/`) — dynamic void world management. `createEmptyWorld(name)`, `loadWorld`, `unloadWorld`, `deleteWorld`. All Bukkit world ops on main thread. Worlds NOT auto-unloaded on shutdown.
- `ArenaManager` (`arena/`) — arena lifecycle: YAML loading with strict validation (collects all missing required fields, logs each as ERROR, skips invalid arenas), non-linear setup wizard (`/bbai create <name>` shows a panel with ALL settings clickable in any order), runtime state, deletion with world cleanup. Arena worlds: `bbai_<arena_name>`. Data model in `arena/api/`: `Arena` + nested `Position`/`PlotData`/`PictureRegion`. `PlotData` has spawn + 2 build-zone corners + exactly 3 cameras + a `PictureRegion` (flat 1×1 or 2×2 in-world rectangle). Optional fields (`spectator`, `min-players`, `build-time`, `game-time`, `countdown-time`) have defaults.
- `GameManager` (`game/`) — owns all active sessions keyed by arena name. State machine `ArenaState`: `WAITING → COUNTDOWN → PLAYING → ENDING → WAITING`. Timers it still owns: countdown (1 Hz) and game tick (1 Hz, also decrements per-player build time). **It no longer owns the render/ML timer** — at `startGame(...)` it calls `evaluationService.registerSession(session, (playerId, themeIndex) -> handleScore(arenaName, playerId, themeIndex))`; the score callback is marshalled to the main thread by `EvaluationService`, so `handleScore` runs there directly (bumps score, clears zone via `clearZone(...)`, `mirror.clearAll()` under write-lock, advances theme). Unregisters the session on every end / forced shutdown path. Themes come from `MLService.classNames()` (shuffled) or `FALLBACK_THEMES` if ML is disabled. Block-listener fast paths (`markPlayerZoneDirty`, `applyMirrorPlace`, `applyMirrorBreak`) feed the per-plot `MutablePlotScene` and flip the dirty flag for the coordinator. `PlayerSnapshot` deep-clones full pre-game state including off-hand (gated on `ServerVersion.V_1_9`). Forced shutdown cancels timers, unregisters all sessions, clears zones, restores all snapshots — world returns to pre-game state.
- `GameSession` (`game/`) — runtime container for a single arena (state, players, themes, mirrors). **No longer exposes any render-tick state**; the camera rotation index used to live here and is now on `SessionHandle` in the evaluation package.
- `EvaluationService` (`evaluation/`) — single owner of the periodic render+ML pipeline for active games, replacing per-arena timers. Lifecycle creates: an immutable `EvalConfig` from `config.yml` (`evaluation.*`), a session registry (`ConcurrentHashMap<arenaName, SessionHandle>`), an `EvaluationMetrics` counter set, a bounded `RenderQueue`, a bounded `MlQueue`, **N daemon render workers** (`bbai-eval-render-<i>`), one daemon **ML coalescer** thread (`bbai-eval-ml`), a Bukkit-scheduled `EvaluationCoordinator` (main-thread, runs every `coordinator-tick-period` Bukkit ticks), and an optional async metrics-log task. Pipeline per coordinator tick:
  1. Main thread iterates every registered `PLAYING` session, advances its camera index (3-slot rotation moved to `SessionHandle`), and for each *dirty* player whose last enqueue was ≥ `min-cadence-ms` ago builds an `EvalJob` (arena + player + plot + theme + `MutablePlotScene` reference + camera xyz/yaw/pitch) and offers it to `RenderQueue`. Cadence stretches automatically under backpressure (drop counters in `EvaluationMetrics`).
  2. `RenderQueue` is FIFO + bounded + **deduped by player UUID**: a newer job for the same player marks the queued one stale (`EvalJob.markStale()`) and replaces the dedup entry. Workers skip stale jobs on dequeue. Exactly one producer (coordinator) and N consumers.
  3. `RenderWorker` takes a job, holds `mirror.readLock()` for the whole `RenderService.render(...)` call, allocates a fresh `byte[224*224*3]` (must NOT be reused — it travels through the next queue as a multi-consumer batch input), records render latency, packages it into an `EvalFrame`, and offers it to `MlQueue`. Exceptions are swallowed → `metrics.incRenderErrors()`; the worker must survive a single bad job.
  4. `MlCoalescerWorker` (single-threaded by design — ONNX session is concurrency-safe, but serial batch assembly avoids a class of races and gives deterministic batch sizes) calls `MlQueue.drainBatch(maxBatchSize, maxWaitMs)`, packs the buffers into `byte[][]`, runs **one** `MLService.predictBatchRgb(...)`, and for every frame whose top-K ranking contains its expected theme (case-insensitive) looks up the per-arena score callback in the live registry and dispatches it on the Bukkit main thread via `Bukkit.getScheduler().runTask(...)` with `(playerId, themeIndex)`. Lookup-miss (arena unregistered between enqueue and drain) is silently skipped — ML batch still counted as completed.
  5. `EvaluationMetrics` uses `LongAdder` for hot counters, `AtomicLong` pairs (sum+count) for latencies, and an `AtomicLongArray` for the batch-size histogram (bucket index = actual batch size, 0..`ml-batch-max-size`). `snapshot(...)` materialises an `EvaluationStats` (immutable, defensive-copy on the histogram).
  6. `stats()` is callable from any thread; during shutdown it tolerates field-null and returns a zero snapshot (the `enabled` flag is the synchronisation flag — Bukkit's `BukkitTask.cancel()` has no happens-before guarantee on the async metrics logger).
  Public API (`BBAIEvaluationService`): `registerSession(session, BiConsumer<UUID, Integer>)` and `unregisterSession(arenaName)` — both **main-thread only**; `stats()` is any-thread. Config keys (all under `evaluation.*`, with defaults): `min-cadence-ms=5000`, `coordinator-tick-period=5` ticks, `render-workers=1` (the renderer's internal `ForkJoinPool` already saturates cores), `render-queue-capacity=64`, `ml-batch-max-size=8`, `ml-batch-max-wait-ms=200`, `ml-queue-capacity=64`, `ml-top-k=2`, `metrics-log-period-seconds=60` (0 = off).
- `RenderService` (`render/`) — owns `CpuRenderer` + dedicated `ForkJoinPool`. Lifecycle: create in `enable()`, destroy in `shutdown()`. Internal `renderer` field is `volatile`; shutdown race produces explicit `IllegalStateException` (also converted from `RejectedExecutionException`). API: `capture(region)` (main-thread snapshot of Bukkit chunks), `render(scene, camX, camY, camZ, yaw, pitch)` (allocating; returns 224×224 row-major HWC RGB ready for ML), `render(..., outBuf)` (non-allocating, reuse `byte[224*224*3]` in hot loops — must NOT share buffer across concurrent calls). Always go through this service; don't construct `CpuRenderer` or call `ChunkScene.capture` directly outside the render package.
- `CpuRenderer` — CPU voxel ray caster using DDA traversal, semi-transparent front-to-back compositing, per-face Minecraft-style directional shading, quadrant-sampled AO, sub-block AABB tests, emissive bypass. Each instance owns a daemon `ForkJoinPool` with named threads (`bbai-renderer-<i>`). Brightness multipliers are *raised* over vanilla (top=1.0, bottom=0.6, X=0.7, Z=0.85) — without textures, darker multipliers confuse the classifier. Don't "fix" them back to vanilla.
- `MutablePlotScene` (`render/data/`) — per-plot scene mirror replacing main-thread `capture(region)` in the game render tick. Backed by `short[]` of `XMaterial` ordinals + parallel `byte[] legacyBlockData` (1.8–1.12) **or** `String[] blockStates` (1.13+), selected via `legacy` constructor flag. **Threading: single writer (main Bukkit thread) + multiple readers (async render).** Cell writes are lock-free (JLS §17.7 atomic single-store for `short`/`byte`/reference); render reads take `readLock()` around the whole frame; `clearAll()` takes the write-lock. State-string allocations gated on `BlockPalette.needsBlockState(material)`. Out-of-bounds writes silently no-op. Factory: `forPlot(Arena.PlotData, boolean legacy)`.
- Other render package members: `SceneData` (thread-safe block-access interface; impls `ChunkScene`/`FlatScene`/`MutablePlotScene`), `BlockPalette`/`BlockShape`/`BlockRenderState`, `LegacyBlockStates`, `RendererUtils` (`@UtilityClass` with `WIDTH=224`, `HEIGHT=224`, `FOV=70.0`, `toBufferedImage`, `buildHeightMap`; hard cap `MAX_HEIGHTMAP_AREA = 512*512`).
- `MessageService` (`message/`) — delegates to 6 micro-services via Lombok `@Delegate`: `ChatMicroService`, `BarMicroService`, `TitleMicroService`, `TabMicroService`, `NameMicroService`, `BoardMicroService`. Each resolves its `ServerVersion` once in its constructor (no runtime version checks in hot paths). Micro-services instantiated in `MessageService.enable()`.
- Entity packet services (`entity/npc/`, `entity/hologram/`, `entity/picture/`) — all packet-based via PacketEvents. NPCs are **stateless** (only `entityId` + skin profile; per-viewer position/equipment). Holograms = stacked invisible armor stands at 0.3 intervals. Pictures = `width × height` grid of invisible item frames with filled maps.
- `MLService` (`ml/`) — runs custom-trained ConvNeXt-Tiny embedder locally via ONNX Runtime. **No external service, no HTTP.** Bundled: `/models/custom_convnext_embeddings.onnx` (~107 MiB), `/models/centroids.json` (15 themes, 128-dim L2-normalized). Backend probing on `enable()`: **CoreML → CUDA → DirectML → ROCm → CPU**; first one whose session loads + warms up at `batch=1` and `batch=TTA_VIEWS=4` wins. Session config: `allow_spinning=0` on both thread pools (critical — otherwise ORT pins a CPU core 100% even when idle, starving the Bukkit main thread), `intraOpNumThreads = min(4, max(2, cores/2))`, `interOpNumThreads = 1`, memory-pattern + arena allocator on. Per-backend overrides: DirectML disables memory-pattern, CoreML opts into `CREATE_MLPROGRAM`. Embedding dim 128. API: `embed(...)`/`embedBatch(...)`/`predict(...)`/`predictRgb(...)`/`predictBatch(...)` + `*WithTTA` (4 cheap augmentations: shared bilinear resize to 246×246, random crop / hflip / brightness jitter ∈ [0.85, 1.15], ImageNet normalize; one super-batch `run()`, sum embeddings + L2-normalize). Disabled mode (model missing or no backend loads): zero embeddings, deterministic-but-meaningless rankings, `backend() == "DISABLED"`. ORT sessions are thread-safe for concurrent `run()`; all methods are **blocking** — call from async only.
- `PluginLogger` (`core/`) — configurable wrapper around `java.util.logging.Logger`. Levels `DEBUG`/`INFO`/`WARN`/`ERROR`/`OFF`. Debug emitted at Java `INFO` level with `[DEBUG]` prefix (Bukkit consoles drop below INFO). Action lambdas pre-resolved on level change; no per-call checks. Configured via `log-level` in `config.yml`.

**Utilities (`util/`):** `EntityUtils` (shared monotonic entity-id allocator — `nextEntityId()` for all packet-based entity services), `ReflectionUtils`, `MessageUtils`, `RendererUtils`, `MapPalette` (Minecraft map palette, 1.8-compatible), `JarIntegrityVerifier`, `SoundPalette`.

**Threading rules:**
- Capture Bukkit world data only on the main thread; render and palette logic is async-safe.
- **Plot mirror writes** (`MutablePlotScene.setBlock`/`clearBlock`) **must run on the main thread.** Single-writer/multi-reader is the entire safety contract. Reads from any thread take `mirror.readLock()` for the duration. `clearAll()` takes the write-lock internally.
- `MLService.*` is blocking — never call from the main thread. **In game flow this is fully owned by `EvaluationService`** (its `bbai-eval-render-<i>` and `bbai-eval-ml` daemon threads), so business code never spins up its own render+predict async tasks. Outside game flow (e.g. `MLTestCommand`), the canonical pattern remains `runTaskAsynchronously(...)` wrapping render + predict, then `runTask(...)` back to main for any Bukkit-state mutation.
- `EvaluationService.registerSession` / `unregisterSession` must be called from the **main thread**; `stats()` is safe from any thread.
- `EvalFrame.rgb` buffers are **single-owner immutables once enqueued**: render workers MUST allocate a fresh buffer per frame (no buffer reuse via `RenderService.render(..., outBuf)`), because the ML coalescer reads the same buffer at an unknown later time, possibly as part of a multi-frame batch.

## Code Quality Requirements

**Comments:** All new code must include professional English comments. Add Javadoc to every new public class, interface, method, and field. Use inline comments to explain non-obvious logic, algorithmic choices, edge cases, and threading. Comments should explain *why*, not just *what*.

**Test coverage:** Every new feature, utility, or behavioral change must be accompanied by tests. Cover happy path, edge cases, error conditions, boundary values. For renderer/palette/ML/game code that runs without Bukkit, write JUnit tests. For Bukkit-dependent code, add Mockito-based tests where feasible (see existing `commands/`, `listeners/`, `game/` tests). If a class truly cannot be unit-tested without a live server, document that explicitly.

## Code Style

**Brace-free single-statement bodies:** For `if`, `for`, `while`, etc., if the body is a single statement, do **not** use `{}` — but **always** put the body on the next line.

```java
// Correct:
if (color == TRANSPARENT)
    return color;

for (Player recipient : recipients)
    sendChatPacket(recipient, component);

// Wrong — braces on single-statement body:
if (color == TRANSPARENT) {
    return color;
}

// Wrong — body on same line:
if (color == TRANSPARENT) return color;
```

## Key Conventions

- Use `XMaterial` (XSeries) instead of `org.bukkit.Material` in renderer code. XSeries is shaded/relocated to `ru.ashesha.buildBattleAI.libs.xseries`.
- Use Lombok for boilerplate (`@RequiredArgsConstructor`, `@Getter`, `@UtilityClass`, `@Accessors(fluent = true)`). Avoid Lombok when constructors have real initialization logic.
- Package root: `ru.ashesha.buildBattleAI`. Each domain package has its own `api/` subpackage for public interfaces.
- `plugin.yml` metadata is filled from `pom.xml` via Maven resource filtering — edit values in `pom.xml`, not in `plugin.yml`.
- `FlatScene` array layout is X-major: `data[(x-minX)*sizeY*sizeZ + (y-minY)*sizeZ + (z-minZ)]`.
- **Service constructors must not call `plugin.getContext()`.** Services are instantiated inside `PluginContext`'s constructor while `BuildBattleAI#context` is still `null`. Defer anything that needs the context (including `getServerVersion()`, `getUserProfile()`, `sendPacket()`) to `PluginService.enable()`. Services that need version-dependent state keep the field non-final and resolve it in `enable()`.
- **ML output ↔ renderer input:** `RenderService.render(...)` returns exactly the `byte[]` layout `MLService.predictRgb`/`embedRgb` expects (224×224 row-major RGB). Don't decode through `BufferedImage` on the hot path — use the raw-RGB methods.

## Testing Infrastructure

**Test stack:** JUnit Jupiter 5.10, Mockito 5.12, MockBukkit `mockbukkit-v1.21:4.50.0`, paper-api `1.21.5-R0.1-SNAPSHOT` (test scope only — main scope still uses Spigot API `1.21.8-R0.1-SNAPSHOT`).

**Version pinning (don't bump unilaterally):** MockBukkit ships a bundled registry-JSON snapshot tied to a specific paper-api version. `4.50.0` ↔ `paper-api 1.21.5` is the only stable pair we have. Bumping either dependency requires bumping both in sync. MockBukkit MUST appear before paper-api/spigot-api in `pom.xml` so its paper-api wins on the test classpath.

**Choosing the right test tool:**
- **Plain Mockito** for listener handlers with pure delegation — mock `BuildBattleAI`, stub the `getContext()` chain, call the `@EventHandler` method directly with a mock event.
- **MockBukkit** when the production path touches Bukkit registries (anything calling `Bukkit.getItemFactory()`, `Bukkit.getPlayer(uuid)`, `Bukkit.getWorld(name)`, or constructing an `ItemStack` whose `getItemMeta()` is read back). Paper-api 1.21.5's `ItemStack.getItemMeta()` is effectively final and cannot be Mockito-stubbed.
- **`MockedStatic`** for code paths that resolve packet factories or static Bukkit/PacketEvents accessors during `enable()`.
- **`MockedConstruction` (chained)** for structural lifecycle tests of `PluginContext.enable/shutdown/reload`.

## Testing Gotchas

- `XMaterial.AIR.ordinal()` is **not** 0 (ordinal 0 is `ACACIA_BOAT`). When creating `FlatScene` test data, always fill arrays with `(short) XMaterial.AIR.ordinal()` — do not rely on default zero-initialization.
- `FlatScene.BlockDataSnapshot` inherits `@Accessors(fluent = true)` — use `snapshot.material()`, not `snapshot.getMaterial()`.
- **`PlayerMock.playSound(...)` throws `UnimplementedOperationException`** for most overloads. If the code under test triggers `SoundPalette.*.play(player)` or any `XSound#play`, subclass `PlayerMock` and override **all eight** `playSound` overloads. Reference: `MLTestCommandTest.SilentPlayerMock`.
- **No worlds by default in MockBukkit.** `Bukkit.getWorlds().get(0)` throws on empty `ServerMock` — call `server.addSimpleWorld(name)` in `@BeforeEach` when code falls back to it.
- **Static listener state survives across tests in the same JVM** (e.g. `MLTestListener.SELECTIONS`). Wipe it via reflection in `@BeforeEach`/`@AfterEach`; do not add production-only reset hooks.
- `MLServiceTest` exercises the **disabled mode** path (no ONNX model on the test classpath). Full inference can't be tested in CI — integration testing via `/bbaitest run [-tta]` on the local 1.21 server.
- `GameListenerTest` mocks `BBAIGameManager` via the **concrete `GameManager` class** (not the interface) because the listener casts to call package-visible helpers (`getPlayerPlotIndex`, `markPlayerZoneDirty`, `applyMirrorPlace`, `applyMirrorBreak`).
- Evaluation pipeline tests live under `test/.../evaluation/`: `EvalConfigTest`, `EvalJobTest`, `RenderQueueTest`, `MlQueueTest`, `RenderWorkerTest`, `MlCoalescerWorkerTest`, `EvaluationCoordinatorTest`, `EvaluationMetricsTest`, `SessionHandleTest`, `EvaluationServiceLifecycleTest`, `EvaluationServiceStatsTest`. The coordinator + workers + queues are deliberately Bukkit-free — they take collaborators by interface and are unit-tested with plain JUnit. Lifecycle/stats tests use MockBukkit + `MockedConstruction` for the chained service set.

## Obfuscation Awareness

ProGuard config in `proguard/`: `base.pro` (shared keep rules) + `light.pro`/`standard.pro`/`heavy.pro` (level-specific). All levels use rename-only (`-dontshrink -dontoptimize`) because the shaded JAR's incomplete class hierarchy prevents safe shrinking. Mapping for deobfuscating stack traces: `target/proguard-mapping.txt`.

Auto-kept patterns: `**.api.**` interfaces, `implements Listener` / `extends Command`, `@EventHandler` methods, `Serializable` machinery. **Manually-kept (don't touch):** `ai.onnxruntime.**` (native code), `libs.**`, `org.apache.ignite.**`, `javax.cache.**` (ServiceLoader + reflection on own class names).

When adding code accessed by name via reflection strings, add explicit `-keep` entries in `base.pro`.

## Dependencies (provided scope = server supplies them)

- **Spigot API 1.21.8** (provided)
- **PacketEvents 2.12.0** (shaded) → `ru.ashesha.buildBattleAI.libs.packetevents`
- **XSeries 13.6.0** (shaded) → `ru.ashesha.buildBattleAI.libs.xseries`
- **Adventure / Kyori 4.25.0** (shaded) → `ru.ashesha.buildBattleAI.libs.kyori`. Forced up from PacketEvents' transitive 4.21.0 — `ObjectContents` was added in 4.25.0; without override the plugin crashes on MC 1.21.6+ with `NoClassDefFoundError`.
- **ONNX Runtime 1.21.0** (compile, NOT relocated) — ships CPU on all platforms + CoreML on macOS + DirectML on Windows. The `onnxruntime_gpu` artifact would add ~600 MB and tie us to a CUDA toolkit version, so we use the plain artifact and let CUDA fail gracefully during probing.
- **Apache Ignite 2.16.0** (shaded, NOT relocated) — Ignite relies on ServiceLoader/JMX/reflection on own class names; zero Ignite classes load when `data.provider: local`.
- **Lombok 1.18.44** (compile-time only)
- **JUnit Jupiter 5.10**, **Mockito 5.12** (test) — Mockito requires `-Dnet.bytebuddy.experimental=true` (already in surefire config)
- **MockBukkit `mockbukkit-v1.21:4.50.0`**, **paper-api `1.21.5-R0.1-SNAPSHOT`** (test, listed BEFORE spigot-api) — version-pinned together; see Testing Infrastructure.
