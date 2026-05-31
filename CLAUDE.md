# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

BuildBattleAI is a Spigot plugin for a Minecraft Build Battle variant where builds are judged by an AI classifier instead of player voting. It includes a CPU-based voxel renderer that captures in-game builds as 224×224 RGB byte buffers and a native, in-process ONNX Runtime inference service that scores those buffers against a custom-trained ConvNeXt-Tiny embedder.

**Game concept:** Players join an arena → countdown starts when minimum players reached → each player receives a theme (topic) → player builds in their plot zone → every 5 seconds the system captures a voxel render of the dirty zone and runs ML classification → if the theme is in the top-K predictions the player scores a point and the zone is cleared, a new theme is assigned → when the game timer expires, the player with most points wins. Players must build *recognizably for a visual classifier*, not just aesthetically.

**Status:** Plugin bootstrap, commands, listeners, renderer, configuration, entity systems (NPCs, holograms, pictures), the arena creation/management system, the full game session lifecycle, and the native ONNX ML pipeline are all implemented. Themes come from the ML model's class list (with a hardcoded fallback when the model fails to load).

## Build & Test Commands

```bash
mvn clean package                        # Build shaded JAR, run tests, copy to ~/Servers/1.21/plugins/
mvn compile                              # Quick compile check
mvn test                                 # Run all tests
mvn test -pl . -Dtest=BlockPaletteTest   # Run a single test class
mvn test -pl . -Dtest="BlockPaletteTest#stoneHasColor"  # Run a single test method
```

The build produces two artifacts in `target/`: the full JAR (with Apache Ignite shaded) and a `-lite` JAR (without Ignite/javax.cache, for servers with an external Ignite cluster). The full JAR is auto-copied to both `~/Servers/1.21/plugins/` and `~/Servers/1.8/plugins/` via maven-antrun-plugin. Start the local test server with `~/Servers/1.21/start.command`.

**JAR signing** (requires a keystore — generate once with `keytool -genkeypair -alias bbai -keyalg RSA -keysize 2048 -validity 3650 -keystore keystore.jks`):
```bash
mvn clean package -Psign -Djarsigner.keystore=./keystore.jks -Djarsigner.alias=bbai -Djarsigner.storepass=<pass> -Djarsigner.keypass=<pass>
```

**Obfuscation** (three levels — rename only / repackage / maximum):
```bash
mvn clean package -Pobfuscate-light      # Rename classes/methods/fields, flatten packages
mvn clean package -Pobfuscate            # Rename + repackage into single flat package
mvn clean package -Pobfuscate-heavy      # Rename + repackage + overload aggressively + access modification
```

Profiles are combinable: `mvn clean package -Pobfuscate-heavy -Psign ...`. Build pipeline order within `package` phase: shade → ProGuard → jarsigner → antrun. Mapping file for deobfuscating stack traces: `target/proguard-mapping.txt`. Keystores (`*.jks`, `*.p12`) are in `.gitignore` — never commit them.

**Resource filtering caveat (DO NOT BREAK):** `src/main/resources/models/**` is *explicitly excluded* from Maven resource filtering in `pom.xml`. Maven filters text resources through a platform-default `Reader`, which would silently corrupt the bundled `custom_convnext_embeddings.onnx` (~107 MiB) and `centroids.json` if filtered. If you add another binary resource, exclude it from the filtered `<resource>` block and re-include it under the unfiltered one.

## Language & Compatibility

**Java 8 only.** Do not use `var`, records, text blocks, `List.of()`, `Map.of()`, switch expressions, pattern matching, or any Java 9+ features.

**Multi-version: 1.8+.** The plugin must run on Spigot/Paper 1.8 through 1.21.x. Never use Bukkit/Spigot APIs that only exist in newer versions without a version check or abstraction layer. Key rules:
- Use **XSeries** (`XMaterial`, `XBlock`, `XSound`, etc.) for all cross-version abstractions — materials, blocks, sounds, and other version-dependent enums.
- Use **PacketEvents** wrappers instead of raw NMS or Bukkit packet APIs. PacketEvents provides multi-version packet abstraction out of the box.
- `MessageService` already handles version branching via PacketEvents `ServerVersion` checks — follow the same pattern (resolve once inside `enable()`, no runtime version checks in hot paths).
- For server-version access, use `plugin.getContext().getServerVersion()` instead of raw `PacketEvents.getAPI().getServerManager().getVersion()`. Safe to call once the context has been published (i.e. inside `PluginService.enable()` and later) — the version is resolved lazily via `Bukkit.getBukkitVersion()` on first call and cached.
- When adding new Bukkit API calls, verify they exist in 1.8 Spigot. If not, add version-gated logic or use PacketEvents/XSeries equivalents. `PlayerSnapshot` is the canonical pattern for off-hand on 1.9+ vs. 1.8.

## Service & API Usage Rules (MANDATORY)

