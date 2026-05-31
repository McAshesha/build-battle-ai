package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Output of the render stage: a fully-rendered RGB byte buffer ready
 * for ML inference, along with its originating job (so the ML stage
 * can route scores back to the right player).
 * <p>
 * Producers must allocate a fresh {@code rgb} buffer per frame; see the
 * field Javadoc for the ownership contract.
 */
@Value
@Accessors(fluent = true)
class EvalFrame {

    @NonNull EvalJob job;
    /**
     * Row-major 224×224 RGB, layout matching {@code MLService.predictRgb}.
     * <p>
     * <b>Buffer ownership:</b> producers (render workers) MUST hand off a
     * freshly-allocated buffer. The buffer must not be reused or mutated
     * after the {@code EvalFrame} is constructed — it travels through the
     * {@link MlQueue} and is read by the ML coalescer at an unknown later
     * point, potentially as part of a multi-frame batch.
     */
    byte @NonNull [] rgb;
    long renderedAtNanos;
}
