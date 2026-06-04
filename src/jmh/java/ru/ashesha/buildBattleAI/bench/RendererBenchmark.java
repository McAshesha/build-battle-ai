package ru.ashesha.buildBattleAI.bench;

import com.cryptomorin.xseries.XMaterial;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import ru.ashesha.buildBattleAI.render.CpuRenderer;
import ru.ashesha.buildBattleAI.render.data.FlatScene;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * JMH micro-benchmark for the CPU voxel renderer.
 * <p>
 * Measures end-to-end frame time for {@link CpuRenderer#render(ru.ashesha.buildBattleAI.render.data.SceneData,
 * double, double, double, float, float)} across two realistic scene shapes:
 * an empty room (mostly sky / first-hit shading) and a fully populated stone
 * cube (worst-case ray traversal). The pooled internal {@link java.util.concurrent.ForkJoinPool}
 * is shared across iterations and torn down once at the end so we only
 * measure rendering, not pool churn.
 * <p>
 * Run with the {@code -Pbench} profile:
 * <pre>{@code
 *   mvn test-compile -Pbench
 *   mvn -Pbench exec:java
 * }</pre>
 * or directly via {@code java -jar target/buildbattleai-bench.jar RendererBenchmark}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms1G", "-Xmx2G"})
@State(Scope.Benchmark)
public class RendererBenchmark {

    /** Scene cube side; 16 ≈ a chunk, 32 ≈ a building. */
    @Param({"16", "32"})
    public int size;

    /** Selector for the scene fill — empty (mostly air) or dense (mostly stone). */
    @Param({"empty", "dense"})
    public String scene;

    private CpuRenderer renderer;
    private FlatScene flatScene;
    /** Reusable output buffer — 224×224×3 — avoids 150 KB allocation per benchmark op. */
    private byte[] outBuf;

    @Setup(Level.Trial)
    public void setUp() {
        renderer = new CpuRenderer();
        outBuf = new byte[224 * 224 * 3];
        short[] data = new short[size * size * size];
        if ("empty".equals(scene)) {
            Arrays.fill(data, (short) XMaterial.AIR.ordinal());
            // Single block in the middle so the renderer has *something* to hit
            data[(size / 2) * size * size + (size / 2) * size + (size / 2)] =
                    (short) XMaterial.STONE.ordinal();
        } else {
            Arrays.fill(data, (short) XMaterial.STONE.ordinal());
        }
        flatScene = new FlatScene(data, 0, 0, 0, size, size, size);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        renderer.shutdown();
    }

    /**
     * Allocating render path — measures both the renderer cost AND the
     * 150 KB byte[] allocation per frame. Closest to what the
     * EvaluationService coordinator actually does on each evaluation tick.
     */
    @Benchmark
    public void renderAllocating(Blackhole bh) {
        bh.consume(renderer.render(flatScene, size / 2.0, size / 2.0, 0.5, 0f, 0f));
    }

    /**
     * Buffered render path — reuses the same output array, isolating just
     * the rendering cost. Useful to confirm the renderer itself isn't the
     * dominant allocator in steady state.
     */
    @Benchmark
    public void renderBuffered(Blackhole bh) {
        bh.consume(renderer.render(flatScene, size / 2.0, size / 2.0, 0.5, 0f, 0f, outBuf));
    }
}