**Priority: project services >>> Spigot API, PacketEvents >>> NMS, XSeries >>> Spigot enums.** Every capability listed below is already implemented with full multi-version support (1.8–1.21), thread safety, and packet-level correctness. Never bypass these abstractions with raw Bukkit/NMS calls. Do not reinvent functionality that already exists — use the existing service.

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
| ML classification | `MLService` via `BBAIMLService` | Direct ONNX Runtime, HTTP, or in-process model loading |
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
plugin.getContext().getNpcService().spawn(viewers, npc, location);
plugin.getContext().getHologramService().spawn(viewers, hologram, location, lines);
plugin.getContext().getPictureService().spawn(viewers, picture, x, y, z, face, image);
plugin.getContext().getRenderService().render(scene, camX, camY, camZ, yaw, pitch);
plugin.getContext().getConfigService().config();             // main config
plugin.getContext().getConfigService().getDefaultLang();      // default language
plugin.getContext().getWorldService().createEmptyWorld("arena_1");   // void world
plugin.getContext().getDataService().getOrCreatePlayer(uuid, name); // player stats
plugin.getContext().getDataService().saveArenaStats(stats);         // arena stats
plugin.getContext().getGameManager().joinArena(player, "arena_1");  // start playing
plugin.getContext().getMlService().predictRgb(rgb, 224, 224, 2);    // top-2 classification
```

## Architecture

**Entry point:** `BuildBattleAI` (extends `JavaPlugin`) — `onLoad()` first runs `JarIntegrityVerifier.verify()` to check the JAR's digital signature; if the JAR is signed and tampered, the plugin sets `integrityVerified = false` and aborts (no PacketEvents, no context). Otherwise `PluginContext` is created in `onLoad()` (after PacketEvents is loaded), then `PluginContext.enable()` is called from `onEnable()` to initialize managers, commands, and listeners. If `integrityVerified` is false, `onEnable()` logs a prominent error and disables the plugin via `getServer().getPluginManager().disablePlugin(this)`. Unsigned JARs (development builds) pass verification unconditionally.

**Service-owned base abstractions** (must be extended for new commands/listeners):
- `CommandService.PluginCommand` (in `commands/`) — extends Bukkit `Command`. No `plugin.yml` declaration needed — subclass it, then register via `pluginContext.getCommandService().register(new MyCommand(plugin))`. The service owns the reflective `CommandMap` / `knownCommands` access and bulk-unregisters all commands on `shutdown()`. Existing commands: `ArenaCommand` (`/bbai` — create/list/delete/join/leave + hidden `setup` subcommands), `MLTestCommand` (`/bbaitest` — wand-based render+ML diagnostic with optional `-tta` flag, saves rendered PNG to `<data>/renders/`), `WorldTpCommand` (`/worldtp <world>` — dev-only world teleport with on-demand world loading from server folder). `PluginCommand` exposes `public final boolean execute(sender, label, args)` that wraps the protected `execute(sender, args)`; production dispatch hits the protected variant, so prefer it (reflective access) in tests.
- `ListenerService.PluginListener` (in `listeners/`) — unified base implementing both Bukkit `Listener` and PacketEvents `PacketListener`. Register via `pluginContext.getListenerService().register(new MyListener(plugin))` — the service registers with both event systems. Bulk-unregisters on `shutdown()`. A single listener can handle `@EventHandler` methods and packet overrides together. Existing listeners: `ArenaSetupListener` (cleanup on player disconnect during arena setup), `GameListener` (full in-game event handling — block-place/break zone check + dirty-flag, damage/food/drop cancellation, quit cleanup via `leaveArena`, TNT/creeper explosion suppression in `bbai_*` worlds), `MLTestListener` (wand selection state for `/bbaitest`; owns a `private static final Map<UUID, Selection> SELECTIONS` cleared on quit).
- **Do not** add `register()`/`unregister()` methods on command or listener subclasses — registration is the service's responsibility. Commands and listeners only take `plugin` in their constructor.
- Command and listener instances are created and registered in `PluginContext.enable()` (Phase 2, after all services are enabled). Add new registrations there.

**Configuration:** `ConfigService` (in `config/`) implements `BBAIConfigService` — manages three configuration zones:
- **Root config** (`config.yml`) — main plugin settings, auto-migrated from bundled defaults on each enable cycle. Access via `config()` / `saveConfig()`.
- **Language directory** (`lang/`) — multiple translation files (bundled: `en.yml`). Missing keys auto-filled from the default language (configured via `default-language` in `config.yml`). Access via `getDefaultLang()`, `getLang(name)`, `getAvailableLangs()`. The `Lang` interface supports placeholder substitution: `lang.get("key", "%player%", name)`.
- **Arena directory** (`arena/`) — dynamically created/deleted arena definition files. Access via `getArenaConfig(name)`, `createArenaConfig(name)`, `saveArenaConfig(name)`, `deleteArenaConfig(name)`.

All YAML I/O uses explicit UTF-8 encoding (avoids platform-default charset bugs on 1.8–1.12). ConfigService is **enabled first** in `PluginContext` — all other services can safely read config during their `enable()`. In-memory caching means reads are zero-cost; writes require explicit `save*()` calls (no auto-save on shutdown).

**Renderer pipeline:**
- `RenderService` (in `render/`) — the entry point for game logic. Owns the `CpuRenderer` instance and its lifecycle: creates the renderer (and its dedicated `ForkJoinPool`) in `enable()`, destroys it in `shutdown()`. Also holds the `legacy` flag (resolved from server version in `enable()`). Exposes:
  - `capture(region)` — main-thread-only; snapshots Bukkit chunks into a thread-safe `ChunkScene`.
  - `render(scene, camX, camY, camZ, yaw, pitch)` — allocating variant, returns a fresh `byte[224*224*3]`.
  - `render(scene, camX, camY, camZ, yaw, pitch, outBuf)` — non-allocating variant; writes into a caller-provided `byte[]` of exactly `224*224*3` bytes. Reuse this buffer in hot loops to avoid the ~150 KB allocation per frame. The same buffer **must not** be shared across concurrent calls.
  - **Always go through this service** from commands/listeners — don't construct `CpuRenderer` or call `ChunkScene.capture()` directly outside the render package (tests excepted). `render(...)` returns raw 224×224 RGB bytes (row-major HWC, 3 bytes/pixel) ready to be handed straight to `MLService.predictRgb` / `embedRgb`.
  - **Shutdown-race semantics:** the internal `renderer` field is `volatile`. A render call that races with `shutdown()` (or runs before `enable()`) gets an explicit `IllegalStateException` instead of a silent NPE; a `RejectedExecutionException` from a terminated pool is converted into the same `IllegalStateException` at the service boundary. Do NOT bypass this guard.
- `SceneData` — thread-safe interface for block access (implementations: `ChunkScene` for live world snapshots, `FlatScene` for flat-array scenes from schematics or snapshots).
- `CpuRenderer` — instance-based CPU voxel ray caster using DDA traversal with semi-transparent front-to-back compositing, per-face Minecraft-style directional shading, quadrant-sampled ambient occlusion, sub-block AABB tests, and emissive bypass. Each instance owns a dedicated `ForkJoinPool` whose threads are **named** (`bbai-renderer-<poolIndex>`) and **daemon** — named for profiling, daemon so the JVM is never held alive by a forgotten pool. Pool is created once in the constructor and released via `shutdown()`. Algorithm methods are private static (pure functions). Note: brightness multipliers are *raised* over vanilla (top=1.0, bottom=0.6, X=0.7, Z=0.85) because the flat-color renderer has no textures — darker multipliers make the classifier confuse blocks. Don't "fix" them back to vanilla.
- `RendererUtils` (`@UtilityClass`) — stateless rendering utilities: image constants (`WIDTH=224`, `HEIGHT=224`, `FOV=70.0`), `toBufferedImage(byte[])` for PNG conversion (uses bulk `setRGB` for 5–10× speedup over per-pixel calls), and `buildHeightMap(SceneData)` acceleration structure (flat `int[2*nCols]` of `(minY, maxY)` pairs per column; lets DDA traversal skip empty voxels). Hard cap: `MAX_HEIGHTMAP_AREA = 512 * 512` — larger horizontal footprints are rejected up front to prevent multi-gigabyte allocations or int overflow. Use these constants instead of hardcoding 224.
- `ChunkScene` — flat `short[]` of XMaterial ordinals, plus optional `byte[] legacyData` (only populated on 1.8–1.12), plus an array of stored `ChunkSnapshot`s. Block-state strings are **lazily resolved** on first access for the rare stateful blocks (stairs/slabs/trapdoors/etc.) — this avoids ~2M `String` allocations at capture time. `MAX_REGION_AXIS = 512` caps any single axis of a captured region. `capture(region, legacy)` takes the `legacy` flag from `RenderService` — the class does not read the server version itself.
- `BlockPalette` — maps `XMaterial` to color/alpha/emissive properties via three parallel arrays indexed by ordinal.
- `BlockShape` — sub-block AABB geometry for non-full-cube blocks; context-sensitive shapes resolved via connectivity (fences/walls/panes) and block state (slabs/stairs/trapdoors).
- `LegacyBlockStates` — converts 1.8–1.12 legacy data values to modern-format block state strings (stairs, slabs, trapdoors, etc.); only used on pre-1.13 servers.
- `BlockRenderState` — parses Minecraft block state strings (e.g. `minecraft:oak_stairs[facing=north,half=bottom]`) with thread-safe caching.
- `ChunkScene.RenderRegion` — inner interface defining the 3D capture region; `RenderRegion.Cuboid` is the standard implementation.

**Entity systems** (in `entity/`):
- `BBAINPCService` / `NPCService` (in `entity/npc/`) — packet-based fake-player NPCs via PacketEvents. NPCs are **stateless**: they store only `entityId` + skin profile. No location or equipment is kept on the NPC object — each viewer can see the same NPC at a different position with different gear. All state tracking is the caller's responsibility.
- `BBAIHologramService` / `HologramService` (in `entity/hologram/`) — packet-based multiline floating text via invisible armor stands stacked at `0.3`-block intervals. Version-dependent metadata factories resolved in `enable()`.
- `BBAIPictureService` / `PictureService` (in `entity/picture/`) — packet-based image display via invisible item frames with filled maps. Each picture is a `width × height` grid of 128×128 maps. Used by arena plots to display the rendered preview next to each build zone.
- `MapPalette` (`@UtilityClass`) — Minecraft map color palette (36 base colors × 4 shades = 144 indices, 1.8-compatible) for converting RGB rasters into Minecraft map color codes.

**Messaging:** `MessageService` (in `message/`) implements `BBAIMessageService` by delegating to six micro-services via Lombok `@Delegate`: `ChatMicroService`, `BarMicroService`, `TitleMicroService`, `TabMicroService`, `NameMicroService`, `BoardMicroService`. Micro-services are instantiated inside `MessageService.enable()`, not the constructor — each one resolves its own `ServerVersion` via `plugin.getContext().getServerVersion()` in its constructor and resolves its version-dependent packet factories once, so there are no runtime version checks in hot paths.

**Data service:** `DataService` (in `data/`) implements `BBAIDataService` — persistent storage for player statistics and arena stats. Supports two interchangeable backends configured via `data.provider` in `config.yml`:
- **Local** (`provider: local`) — JSON files in the `data/` directory via Gson + `ConcurrentHashMap`. Auto-save timer via `BukkitScheduler.runTaskTimerAsynchronously()`. Atomic writes (write `.tmp` then rename).
- **Ignite** (`provider: ignite`) — Apache Ignite 2.16.0 with three node modes: `server` (full node), `thick-client`, `thin-client`. Ignite manages its own threads; config in `data.ignite.*`; persistence data in `ignite/` directory.

The service is disabled entirely when `data.enabled: false` — all methods return defaults/no-ops and no files or connections are created. Internally delegates to a `DataProvider` SPI with generic `DataRepository<K,V>` instances (backed by `IgniteCache` or `ConcurrentHashMap`). Data models: `PlayerData` (keyed by UUID), `ArenaStats` (keyed by arena name) — both implement `Serializable` for Ignite and are Gson-friendly. ArenaStats is separate from ConfigService's arena YAML files (those store static structure; ArenaStats stores runtime statistics).

**ML service (native, in-process):** `MLService` (in `ml/`) implements `BBAIMLService` — runs a **custom-trained ConvNeXt-Tiny embedder** locally via ONNX Runtime. **No external service, no HTTP, no FastAPI — everything runs inside the JVM.**

- **Bundled resources** (loaded from classpath at `enable()`):
  - `/models/custom_convnext_embeddings.onnx` (~107 MiB) — the model file
  - `/models/centroids.json` — 15 build-theme class centroids (128-dim each, L2-normalized; produced by the external `train_pipeline.py`). Fallback hardcoded class list (`FALLBACK_CLASSES`) is used if the JSON is missing/malformed.
- **Backend probing on `enable()`:** the service walks `Backend.values()` in preference order — **CoreML → CUDA → DirectML → ROCm → CPU** — building a `SessionOptions` per backend and warming up the resulting session at *both* `batch=1` and `batch=TTA_VIEWS` (4). The first backend whose session loads + warm-up succeeds wins. The active backend is logged at INFO and queryable via `backend()`.
- **Session config:** sequential execution mode, `ALL_OPT` optimization, `allow_spinning=0` on both thread pools (critical — otherwise ORT pins a CPU core 100% even when idle, starving the Bukkit main thread), `intraOpNumThreads = min(4, max(2, cores/2))`, `interOpNumThreads = 1`, memory-pattern + arena allocator on. Each backend can override these via `Backend#apply` — notably DirectML disables memory-pattern, CoreML opts into `CREATE_MLPROGRAM`.
- **Embedding dimensionality:** 128, exposed via `embeddingDim()`.
- **API surface** (every method has three flavors, all native — no round-trips between them):
  - `embed(BufferedImage)` / `embed(byte[] encoded)` / `embedRgb(byte[] rgbPixels, int w, int h)` — single image → L2-normalized 128-dim embedding.
  - `embedBatch(...)` — batched variants: one ONNX `run()` call regardless of batch size.
  - `predict(...)` / `predictRgb(...)` / `predictBatch(...)` — embed + top-K classification by cosine similarity against centroids. Returns `PredictionResult { embedding, predictedClass, predictedScore, predictedCentroid, topK : List<TopKEntry> }`.
  - `*WithTTA` variants — for every input build `TTA_VIEWS=4` augmented views (shared bilinear resize to 246×246, then random crop / hflip / brightness jitter ∈ [0.85, 1.15], then ImageNet normalize), submit them all in one super-batch, sum the embeddings and L2-normalize. Cheaper-but-good substitute for the original 8-view PIL-style augmentation; the four cheaper augmentations were kept because rotation/contrast/saturation didn't move the needle. Use the renderer's native 224×224 RGB output with `predictWithTTA(rgb, 224, 224, k)`.
  - `classNames()` / `centroids()` / `embeddingDim()` / `ttaViews()` / `backend()` — metadata.
