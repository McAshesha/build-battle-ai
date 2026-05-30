# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

BuildBattleAI is a Spigot plugin for a Minecraft Build Battle variant where builds are judged by an AI classifier instead of player voting. It includes a CPU-based voxel renderer that captures in-game builds as 224x224 PNG images for ML classification.

**Game concept:** Players join an arena → countdown starts when minimum players reached → each player receives a theme (topic) → player builds in their zone → system captures voxel-rendered images of the build → images are sent to the ML service → if the classifier correctly recognizes the object as matching the theme, the player scores a point → build is cleared, new theme assigned → when time expires, the player with most points wins. The core design principle: players must build *recognizably for a visual classifier*, not just aesthetically.

**Status:** Plugin bootstrap, commands, listeners, renderer, configuration, entity systems (NPCs, holograms, pictures), and the arena creation/management system are implemented. Game session lifecycle, topic assignment, and scoring (`GameManager`) are deferred pending game flow design. ML integration has a REST proxy with a fallback for offline play.

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

## Language & Compatibility

**Java 8 only.** Do not use `var`, records, text blocks, `List.of()`, `Map.of()`, switch expressions, pattern matching, or any Java 9+ features.

**Multi-version: 1.8+.** The plugin must run on Spigot/Paper 1.8 through 1.21.x. Never use Bukkit/Spigot APIs that only exist in newer versions without a version check or abstraction layer. Key rules:
- Use **XSeries** (`XMaterial`, `XBlock`, `XSound`, etc.) for all cross-version abstractions — materials, blocks, sounds, and other version-dependent enums.
- Use **PacketEvents** wrappers instead of raw NMS or Bukkit packet APIs. PacketEvents provides multi-version packet abstraction out of the box.
- `MessageService` already handles version branching via PacketEvents `ServerVersion` checks — follow the same pattern (resolve once inside `enable()`, no runtime version checks in hot paths).
- For server-version access, use `plugin.getContext().getServerVersion()` instead of raw `PacketEvents.getAPI().getServerManager().getVersion()`. Safe to call once the context has been published (i.e. inside `PluginService.enable()` and later) — the version is resolved lazily via `Bukkit.getBukkitVersion()` on first call and cached.
- When adding new Bukkit API calls, verify they exist in 1.8 Spigot. If not, add version-gated logic or use PacketEvents/XSeries equivalents.

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
| ML classification | `MLService` via `BBAIMLService` | Direct HTTP calls to the FastAPI service |
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
```

## Architecture

**Entry point:** `BuildBattleAI` (extends `JavaPlugin`) — `onLoad()` first runs `JarIntegrityVerifier.verify()` to check the JAR's digital signature; if the JAR is signed and tampered, the plugin sets `integrityVerified = false` and aborts (no PacketEvents, no context). Otherwise `PluginContext` is created in `onLoad()` (after PacketEvents is loaded), then `PluginContext.enable()` is called from `onEnable()` to initialize managers, commands, and listeners. If `integrityVerified` is false, `onEnable()` logs a prominent error and disables the plugin via `getServer().getPluginManager().disablePlugin(this)`. Unsigned JARs (development builds) pass verification unconditionally.

**Service-owned base abstractions** (must be extended for new commands/listeners):
- `CommandService.PluginCommand` (in `commands/`) — extends Bukkit `Command`. No `plugin.yml` declaration needed — subclass it, then register via `pluginContext.getCommandService().register(new MyCommand(plugin))`. The service owns the reflective `CommandMap` / `knownCommands` access and bulk-unregisters all commands on `shutdown()`. Existing commands: `ArenaCommand` (`/bbai`), `MLTestCommand` (`/bbaitest` — diagnostic wand-based render + ML pipeline). `PluginCommand` exposes `public final boolean execute(sender, label, args)` that wraps the protected `execute(sender, args)`; production dispatch hits the protected variant, so prefer it (reflective access) in tests.
- `ListenerService.PluginListener` (in `listeners/`) — unified base implementing both Bukkit `Listener` and PacketEvents `PacketListener`. Register via `pluginContext.getListenerService().register(new MyListener(plugin))` — the service registers with both event systems. Bulk-unregisters on `shutdown()`. A single listener can handle `@EventHandler` methods and packet overrides together. Existing listeners: `ArenaSetupListener` (cleanup on player disconnect during arena setup), `GameListener` (in-game event cancellation in arena worlds — damage/food/drop/explosion), `MLTestListener` (wand selection state for `/bbaitest`; owns a `private static final Map<UUID, Selection> SELECTIONS` cleared on quit).
- **Do not** add `register()`/`unregister()` methods on command or listener subclasses — registration is the service's responsibility. Commands and listeners only take `plugin` in their constructor.
- Command and listener instances are created and registered in `PluginContext.enable()` (Phase 2, after all services are enabled). Add new registrations there.

**Configuration:** `ConfigService` (in `config/`) implements `BBAIConfigService` — manages three configuration zones:
- **Root config** (`config.yml`) — main plugin settings, auto-migrated from bundled defaults on each enable cycle. Access via `config()` / `saveConfig()`.
- **Language directory** (`lang/`) — multiple translation files (bundled: `en.yml`). Missing keys auto-filled from the default language (configured via `default-language` in `config.yml`). Access via `getDefaultLang()`, `getLang(name)`, `getAvailableLangs()`. The `Lang` interface supports placeholder substitution: `lang.get("key", "%player%", name)`.
- **Arena directory** (`arena/`) — dynamically created/deleted arena definition files. Access via `getArenaConfig(name)`, `createArenaConfig(name)`, `saveArenaConfig(name)`, `deleteArenaConfig(name)`.

All YAML I/O uses explicit UTF-8 encoding (avoids platform-default charset bugs on 1.8–1.12). ConfigService is **enabled first** in `PluginContext` — all other services can safely read config during their `enable()`. In-memory caching means reads are zero-cost; writes require explicit `save*()` calls (no auto-save on shutdown).

**Renderer pipeline:**
- `RenderService` (in `render/`) — the entry point for game logic. Owns the `CpuRenderer` instance and its lifecycle: creates the renderer (and its dedicated `ForkJoinPool`) in `enable()`, destroys it in `shutdown()`. Also holds the `legacy` flag (resolved from server version in `enable()`). Exposes `render(scene, camX, camY, camZ, yaw, pitch)`, `capture(region)`. **Always go through this service** from commands/listeners — don't construct `CpuRenderer` or call `ChunkScene.capture()` directly outside the render package (tests excepted).
- `SceneData` — thread-safe interface for block access (implementations: `ChunkScene` for live world snapshots, `FlatScene` for flat-array scenes from schematics or snapshots)
- `CpuRenderer` — instance-based CPU voxel ray caster using DDA traversal. Each instance owns a dedicated `ForkJoinPool`; the pool is created once in the constructor and released via `shutdown()`. After shutdown the instance is discarded — `RenderService` creates a fresh one on the next `enable()`. Algorithm methods are private static (pure functions).
- `RendererUtils` (`@UtilityClass`) — stateless rendering utilities: image constants (`WIDTH`, `HEIGHT`, `FOV`), `toBufferedImage(byte[])` for PNG conversion, and `buildHeightMap(SceneData)` acceleration structure. Use these constants instead of hardcoding 224.
- `ChunkScene.capture(region, legacy)` — the `legacy` flag is passed in by `RenderService`; the class does not read the server version itself.
- `BlockPalette` — maps `XMaterial` to color/alpha/emissive properties via three parallel arrays indexed by ordinal
- `BlockShape` — sub-block AABB geometry for non-full-cube blocks; context-sensitive shapes resolved via connectivity (fences/walls/panes) and block state (slabs/stairs/trapdoors)
- `LegacyBlockStates` — converts 1.8–1.12 legacy data values to modern-format block state strings (stairs, slabs, trapdoors, etc.); only used on pre-1.13 servers
- `BlockRenderState` — parses Minecraft block state strings (e.g. `minecraft:oak_stairs[facing=north,half=bottom]`) with thread-safe caching
- `ChunkScene.RenderRegion` — inner interface defining the 3D capture region; `RenderRegion.Cuboid` is the standard implementation

**Entity systems** (in `entity/`):
- `BBAINPCService` / `NPCService` (in `entity/npc/`) — packet-based fake-player NPCs via PacketEvents. NPCs are **stateless**: they store only `entityId` + skin profile. No location or equipment is kept on the NPC object — each viewer can see the same NPC at a different position with different gear. All state tracking is the caller's responsibility.
- `spawn(viewers, npc, location)` — player-info → spawn → skin metadata → head look → tab cleanup
- `teleport(viewers, npc, destination)` — absolute position via entity teleport packet + head look
- `move(viewers, npc, from, to)` — relative move + rotation packet; auto-falls back to teleport if delta > 8 blocks
- Version-dependent factories (spawn packet, player-info add/remove, skin metadata index) are resolved in `enable()` — same pattern as `MessageService` — and rebound on each reload cycle.
- `BBAIHologramService` / `HologramService` (in `entity/hologram/`) — packet-based multiline floating text via invisible armor stands. Each hologram is a stack of invisible armor stands with custom names, spaced `0.3` blocks apart vertically. Version-dependent metadata factories (custom name type, name visible type, armor stand flags index) are resolved in `enable()`.
- `createHologram(location, lines)` — allocates entity IDs for each line, returns a `Hologram` object
- `spawn/despawn(viewers, hologram)` — sends spawn/destroy packets for all armor stands
- `updateLine/updateLines(viewers, hologram, ...)` — updates custom name metadata without respawn
- `teleport(viewers, hologram, destination)` — sends entity teleport packets preserving line spacing

- `BBAIPictureService` / `PictureService` (in `entity/picture/`) — packet-based image display via invisible item frames with filled maps. Each picture is a grid of `width × height` tiles, where each tile is a 128×128 map. Version-dependent factories (resolved in `enable()`) handle item frame item metadata index (5–8), filled map item creation (legacy data / NBT / component), and face direction encoding (pre-1.13 vs 1.13+).
- `createPicture(width, height)` — allocates entity IDs and map IDs for the tile grid
- `spawn/despawn(viewers, picture, anchorX, anchorY, anchorZ, face, image)` — spawns/destroys invisible item frames and sends map data
- `update(viewers, picture, image)` — updates map pixel data without respawning frames
- `MapPalette` (`@UtilityClass`) — Minecraft map color palette (36 base colors × 4 shades = 144 indices, 1.8-compatible). Provides `matchColor(rgb)`, `imageToMapColors(image)`, `tileToMapColors(image, grid, tile)`, and `resizeImage()`.

**Messaging:** `MessageService` (in `message/`) implements `BBAIMessageService` by delegating to six micro-services via Lombok `@Delegate`: `ChatMicroService`, `BarMicroService`, `TitleMicroService`, `TabMicroService`, `NameMicroService`, `BoardMicroService`. Micro-services are **instantiated inside `MessageService.enable()`**, not the constructor — each one resolves its own `ServerVersion` via `plugin.getContext().getServerVersion()` in its constructor (safe at that point because the plugin context has been published) and resolves its version-dependent packet factories once, so there are no runtime version checks in hot paths.

**Data service:** `DataService` (in `data/`) implements `BBAIDataService` — persistent storage for player statistics and arena stats. Supports two interchangeable backends configured via `data.provider` in `config.yml`:
- **Local** (`provider: local`) — JSON files in the `data/` directory via Gson + `ConcurrentHashMap`. Auto-save timer via `BukkitScheduler.runTaskTimerAsynchronously()`. Atomic writes (write `.tmp` then rename).
- **Ignite** (`provider: ignite`) — Apache Ignite 2.16.0 with three node modes: `server` (full node), `thick-client`, `thin-client`. Ignite manages its own threads; config in `data.ignite.*`; persistence data in `ignite/` directory.

The service is disabled entirely when `data.enabled: false` — all methods return defaults/no-ops and no files or connections are created. Internally delegates to a `DataProvider` SPI with generic `DataRepository<K,V>` instances (backed by `IgniteCache` or `ConcurrentHashMap`). Data models: `PlayerData` (keyed by UUID), `ArenaStats` (keyed by arena name) — both implement `Serializable` for Ignite and are Gson-friendly. ArenaStats is separate from ConfigService's arena YAML files (those store static structure; ArenaStats stores runtime statistics).

**ML service:** `MLService` (in `ml/`) implements `BBAIMLService` — REST proxy to an external FastAPI microservice for image classification. Blocking HTTP calls; must be invoked from async context. Includes an automatic fallback for offline/development play: when the microservice is unreachable, `predict`/`predictImage` return synthetic results with random scores from a built-in theme set (`cat`, `sword`, `ball`, `house`, `tree`, `glasses`) and zero-filled embeddings instead of throwing. `health()` and `centroids()` still throw on failure (no fallback).

**Utilities:**
- `EntityUtils` (`@UtilityClass`) — shared monotonic entity ID allocator for all packet-based entity services (NPCs, holograms, pictures). A single `AtomicInteger` counter guarantees no two synthetic entities (regardless of type) share an ID. All entity services must use `EntityUtils.nextEntityId()` instead of maintaining their own counters.
- `ReflectionUtils` — generic reflection helpers (`findField`, `findMethod`, `getFieldValue`, `setFieldValue`, `invokeMethod`, `invokeStaticMethod`) with hierarchy traversal, auto `setAccessible`, and unchecked exception wrapping. Used by `CommandService` for `CommandMap` access.
- `JarIntegrityVerifier` (`@UtilityClass`) — verifies JAR digital signatures at startup using `java.util.jar.JarFile` with verification enabled. Returns `true` for unsigned JARs (dev builds) and intact signed JARs; returns `false` if a signed JAR has been tampered with (broken digest or unsigned entries in a signed JAR). Called from `BuildBattleAI.onLoad()` before any other initialization.
- `SoundPalette` (`@UtilityClass`) — curated palette of cross-version sound effects (CONFIRM, CLICK, ERROR, SUCCESS, etc.) using `XSound` for UI feedback and game events. Used by `ArenaManager` for setup wizard interactions.

**World service:** `WorldService` (in `world/`) implements `BBAIWorldService` — dynamic void world management for arena isolation. Creates completely empty (air-only) worlds using a stateless `VoidChunkGenerator` that supports both the modern `ChunkData` API and the legacy `byte[]` API (pre-1.8 fallback). Operations: `createEmptyWorld(name)`, `loadWorld(name)`, `unloadWorld(world)`, `deleteWorld(world)`. All Bukkit world operations must be called from the main server thread. The service maintains a tracking set of world names it has created/loaded during the current lifecycle (cleared on shutdown). Worlds are not automatically unloaded on shutdown — cleanup is the caller's (arena manager) responsibility.

**Arena system:** `ArenaManager` (in `arena/`) implements `BBAIArenaManager` — full arena lifecycle: YAML loading with strict validation, interactive non-linear creation wizard, runtime state tracking, deletion with world cleanup. Key design points:
- **Non-linear setup wizard**: `/bbai create <name>` creates a void world (`bbai_<name>`) and shows a panel with ALL settings visible at once. The admin can fill them in any order, re-select values, and confirm when ready. Every setting change re-sends the full panel. The panel uses clickable `ChatMessage` segments that execute hidden `/bbai setup <action>` commands.
- **Arena data model** (`Arena` + `Arena.Position` + `Arena.PlotData` in `arena/api/`): Each arena has global settings (lobby, spectator, max/min players, build time, rounds) and per-plot settings (spawn, corner1, corner2, camera). `Position` wraps `double x,y,z` + `float yaw,pitch`.
- **Config validation**: `deserializeArena()` collects all missing required fields into a list, logs each as an ERROR, then skips the arena. Arenas with invalid configs are never activated.
- **Future-proofing**: Optional fields (`spectator`, `min-players`, `build-time`, `rounds`) have defaults so existing arena configs remain valid when new features use them.
- **Setup session** (`ArenaSetupSession`, package-private): Non-linear data bag — no step enum, all fields nullable until set. `isComplete()` checks that maxPlayers, lobby, and all per-plot fields (spawn, corner1, corner2, camera) are filled. `trimPlotsAbove(n)` discards excess plots when the player count is reduced.
- Arena worlds use the naming convention `bbai_<arena_name>`. On startup, enabled arenas try `loadWorld()` first, falling back to `createEmptyWorld()`.

**Logging:** `PluginLogger` (in `core/`) — configurable logging wrapper around Java's `Logger`. Four levels: `DEBUG`, `INFO`, `WARN`, `ERROR` (plus `OFF`). Supports `String.format`-style formatting and throwable logging. Uses pre-resolved action lambdas — level checks happen once when the level changes, not on every log call. Debug messages are emitted at Java `INFO` level with a `[DEBUG]` prefix (Bukkit consoles drop below `INFO`). Log level is configured via `log-level` in `config.yml`. Access via `plugin.getPluginLogger()`.

**Lifecycle coordinator:** `PluginContext` (in `core/`) owns all services. All services are constructed once in `PluginContext`'s constructor and reused across reload cycles. Each service implements the internal `PluginService` interface (`enable()`, `shutdown()`, default `reload()` = `shutdown` + `enable`). `PluginContext.enable()` calls `enable()` on every service in construction order (ConfigService first, then DataService → WorldService → ArenaManager → GameManager → MessageService → NPCService → HologramService → PictureService → MLService → RenderService → CommandService → ListenerService) and then performs business-layer bootstrap (command/listener registrations); `PluginContext.shutdown()` walks the list in reverse; `PluginContext.reload()` is a clean `shutdown` + `enable`. Invariant: after `shutdown` the plugin holds no runtime resources and has not disturbed any other plugin or the server; after a subsequent `enable` the plugin is running exactly as after a fresh server start. `PluginService` is intentionally NOT part of the public API — API interfaces (`BBAI*`) never expose lifecycle methods; service classes implement their API interface and `PluginService` separately. `PluginContext` also provides `sendPacket()` and `getUserProfile()` helpers for PacketEvents interaction.

**Package layout:**
- `core/` — `PluginContext`, `PluginService` (lifecycle contract), `PluginLogger` (configurable logging)
- `commands/` — `CommandService`, command implementations (`ArenaCommand`, `MLTestCommand`)
- `listeners/` — `ListenerService`, listener implementations (`ArenaSetupListener`, `GameListener`, `MLTestListener`)
- `render/` — `RenderService`, `CpuRenderer`, `BlockPalette`, `BlockShape`, `BlockRenderState`, `RendererUtils`; `render/data/` has `ChunkScene`, `FlatScene`, `SceneData`, `LegacyBlockStates`
- `message/` — `MessageService`, micro-services; `message/api/` has `BBAIMessageService`
- `entity/npc/` — `NPCService`; `entity/npc/api/` has `BBAINPCService`
- `entity/hologram/` — `HologramService`; `entity/hologram/api/` has `BBAIHologramService`
- `entity/picture/` — `PictureService`; `entity/picture/api/` has `BBAIPictureService`
- `data/` — `DataService`, `DataProvider`, `DataRepository`, `LocalDataProvider`, `LocalRepository`, `IgniteEmbeddedProvider`, `IgniteThinProvider`, `IgniteCacheRepository`, `IgniteClientCacheRepository`; `data/api/` has `BBAIDataService`, `PlayerData`, `ArenaStats`
- `ml/` — `MLService`; `ml/api/` has `BBAIMLService`
- `arena/` — `ArenaManager`, `ArenaSetupSession`; `arena/api/` has `BBAIArenaManager`, `Arena` (data model with `Position`, `PlotData`)
- `game/` — `GameManager`; `game/api/` has `BBAIGameManager`
- `player/` — player state management (stub)
- `world/` — `WorldService`; `world/api/` has `BBAIWorldService`
- `config/` — `ConfigService`, `Lang`; `config/api/` has `BBAIConfigService`, `Lang`
- `util/` — `ReflectionUtils`, `MessageUtils`, `EntityUtils`, `RendererUtils`, `MapPalette`, `JarIntegrityVerifier`, `SoundPalette`

**Threading rule:** Capture Bukkit world data only on the main thread. All render and palette logic must be async-safe.

## Code Quality Requirements

**Comments:** All new code must include professional English comments. Add Javadoc to every new public class, interface, method, and field. Use inline comments to explain non-obvious logic, algorithmic choices, edge cases, and threading considerations. Comments should explain *why*, not just *what*.

**Test coverage:** Every new feature, utility, or behavioral change must be accompanied by tests. Cover the happy path, edge cases, error conditions, and boundary values. For renderer/palette code that runs without Bukkit, write JUnit tests. For Bukkit-dependent code, add Mockito-based tests where feasible (see existing `commands/` and `listeners/` tests for patterns). If a class truly cannot be unit-tested without a live server, document that explicitly in the test class or in a `package-info.java`.

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
- **Service constructors must not call `plugin.getContext()`.** Services are instantiated inside `PluginContext`'s constructor, at which point `BuildBattleAI#context` is still `null`. Defer anything that needs the context (including `getServerVersion()`, `getUserProfile()`, `sendPacket()`) to `PluginService.enable()` — by the time it runs, the context reference has been published. Services that need to prepare version-dependent state (e.g. `RenderService.legacy` and its `CpuRenderer` instance, `NPCService`'s packet factories, `MessageService`'s sub-services) follow this pattern: keep the field non-final, resolve it in `enable()`.

