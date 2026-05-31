package ru.ashesha.buildBattleAI.evaluation;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Immutable render-request descriptor produced by the coordinator and
 * consumed by render workers. Carries everything the worker needs to
 * render without touching Bukkit state again.
 * <p>
 * The stale flag is the only mutable bit: {@link #markStale()} lets the
 * {@code RenderQueue} dedup index invalidate older queued copies when a
 * newer request for the same player arrives.
 */
@Builder(access = AccessLevel.PACKAGE)
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class EvalJob {

    @NonNull private final String arenaName;
    @NonNull private final UUID playerId;
    @NonNull private final String playerName;
    private final int plotIndex;
    private final int themeIndex;
    @NonNull private final String expectedTheme;
    @NonNull private final MutablePlotScene mirror;
    private final double cameraX;
    private final double cameraY;
    private final double cameraZ;
    private final float cameraYaw;
    private final float cameraPitch;
    private final long enqueuedAtNanos;

    // Marked @Builder.Default so each new job starts with a fresh, non-stale flag
    // even if a caller never invokes the (auto-generated) builder setter.
    @Builder.Default
    private final AtomicBoolean stale = new AtomicBoolean(false);

    /** Marks this job as superseded; workers must skip stale jobs on dequeue. */
    public void markStale() {
        stale.set(true);
    }

    /** True if a newer job for the same player has been enqueued. */
    public boolean isStale() {
        return stale.get();
    }
}