- **Disabled / fallback mode:** if the ONNX resource is missing or no backend loads, the service stays alive: every inference call returns a zero-filled embedding, predictions return deterministic-but-meaningless rankings, and `backend()` returns `"DISABLED"`. This is what `GameManager` falls back to when it can't pull real themes from `classNames()`.
- **Thread-safety:** ORT sessions are thread-safe for concurrent `run()` calls. Each request allocates its own input tensor and closes it before returning. TTA augmentation uses `ThreadLocalRandom`. All methods are **blocking** — call from an async Bukkit task; never from the main thread.

**Game session lifecycle:** `GameManager` (in `game/`) implements `BBAIGameManager` — owns all active sessions, keyed by arena name. Per-player join → countdown → play → end loop.

- State machine in `ArenaState`: `WAITING → COUNTDOWN → PLAYING → ENDING → WAITING`. Transitions are driven by `minPlayers` (start countdown), countdown reaching 0 (start game), `gameTime` reaching 0 (end game), and all-players-leave shortcuts. `getArenaState(name)` returns `WAITING` when no session exists.
- **`GameSession`** (package-private) — tracks one arena's active session: state, players (`Map<UUID, GamePlayer>`), used plot indices, shuffled theme list, rotating camera index (cycles 0→1→2→0…), remaining game time, and the four scheduler task IDs (countdown / game tick / render / ending) so `cancelAllTasks()` cleans up reliably on shutdown.
- **`GamePlayer`** (package-private) — per-player session state: plot index, score, theme index, per-player build time remaining, `zoneDirty` flag (set by `GameListener` on place/break, consumed by the render tick).
- **`PlayerSnapshot`** (package-private) — captures full pre-game player state: location, gamemode, inventory + armor + off-hand (off-hand gated by `ServerVersion.V_1_9`), potion effects, level, exp, health, food, saturation, allow-flight, flying, fire ticks. Deep-clones all `ItemStack`s and `PotionEffect`s. Restored on leave / game end / shutdown / disconnect (`GameListener.onPlayerQuit` → `leaveArena`).
- **Timers** (Bukkit scheduler):
  - Countdown: 1 Hz, ticks the `arena.countdownTime()` down to 0.
  - Game tick: 1 Hz, decrements global game time and every player's `buildTimeRemaining`. Build-time expiry advances the player's theme, clears their zone, resets the timer.
  - Render/ML: every `RENDER_INTERVAL_TICKS = 100` (5 s). For each player whose zone is dirty: `RenderService.capture(...)` runs synchronously on the main thread, then `runTaskAsynchronously` does `render(...) + predictRgb(rgb, 224, 224, 2)`. If the player's theme is in the top-2, `handleScore` is scheduled back on the main thread to bump score, clear the zone, advance the theme, and re-arm the build timer. Camera rotates between the plot's 3 cameras per render tick (session-wide, not per-player).
