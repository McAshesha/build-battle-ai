package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Output of the render stage: a fully-rendered RGB byte buffer ready
 * for ML inference, along with its originating job (so the ML stage
 * can route scores back to the right player).
 */
@Value
@Accessors(fluent = true)
class EvalFrame {

    @NonNull EvalJob job;
    /** Row-major 224x224 RGB, layout matching {@code MLService.predictRgb}. */
    byte @NonNull [] rgb;
    long renderedAtNanos;
}