## Testing Infrastructure

**Test stack:** JUnit Jupiter 5.10, Mockito 5.12, MockBukkit `mockbukkit-v1.21:4.50.0`, paper-api `1.21.5-R0.1-SNAPSHOT` (test scope only — main scope still uses Spigot API).

**Version pinning rationale (don't bump unilaterally):** MockBukkit ships a bundled registry-JSON snapshot tied to a specific paper-api version. `4.50.0` ↔ `paper-api 1.21.5` is the only stable pair we have. Newer MockBukkit (4.60+) targets paper-api 1.21.6+, which adds the `minecraft:dialog` registry — MockBukkit ≤ 4.60 fails to load it with `InternalDataLoadException`. Bumping either dependency requires bumping both in sync. The MockBukkit dependency MUST appear before paper-api/spigot-api in `pom.xml` so its paper-api wins on the test classpath (per MockBukkit README).

**Choosing the right test tool:**
- **Plain Mockito** for listener handlers with pure delegation (`GameListenerTest`, `ArenaSetupListenerTest`): mock `BuildBattleAI`, stub the `getContext()` chain, call the `@EventHandler` method directly with a mock event. Fast and isolated.
- **MockBukkit** when the production path touches Bukkit registries — anything that calls `Bukkit.getItemFactory()`, `Bukkit.getPlayer(uuid)`, `Bukkit.getWorld(name)`, or constructs an `ItemStack` whose `getItemMeta()` is read back. Paper-api 1.21.5's `ItemStack.getItemMeta()` is effectively final (factory-delegating) and cannot be Mockito-stubbed.
- **`MockedStatic`** for code paths that resolve packet factories or static Bukkit/PacketEvents accessors during `enable()` — see `CommandServiceTest`, `ListenerServiceTest`.
- **`MockedConstruction` (chained)** for structural lifecycle tests of `PluginContext.enable/shutdown/reload` — intercepts every `new XService(plugin)` so order/getter contracts can be verified on mocks without bootstrapping PacketEvents (`PluginContextLifecycleTest`).

**MockBukkit traps to know:**
- **`PlayerMock.playSound(...)` throws `UnimplementedOperationException`** for most overloads, including the 1.19+ seed-bearing variants. If the production code triggers `SoundPalette.*.play(player)` or any `XSound#play`, subclass `PlayerMock` and override **all eight** `playSound` overloads (`{Location|Entity} × {Sound|String} × {without|with SoundCategory+seed}`). Reference: `MLTestCommandTest.SilentPlayerMock`.
- **No worlds by default.** `Bukkit.getWorlds().get(0)` throws on an empty `ServerMock` — if the code under test falls back to it (`ArenaManager.returnPlayer`), call `server.addSimpleWorld(name)` in `@BeforeEach`.
- **`XSound.SoundPlayer.play` reaches into `Bukkit.getPlayer(uuid)`** — any code that emits a curated sound via `SoundPalette` requires MockBukkit (or a `MockedStatic<Bukkit>`).
- **`Bukkit.getUnsafe().getMainLevelName()`** is invoked by paper-api's `WorldCreator`. If you mock `Bukkit` statically while exercising `WorldService`, stub `getUnsafe()` too.
- **Static listener state survives across tests in the same JVM** (e.g. `MLTestListener.SELECTIONS`). Wipe it via reflection in `@BeforeEach`/`@AfterEach`; do not add production-only reset hooks. Pattern:
  ```java
  @SuppressWarnings("unchecked")
  private static void clearSelectionState() {
      try {
          Field f = MLTestListener.class.getDeclaredField("SELECTIONS");
          f.setAccessible(true);
          ((Map<UUID, ?>) f.get(null)).clear();
      } catch (Exception e) { throw new IllegalStateException(e); }
  }
  ```

**Mirror layout:** Tests live in `src/test/java/ru/ashesha/buildBattleAI/<same-package>/`. The `test/.../mockbukkit/` package holds smoke tests that verify MockBukkit itself behaves as expected after a version bump — keep at least one canary test there so a broken pair surfaces immediately on `mvn test`.

## Testing Gotchas

- `XMaterial.AIR.ordinal()` is **not** 0 (ordinal 0 is `ACACIA_BOAT`). When creating `FlatScene` test data, always fill arrays with `(short) XMaterial.AIR.ordinal()` — do not rely on default zero-initialization.
- `FlatScene.BlockDataSnapshot` inherits `@Accessors(fluent = true)` from the enclosing `FlatScene` class — use `snapshot.material()`, not `snapshot.getMaterial()`.
- Tests for `BlockPalette`, `BlockShape`, `BlockRenderState`, `CpuRenderer`, `RendererUtils`, and `FlatScene` run without a Bukkit server. `MessageService` and `ChunkScene` still require a live server (tested manually). Commands and listeners are now unit-tested via the MockBukkit/Mockito hybrid described in the Testing Infrastructure section.
- `CpuRendererTest` creates a shared `CpuRenderer` instance in `@BeforeAll` and shuts it down in `@AfterAll` to avoid pool creation overhead per test. Follow this pattern when adding renderer tests.
- `CommandServiceTest` uses `MockedStatic<Bukkit>` + `MockedStatic<ReflectionUtils>` inside a try-with-resources `@BeforeEach` just long enough to run `enable()` — once `enable()` returns, the resolved `CommandMap`/`knownCommands` references are captured on the service instance and the statics are closed. `ListenerServiceTest` uses `MockedStatic<PacketEvents>` per-test to mock the event manager. Follow these patterns when adding tests for service-level code.
- `ArenaManagerTest` mocks `PluginContext`, `BBAIConfigService`, and `BBAIWorldService` to test arena loading, validation, and deletion without a live server. The `buildValidArenaConfig()` helper produces a complete YAML with all required fields (lobby, per-plot spawn/corners/camera/picture). Use it as a template when adding arena-related tests.
- `ArenaSetupWizardTest` covers the interactive setup flow (start/handleSet*/handleConfirm/handleCancel + per-plot picture-geometry transitions) by combining MockBukkit (for the wand `ItemStack` round-trip and `Bukkit.getPlayer`) with mocked services. `ArenaSetupSessionTest` exercises the pure session-state logic (`isComplete`, geometry classification, `isFaceAllowed`, `trimPlotsAbove`) without any Bukkit primitives.
- `PluginContextLifecycleTest` uses **18 simultaneously-active `MockedConstruction` blocks** (one per service + command + listener) to intercept every `new XService(plugin)` inside `PluginContext`'s constructor. This lets us verify `enable()`/`shutdown()` ordering and `reload()` semantics without bootstrapping PacketEvents. If you add a new service, update the construction order assertion in this test — order is part of the lifecycle contract.

## Obfuscation Awareness

ProGuard keep rules in `proguard/base.pro` protect classes that must not be renamed. When adding new code, ensure these patterns are followed so the obfuscated build doesn't break:
- **New API interfaces** in `**/api/` packages are auto-kept by the `**.api.**` wildcard rule.
- **New Serializable classes** (Gson/Ignite) must have their fields kept — the existing `Serializable` rule covers `serialVersionUID` and read/write methods, but if field names matter for JSON, add explicit `-keepclassmembers` in `base.pro`.
- **New Bukkit listeners/commands** are auto-kept via the `implements Listener` / `extends Command` rules.
- **New `@EventHandler` methods** are auto-kept by the annotation rule.
- **New utility classes accessed by name** (e.g. via reflection strings) need explicit `-keep` entries in `base.pro`.
- **Shaded libraries** (`libs.**`, `org.apache.ignite.**`, `javax.cache.**`) are fully kept and must not be touched.

ProGuard config files live in `proguard/`: `base.pro` (shared keep rules), `light.pro`, `standard.pro`, `heavy.pro` (level-specific options). All levels use rename-only obfuscation (`-dontshrink -dontoptimize`) because the shaded JAR's incomplete class hierarchy (many provided-scope deps) prevents ProGuard from safely shrinking or optimizing.

## Dependencies (provided scope = server supplies them)

- **Spigot API 1.21.x** (provided) — server runtime
- **PacketEvents 2.x** (shaded) — packet-level interaction, relocated to `ru.ashesha.buildBattleAI.libs.packetevents`
- **XSeries** (shaded) — cross-version material abstraction, relocated to `ru.ashesha.buildBattleAI.libs.xseries`
- **Adventure / Kyori** (shaded) — component text serialization, relocated to `ru.ashesha.buildBattleAI.libs.kyori`
- **Apache Ignite 2.16.0** (shaded, NOT relocated) — distributed data grid for clustered storage; relocation is not used because Ignite relies on ServiceLoader, JMX, and reflection on its own class names. Zero Ignite classes are loaded when `data.provider` is `local`.
- **Lombok** (compile-time only) — annotation processing for boilerplate reduction
- **JUnit Jupiter 5.10** (test scope) — unit testing
- **Mockito 5.12** (test scope) — mocking for command/listener tests (requires `-Dnet.bytebuddy.experimental=true`, already configured in surefire)
- **MockBukkit `mockbukkit-v1.21:4.50.0`** (test scope) — fake Bukkit server for tests that need real `ItemFactory`, `PlayerInventory`, world registries. Version pinned (see Testing Infrastructure above).
- **paper-api `1.21.5-R0.1-SNAPSHOT`** (test scope, listed BEFORE the spigot-api `provided` entry) — pulled in for MockBukkit; must match MockBukkit's bundled registry version.