- **Themes** come from `MLService.classNames()` (shuffled); if the ML service is in disabled mode (`classNames()` empty), `FALLBACK_THEMES` is used instead.
- **GameManager exposes two helpers used by `GameListener`** that are not part of `BBAIGameManager`: `getPlayerPlotIndex(uuid, arenaName)` and `markPlayerZoneDirty(uuid, arenaName)`. There's also a static `isInZone(x, y, z, plot)` AABB check shared between the listener and the manager itself.
- **Forced-shutdown semantics:** `GameManager.shutdown()` walks every active session, cancels all timers, clears every plot zone, restores every player's snapshot, and clears the maps. Invariant: after a plugin reload the world is exactly as if no game was ever in progress.

**Utilities:**
- `EntityUtils` (`@UtilityClass`) — shared monotonic entity ID allocator for all packet-based entity services (NPCs, holograms, pictures). A single `AtomicInteger` counter guarantees no two synthetic entities (regardless of type) share an ID. All entity services must use `EntityUtils.nextEntityId()` instead of maintaining their own counters.
- `ReflectionUtils` — generic reflection helpers (`findField`, `findMethod`, `getFieldValue`, `setFieldValue`, `invokeMethod`, `invokeStaticMethod`) with hierarchy traversal, auto `setAccessible`, and unchecked exception wrapping. Used by `CommandService` for `CommandMap` access.
- `JarIntegrityVerifier` (`@UtilityClass`) — verifies JAR digital signatures at startup using `java.util.jar.JarFile` with verification enabled. Returns `true` for unsigned JARs (dev builds) and intact signed JARs; returns `false` if a signed JAR has been tampered with.
- `SoundPalette` (`@UtilityClass`) — curated palette of cross-version sound effects (CONFIRM, CLICK, ERROR, SUCCESS, WELCOME, DENY, SCORE, etc.) using `XSound` for UI feedback. Used by `ArenaManager` setup wizard and `MLTestCommand`.

