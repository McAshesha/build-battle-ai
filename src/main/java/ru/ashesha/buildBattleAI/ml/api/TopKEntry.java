package ru.ashesha.buildBattleAI.ml.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * A single entry in the top-K nearest-centroid ranking produced by the
 * {@link BBAIMLService}. Each entry pairs a candidate class name with the
 * cosine similarity score between the input embedding and that class centroid.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public final class TopKEntry {

    /** Class name for this candidate. */
    private final String className;

    /**
     * Cosine similarity between the input image embedding and this class
     * centroid. Range is {@code [-1, 1]}; higher means more similar.
     */
    private final float score;
}
