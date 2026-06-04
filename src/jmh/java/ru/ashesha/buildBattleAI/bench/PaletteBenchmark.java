package ru.ashesha.buildBattleAI.bench;

import com.cryptomorin.xseries.XMaterial;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import ru.ashesha.buildBattleAI.render.BlockPalette;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for the {@link BlockPalette} hot-path lookups invoked once
 * per ray hit in the renderer. The palette is called millions of times per
 * frame, so any regression in array-indexed lookup latency directly inflates
 * frame time and EvaluationService queue depth.
 * <p>
 * Run via the {@code -Pbench} Maven profile; see {@link RendererBenchmark}
 * for full instructions.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1)
@State(Scope.Benchmark)
public class PaletteBenchmark {

    /** Materials drawn from in a round-robin fashion to defeat branch prediction. */
    private XMaterial[] sample;
    private int cursor;

    @Setup
    public void setUp() {
        // Mix of opaque, translucent, emissive, face-dependent, and rare blocks.
        // Hardcoded order keeps the benchmark deterministic across runs.
        sample = new XMaterial[]{
                XMaterial.STONE,
                XMaterial.OAK_PLANKS,
                XMaterial.GRASS_BLOCK,
                XMaterial.GLASS,
                XMaterial.WATER,
                XMaterial.GLOWSTONE,
                XMaterial.OAK_STAIRS,
                XMaterial.OAK_SLAB,
                XMaterial.DIAMOND_BLOCK,
                XMaterial.GREEN_WOOL,
                XMaterial.CHERRY_LOG,
                XMaterial.CHERRY_LEAVES,
                XMaterial.IRON_BARS,
                XMaterial.OAK_FENCE,
                XMaterial.COBBLESTONE_WALL,
                XMaterial.AIR
        };
        cursor = 0;
    }

    /** Hot path: simple material-keyed RGB lookup. */
    @Benchmark
    public int getColorRoundRobin() {
        int c = BlockPalette.getColor(sample[cursor]);
        cursor = (cursor + 1) & 15;
        return c;
    }

    /** Hot path: alpha probe — runs alongside getColor() on every ray hit. */
    @Benchmark
    public int getAlphaRoundRobin() {
        int a = BlockPalette.getAlpha(sample[cursor]);
        cursor = (cursor + 1) & 15;
        return a;
    }

    /** Predicate hot path used during scene-mirror writes. */
    @Benchmark
    public void needsBlockStateRoundRobin(Blackhole bh) {
        bh.consume(BlockPalette.needsBlockState(sample[cursor]));
        cursor = (cursor + 1) & 15;
    }
}