**World service:** `WorldService` (in `world/`) implements `BBAIWorldService` — dynamic void world management for arena isolation. Creates completely empty (air-only) worlds using a stateless `VoidChunkGenerator` that supports both the modern `ChunkData` API and the legacy `byte[]` API (pre-1.8 fallback). Operations: `createEmptyWorld(name)`, `loadWorld(name)`, `unloadWorld(world)`, `deleteWorld(world)`. All Bukkit world operations must be called from the main server thread. The service tracks names it has created/loaded during the current lifecycle (cleared on shutdown). Worlds are not automatically unloaded on shutdown — cleanup is the caller's responsibility.

**Arena system:** `ArenaManager` (in `arena/`) implements `BBAIArenaManager` — full arena lifecycle: YAML loading with strict validation, interactive non-linear creation wizard, runtime state tracking, deletion with world cleanup.

- **Non-linear setup wizard:** `/bbai create <name>` creates a void world (`bbai_<name>`) and shows a panel with ALL settings visible at once. The admin can fill them in any order, re-select values, and confirm when ready. Every setting change re-sends the full panel. The panel uses clickable `ChatMessage` segments that execute hidden `/bbai setup <action>` commands.
- **Arena data model** (`Arena` + nested `Position` / `PlotData` / `PictureRegion` in `arena/api/`):
  - **Global fields:** `name`, `worldName`, `maxPlayers` (2–8), `enabled`, `lobby: Position`, `spectator: Position` (nullable — `effectiveSpectator()` falls back to lobby), `minPlayers` (default 2), `buildTime` (per-build seconds, default 150), `gameTime` (total session seconds, default 300), `countdownTime` (default 5), and `plots: List<PlotData>`.
  - **`PlotData`:** spawn point, two cuboid build-zone corners (six ints), **3 cameras** (`List<Position>` — exactly 3 angles, rotated by `GameManager` across render ticks), and a `PictureRegion` for the in-world preview display.
  - **`PictureRegion`:** flat 1×1 or 2×2 in-world rectangle with a `face: BlockFace` (NORTH/SOUTH for XY-plane 2×2, EAST/WEST for YZ-plane 2×2, any cardinal for 1×1). Validation enforces coplanarity and face/plane compatibility in the constructor.
  - **`Position`:** `double x, y, z` + `float yaw, pitch` — used for spawn/lobby/spectator/cameras.
- **Config validation:** `deserializeArena()` collects all missing required fields into a list, logs each as an ERROR, then skips the arena. Arenas with invalid configs are never activated.
- **Future-proofing:** optional fields (`spectator`, `min-players`, `build-time`, `game-time`, `countdown-time`) have defaults so existing arena configs remain valid as new features land.
- **Setup session** (`ArenaSetupSession`, package-private): non-linear data bag — no step enum, all fields nullable until set. `isComplete()` checks that maxPlayers, lobby, and all per-plot fields (spawn, corner1, corner2, all 3 cameras, picture geometry) are filled. `trimPlotsAbove(n)` discards excess plots when the player count is reduced.
- Arena worlds use the naming convention `bbai_<arena_name>`. On startup, enabled arenas try `loadWorld()` first, falling back to `createEmptyWorld()`.

