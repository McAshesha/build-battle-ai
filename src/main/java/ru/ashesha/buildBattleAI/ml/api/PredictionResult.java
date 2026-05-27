package ru.ashesha.buildBattleAI.ml.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * Result of a {@link BBAIMLService} prediction call.
 * <p>
 * Bundles the raw L2-normalized image embedding together with the nearest
 * class centroid (the predicted class) and a ranked list of the top-K closest
 * classes by cosine similarity.
 * <p>
 * The caller is responsible for ensuring {@code topK} is immutable and that
 * {@code embedding} / {@code predictedCentroid} are not shared with another
 * aliased reference.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public final class PredictionResult {

    /**
     * L2-normalized embedding vector produced by the model for the input image.
     * Length equals {@link BBAIMLService#embeddingDim()}.
     */
    private final float[] embedding;

    /** Top-1 predicted class — the class whose centroid is closest to the embedding. */
    private final String predictedClass;

    /** Cosine similarity between the embedding and the top-1 class centroid. */
    private final float predictedScore;

    /** L2-normalized centroid of the top-1 predicted class. */
    private final float[] predictedCentroid;

    /** Ranked top-K candidate classes (descending similarity). */
    private final List<TopKEntry> topK;
}
