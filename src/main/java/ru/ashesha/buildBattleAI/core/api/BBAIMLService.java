package ru.ashesha.buildBattleAI.core.api;

import lombok.NonNull;
import ru.ashesha.buildBattleAI.core.MLService;

import java.util.List;
import java.util.Map;

/**
 * Service for classifying rendered build images via a machine-learning model.
 * <p>
 * Currently backed by a remote FastAPI microservice (REST proxy). This interface
 * is designed to remain stable when the ML inference is eventually migrated to
 * a native Java implementation — only the underlying {@link MLService} will change.
 * <p>
 * <b>All methods perform blocking I/O.</b> Callers must invoke them from an async
 * context (e.g. {@code BukkitScheduler.runTaskAsynchronously}) to avoid stalling
 * the main server thread.
 */
public interface BBAIMLService {

    /**
     * Classifies a build image from raw RGB pixel data.
     * <p>
     * This is the primary classification method, designed for direct use with
     * {@link ru.ashesha.buildBattleAI.render.CpuRenderer#render CpuRenderer.render()}
     * output. Internally converts the raw pixels to PNG before sending to the
     * inference backend.
     *
     * @param rgbPixels row-major RGB byte array ({@code width * height * 3} bytes),
     *                  where each pixel is 3 consecutive unsigned bytes (R, G, B)
     * @param width     image width in pixels
     * @param height    image height in pixels
     * @param topK      number of top predictions to return (1–20)
     * @return the classification result with embedding, predicted class, and top-K scores
     * @throws RuntimeException if the inference request fails or returns an error
     */
    @NonNull
    MLService.PredictionResult predict(@NonNull byte[] rgbPixels, int width, int height, int topK);

    /**
     * Classifies a build image from encoded image bytes (PNG, JPEG, etc.).
     * <p>
     * Use this when the image is already encoded (e.g. read from a file).
     * The bytes are Base64-encoded and sent as a data URI to the inference backend.
     *
     * @param imageData encoded image bytes (PNG or JPEG)
     * @param topK      number of top predictions to return (1–20)
     * @return the classification result with embedding, predicted class, and top-K scores
     * @throws RuntimeException if the inference request fails or returns an error
     */
    @NonNull
    MLService.PredictionResult predictImage(@NonNull byte[] imageData, int topK);

    /**
     * Retrieves service health information and model metadata.
     * <p>
     * Useful for verifying that the ML backend is reachable and for inspecting
     * model configuration (embedding dimension, input size, available classes).
     *
     * @return health info including model name, device, and available classes
     * @throws RuntimeException if the health check request fails
     */
    @NonNull
    MLService.HealthInfo health();

    /**
     * Retrieves all class centroids (mean embeddings).
     * <p>
     * Each centroid is the L2-normalized mean of all prototype embeddings for that class.
     * Centroids can be used for offline similarity comparisons without a full predict call.
     *
     * @return map from class name to its 128-dimensional centroid embedding
     * @throws RuntimeException if the centroids request fails
     */
    @NonNull
    Map<String, float[]> centroids();

}