**Logging:** `PluginLogger` (in `core/`) — configurable logging wrapper around Java's `Logger`. Four levels: `DEBUG`, `INFO`, `WARN`, `ERROR` (plus `OFF`). Supports `String.format`-style formatting and throwable logging. Uses pre-resolved action lambdas — level checks happen once when the level changes, not on every log call. Debug messages are emitted at Java `INFO` level with a `[DEBUG]` prefix (Bukkit consoles drop below `INFO`). Log level configured via `log-level` in `config.yml`. Access via `plugin.getPluginLogger()`.

**Lifecycle coordinator:** `PluginContext` (in `core/`) owns all services. All services are constructed once in `PluginContext`'s constructor and reused across reload cycles. Each service implements the internal `PluginService` interface (`enable()`, `shutdown()`, default `reload()` = `shutdown` + `enable`). `PluginContext.enable()` calls `enable()` on every service in construction order (ConfigService → DataService → WorldService → ArenaManager → GameManager → MessageService → NPCService → HologramService → PictureService → MLService → RenderService → CommandService → ListenerService) and then registers commands (`ArenaCommand`, `MLTestCommand`, `WorldTpCommand`) and listeners (`ArenaSetupListener`, `GameListener`, `MLTestListener`). `PluginContext.shutdown()` walks the list in reverse; `PluginContext.reload()` is a clean `shutdown` + `enable`. Invariant: after `shutdown` the plugin holds no runtime resources and has not disturbed any other plugin or the server; after a subsequent `enable` the plugin is running exactly as after a fresh server start. `PluginService` is intentionally NOT part of the public API — API interfaces (`BBAI*`) never expose lifecycle methods. `PluginContext` also provides `sendPacket()` and `getUserProfile()` helpers for PacketEvents interaction.

**Package layout:**
- `core/` — `PluginContext`, `PluginService` (lifecycle contract), `PluginLogger`
- `commands/` — `CommandService`, `ArenaCommand`, `MLTestCommand`, `WorldTpCommand`
- `listeners/` — `ListenerService`, `ArenaSetupListener`, `GameListener`, `MLTestListener`
- `render/` — `RenderService`, `CpuRenderer`, `BlockPalette`, `BlockShape`, `BlockRenderState`; `render/data/` has `ChunkScene`, `FlatScene`, `SceneData`, `LegacyBlockStates`
- `message/` — `MessageService`, micro-services; `message/api/` has `BBAIMessageService`
- `entity/npc/`, `entity/hologram/`, `entity/picture/` — packet-based entity services; each has an `api/` subpackage
- `data/` — `DataService`, `DataProvider`, `DataRepository`, `LocalDataProvider`, `LocalRepository`, Ignite providers/repositories; `data/api/` has `BBAIDataService`, `PlayerData`, `ArenaStats`
- `ml/` — `MLService`; `ml/api/` has `BBAIMLService`, `PredictionResult`, `TopKEntry`
- `arena/` — `ArenaManager`, `ArenaSetupSession`; `arena/api/` has `BBAIArenaManager`, `Arena` (with `Position`, `PlotData`, `PictureRegion`)
- `game/` — `GameManager`, `GameSession`, `GamePlayer`, `PlayerSnapshot`, `ArenaState`; `game/api/` has `BBAIGameManager`
- `world/` — `WorldService`; `world/api/` has `BBAIWorldService`
- `config/` — `ConfigService`, `Lang`; `config/api/` has `BBAIConfigService`, `Lang`
- `util/` — `ReflectionUtils`, `MessageUtils`, `EntityUtils`, `RendererUtils`, `MapPalette`, `JarIntegrityVerifier`, `SoundPalette`

**Threading rules:**
- Capture Bukkit world data only on the main thread; all render and palette logic is async-safe.
- `MLService.*` is blocking and must NOT be called from the main thread. The canonical pattern is `Bukkit.getScheduler().runTaskAsynchronously(...)` wrapping the render + predict pair, then `runTask(...)` back to the main thread for any Bukkit-state mutation (scoring, zone clearing, teleport).

## Code Quality Requirements

**Comments:** All new code must include professional English comments. Add Javadoc to every new public class, interface, method, and field. Use inline comments to explain non-obvious logic, algorithmic choices, edge cases, and threading considerations. Comments should explain *why*, not just *what*.

**Test coverage:** Every new feature, utility, or behavioral change must be accompanied by tests. Cover the happy path, edge cases, error conditions, and boundary values. For renderer/palette/ML/game code that runs without Bukkit, write JUnit tests. For Bukkit-dependent code, add Mockito-based tests where feasible (see existing `commands/`, `listeners/`, and `game/` tests for patterns). If a class truly cannot be unit-tested without a live server, document that explicitly in the test class or in a `package-info.java`.

## Code Style

**Brace-free single-statement bodies:** For `if`, `for`, `while`, and similar control structures, if the body is a single statement, do **not** use `{}` — but **always** put the body on the next line (never on the same line as the condition).

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

