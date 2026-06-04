# BuildBattleAI

A Spigot plugin for Minecraft — a Build Battle variant where builds are judged by an AI classifier instead of player voting.

## How it works

1. Players build to a given theme inside dedicated arena plots.
2. A built-in CPU renderer captures the build as a 224×224 RGB image — model-ready straight out of the camera.
3. An ONNX classifier scores the rendered frame; if the theme lands in the top-K predictions, the player scores a point and a new theme is assigned.

## Renderer

The plugin's core is a CPU-only voxel ray caster written in pure Java:
- **DDA traversal** through voxels with face-hit tracking
- **Ambient Occlusion** — quadrant-sampled corner neighbours for soft shadows
- **Translucency** — front-to-back compositing through glass, water and ice
- **Sub-block shapes** — ray–AABB intersection for stairs, slabs, fences, trapdoors and 40+ other block types
- **Emissive blocks** — glowstone, lava and lanterns render at full brightness, bypassing face shading
- **Parallelism** via `ForkJoinPool` — recursive horizontal-strip subdivision

After a single main-thread chunk snapshot, the renderer is fully thread-safe and completely independent of the Bukkit API.

## ML inference

- Local, in-process **ONNX Runtime 1.21** — no external service, no HTTP
- Custom-trained **ConvNeXt-Tiny embedder** (~107 MiB), 128-dimensional L2-normalised embeddings against 15 theme centroids
- Backend probing on enable: **CoreML → CUDA → DirectML → ROCm → CPU**
- Optional 4-view TTA (resize, random crop, hflip, brightness jitter)

## Build

```bash
mvn clean package    # build the JAR + run unit tests
mvn test             # run tests only
```

Requires **Java 8** and **Maven 3.6+**.

## Testing

The default `mvn test` runs 12 700+ unit and integration tests (renderer, palette, evaluation queues, ML in disabled mode, MockBukkit scenarios). Heavy tests live in dedicated Maven profiles.

| Profile | What it does | Command |
|---|---|---|
| (default) | Unit tests + property-based palette + renderer golden snapshots + `plugin.yml` smoke | `mvn test` |
| `-Pe2e` | Spawns the real **Paper 1.8.8** (`Servers/1.8/`) and **Purpur 1.21.11** (`Servers/1.21/`) servers as subprocesses, waits for `Done (`, asserts the plugin enabled, sends `bbai list`, stops the server, asserts a clean exit | `mvn package && mvn test -Pe2e` |
| `-Pml-it` | Loads the real ConvNeXt-Tiny ONNX model from `models/`, runs one forward pass through ORT, asserts the embedding has shape `(1, 128)` and is non-zero | `mvn test -Pml-it` |
| `-Pbench` | Compiles JMH micro-benchmarks for the renderer and palette from `src/jmh/java/` | `mvn test-compile -Pbench`<br>then run via `java -cp ... org.openjdk.jmh.Main RendererBenchmark` |

### Renderer golden snapshots

`RendererGoldenSnapshotTest` renders a fixed set of scenes (empty sky, single stone block, hollow stone room, mixed-palette tower with an emissive block, stone wall) and compares the SHA-256 of the rendered RGB bytes against golden hashes stored under `src/test/resources/golden/renderer/`. If a golden is missing it is written on the spot (first-run blessing). On a mismatch the actual rendered image is dumped to `target/golden-actual/<name>.png` for visual diffing.

To re-bless after intentional renderer changes:

```bash
GOLDEN_UPDATE=1 mvn test -Dtest=RendererGoldenSnapshotTest
```

### End-to-end against real servers

The E2E driver lives under `src/test/java/ru/ashesha/buildBattleAI/e2e/`:

- `AbstractServerE2ETest` — shared logic: copies the fresh JAR from `target/` into `plugins/`, removes duplicates, forces EULA acceptance, launches `start.command`, tails stdout, sends commands via stdin, asserts a clean shutdown
- `Paper18E2ETest` — pinned to `Servers/1.8/` (Paper 1.8.8)
- `Purpur121E2ETest` — pinned to `Servers/1.21/` (Purpur 1.21.11)

Known cosmetic cross-version Bukkit warnings (e.g. `PlayerSwapHandItemsEvent` does not exist on 1.8) are tolerated — the plugin still loads and runs correctly; only the missing-event listener is skipped, which is the intended behaviour on the older API.

## Compatibility

- **Spigot / Paper 1.8 through 1.21.x** (cross-version via XSeries + PacketEvents abstractions)
- Java 8 source and target

## Dependencies

| Library | Purpose | Scope |
|---|---|---|
| Spigot API | Server API | provided |
| PacketEvents 2.x | Packet wiring | shaded |
| XSeries | Cross-version material / sound / particle abstractions | shaded |
| Adventure (Kyori) | Text component serialisation | shaded |
| ONNX Runtime 1.21 | ML inference (CPU + CoreML/CUDA/DirectML/ROCm probing) | compile |
| Apache Ignite 2.16 | Optional distributed data backend | compile (shaded into full JAR) |
| Lombok | Boilerplate generation | compile-only |
| JUnit 5 / Mockito / MockBukkit | Unit & integration tests | test |
| JMH 1.37 | Micro-benchmarks (under `-Pbench`) | test |