- Use `XMaterial` (from XSeries) instead of raw `org.bukkit.Material` in renderer code. XSeries is shaded/relocated to `ru.ashesha.buildBattleAI.libs.xseries`.
- Use Lombok for boilerplate (`@RequiredArgsConstructor`, `@Getter`, `@UtilityClass`, `@Accessors(fluent = true)`). Avoid Lombok when constructors have real initialization logic.
- Package root: `ru.ashesha.buildBattleAI`. Each domain package has its own `api/` subpackage for public interfaces.
- `plugin.yml` metadata (name, version, main, description, authors, website, prefix) is filled from `pom.xml` via Maven resource filtering — edit values in `pom.xml`, not in `plugin.yml`.
- `FlatScene` array layout is X-major: `data[(x-minX)*sizeY*sizeZ + (y-minY)*sizeZ + (z-minZ)]`.
- **Service constructors must not call `plugin.getContext()`.** Services are instantiated inside `PluginContext`'s constructor, at which point `BuildBattleAI#context` is still `null`. Defer anything that needs the context (including `getServerVersion()`, `getUserProfile()`, `sendPacket()`) to `PluginService.enable()` — by the time it runs, the context reference has been published. Services that need to prepare version-dependent state (e.g. `RenderService.legacy` and its `CpuRenderer` instance, `NPCService`'s packet factories, `MessageService`'s sub-services, `GameManager`'s `serverVersion`) follow this pattern: keep the field non-final, resolve it in `enable()`.
- **ML output → renderer input:** `RenderService.render(...)` returns exactly the `byte[]` layout `MLService.predictRgb` / `embedRgb` expects (224×224 row-major RGB). Don't decode through `BufferedImage` on the hot path — use the raw-RGB methods.

## Testing Infrastructure

**Test stack:** JUnit Jupiter 5.10, Mockito 5.12, MockBukkit `mockbukkit-v1.21:4.50.0`, paper-api `1.21.5-R0.1-SNAPSHOT` (test scope only — main scope still uses Spigot API `1.21.8-R0.1-SNAPSHOT`).

**Version pinning rationale (don't bump unilaterally):** MockBukkit ships a bundled registry-JSON snapshot tied to a specific paper-api version. `4.50.0` ↔ `paper-api 1.21.5` is the only stable pair we have. Newer MockBukkit (4.60+) targets paper-api 1.21.6+, which adds the `minecraft:dialog` registry — MockBukkit ≤ 4.60 fails to load it with `InternalDataLoadException`. Bumping either dependency requires bumping both in sync. The MockBukkit dependency MUST appear before paper-api/spigot-api in `pom.xml` so its paper-api wins on the test classpath (per MockBukkit README).

**Choosing the right test tool:**
- **Plain Mockito** for listener handlers with pure delegation (`GameListenerTest`, `ArenaSetupListenerTest`): mock `BuildBattleAI`, stub the `getContext()` chain, call the `@EventHandler` method directly with a mock event. Fast and isolated.
- **MockBukkit** when the production path touches Bukkit registries — anything that calls `Bukkit.getItemFactory()`, `Bukkit.getPlayer(uuid)`, `Bukkit.getWorld(name)`, or constructs an `ItemStack` whose `getItemMeta()` is read back. Paper-api 1.21.5's `ItemStack.getItemMeta()` is effectively final (factory-delegating) and cannot be Mockito-stubbed.
- **`MockedStatic`** for code paths that resolve packet factories or static Bukkit/PacketEvents accessors during `enable()` — see `CommandServiceTest`, `ListenerServiceTest`.
- **`MockedConstruction` (chained)** for structural lifecycle tests of `PluginContext.enable/shutdown/reload` — intercepts every `new XService(plugin)` so order/getter contracts can be verified on mocks without bootstrapping PacketEvents (`PluginContextLifecycleTest`).

**MockBukkit traps to know:**
- **`PlayerMock.playSound(...)` throws `UnimplementedOperationException`** for most overloads, including the 1.19+ seed-bearing variants. If the production code triggers `SoundPalette.*.play(player)` or any `XSound#play`, subclass `PlayerMock` and override **all eight** `playSound` overloads. Reference: `MLTestCommandTest.SilentPlayerMock`.
- **No worlds by default.** `Bukkit.getWorlds().get(0)` throws on an empty `ServerMock` — if the code under test falls back to it (`ArenaManager.returnPlayer`, `PlayerSnapshot.restore` when the saved world unloads), call `server.addSimpleWorld(name)` in `@BeforeEach`.
- **`XSound.SoundPlayer.play` reaches into `Bukkit.getPlayer(uuid)`** — any code that emits a curated sound via `SoundPalette` requires MockBukkit (or a `MockedStatic<Bukkit>`).
- **`Bukkit.getUnsafe().getMainLevelName()`** is invoked by paper-api's `WorldCreator`. If you mock `Bukkit` statically while exercising `WorldService`, stub `getUnsafe()` too.
- **Static listener state survives across tests in the same JVM** (e.g. `MLTestListener.SELECTIONS`). Wipe it via reflection in `@BeforeEach`/`@AfterEach`; do not add production-only reset hooks.

**Mirror layout:** Tests live in `src/test/java/ru/ashesha/buildBattleAI/<same-package>/`. The `test/.../mockbukkit/` package holds smoke tests that verify MockBukkit itself behaves as expected after a version bump — keep at least one canary test there so a broken pair surfaces immediately on `mvn test`.

## Testing Gotchas

- `XMaterial.AIR.ordinal()` is **not** 0 (ordinal 0 is `ACACIA_BOAT`). When creating `FlatScene` test data, always fill arrays with `(short) XMaterial.AIR.ordinal()` — do not rely on default zero-initialization.
- `FlatScene.BlockDataSnapshot` inherits `@Accessors(fluent = true)` from the enclosing `FlatScene` class — use `snapshot.material()`, not `snapshot.getMaterial()`.
- Tests for `BlockPalette`, `BlockShape`, `BlockRenderState`, `CpuRenderer`, `RendererUtils`, and `FlatScene` run without a Bukkit server. `MessageService` and `ChunkScene` still require a live server (tested manually). Commands and listeners are unit-tested via the MockBukkit/Mockito hybrid described above.
- `MLServiceTest` exercises the **disabled mode** path (no ONNX model on the test classpath) — it asserts zero embeddings, deterministic fallback centroids, and `backend() == "DISABLED"`. Full inference can't be tested in CI because the model file is too large to bundle in tests; integration testing happens via `/bbaitest run [-tta]` on the local 1.21 server.
- `CpuRendererTest` creates a shared `CpuRenderer` instance in `@BeforeAll` and shuts it down in `@AfterAll` to avoid pool creation overhead per test. Follow this pattern when adding renderer tests.
- `CommandServiceTest` uses `MockedStatic<Bukkit>` + `MockedStatic<ReflectionUtils>` inside a try-with-resources `@BeforeEach` just long enough to run `enable()` — once `enable()` returns, the resolved `CommandMap`/`knownCommands` references are captured on the service instance and the statics are closed. `ListenerServiceTest` uses `MockedStatic<PacketEvents>` per-test to mock the event manager.
- `ArenaManagerTest` mocks `PluginContext`, `BBAIConfigService`, and `BBAIWorldService` to test arena loading, validation, and deletion without a live server. The `buildValidArenaConfig()` helper produces a complete YAML with all required fields (lobby, per-plot spawn/corners/cameras/picture). Use it as a template.
- `ArenaSetupWizardTest` covers the interactive setup flow by combining MockBukkit (for the wand `ItemStack` round-trip and `Bukkit.getPlayer`) with mocked services. `ArenaSetupSessionTest` exercises the pure session-state logic (`isComplete`, geometry classification, `isFaceAllowed`, `trimPlotsAbove`) without any Bukkit primitives.
- `PluginContextLifecycleTest` uses simultaneously-active `MockedConstruction` blocks (one per service) to intercept every `new XService(plugin)` inside `PluginContext`'s constructor and verify `enable()`/`shutdown()` ordering and `reload()` semantics. If you add a new service, update the construction order assertion — order is part of the lifecycle contract.
- `GameManagerTest` / `GameSessionTest` / `GamePlayerTest` / `PlayerSnapshotTest` cover the session lifecycle in isolation: state transitions, plot assignment, scoring, snapshot deep-cloning, off-hand gating on 1.9+ vs 1.8. Mock the ML and render services to keep them deterministic.

## Obfuscation Awareness

ProGuard keep rules in `proguard/base.pro` protect classes that must not be renamed. When adding new code, ensure these patterns are followed so the obfuscated build doesn't break:
- **New API interfaces** in `**/api/` packages are auto-kept by the `**.api.**` wildcard rule.
- **New Serializable classes** (Gson/Ignite) must have their fields kept — the existing `Serializable` rule covers `serialVersionUID` and read/write methods, but if field names matter for JSON, add explicit `-keepclassmembers` in `base.pro`.
- **New Bukkit listeners/commands** are auto-kept via the `implements Listener` / `extends Command` rules.
- **New `@EventHandler` methods** are auto-kept by the annotation rule.
- **New utility classes accessed by name** (e.g. via reflection strings) need explicit `-keep` entries in `base.pro`.
- **ONNX Runtime** (`ai.onnxruntime.**`) and **shaded libraries** (`libs.**`, `org.apache.ignite.**`, `javax.cache.**`) are fully kept and must not be touched — they load native code and use reflection on their own class names.

ProGuard config files live in `proguard/`: `base.pro` (shared keep rules), `light.pro`, `standard.pro`, `heavy.pro` (level-specific options). All levels use rename-only obfuscation (`-dontshrink -dontoptimize`) because the shaded JAR's incomplete class hierarchy (many provided-scope deps) prevents ProGuard from safely shrinking or optimizing.

## Dependencies (provided scope = server supplies them)

- **Spigot API 1.21.8** (provided) — server runtime
- **PacketEvents 2.12.0** (shaded) — packet-level interaction, relocated to `ru.ashesha.buildBattleAI.libs.packetevents`
- **XSeries 13.6.0** (shaded) — cross-version material abstraction, relocated to `ru.ashesha.buildBattleAI.libs.xseries`
- **Adventure / Kyori 4.25.0** (shaded) — component text serialization, relocated to `ru.ashesha.buildBattleAI.libs.kyori`. Version is *forced* up from PacketEvents' transitive 4.21.0 because `net.kyori.adventure.text.object.ObjectContents` was only added in 4.25.0; without the override the plugin crashes with `NoClassDefFoundError` on MC 1.21.6+ when PacketEvents decodes item components.
- **ONNX Runtime 1.21.0** (compile, NOT relocated) — native ML inference. Ships CPU on all platforms + CoreML on macOS (arm64/x86_64) + DirectML on Windows out of the box. The companion `onnxruntime_gpu` artifact would add CUDA bindings but bloat the JAR by ~600 MB and tie it to a specific CUDA toolkit, so we keep the plain artifact and let CUDA fail gracefully during provider probing.
- **Apache Ignite 2.16.0** (shaded, NOT relocated) — distributed data grid for clustered storage; relocation is not used because Ignite relies on ServiceLoader, JMX, and reflection on its own class names. Zero Ignite classes are loaded when `data.provider` is `local`.
- **Lombok 1.18.44** (compile-time only) — annotation processing for boilerplate reduction
- **JUnit Jupiter 5.10** (test scope) — unit testing
- **Mockito 5.12** (test scope) — mocking for command/listener tests (requires `-Dnet.bytebuddy.experimental=true`, already configured in surefire)
- **MockBukkit `mockbukkit-v1.21:4.50.0`** (test scope) — fake Bukkit server. Version pinned (see Testing Infrastructure above).
- **paper-api `1.21.5-R0.1-SNAPSHOT`** (test scope, listed BEFORE the spigot-api `provided` entry) — pulled in for MockBukkit; must match MockBukkit's bundled registry version.
