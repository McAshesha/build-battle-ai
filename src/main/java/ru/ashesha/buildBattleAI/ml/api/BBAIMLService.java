package ru.ashesha.buildBattleAI.ml.api;

import lombok.NonNull;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

/**
 * In-process image classification backed by ONNX Runtime.
 * <p>
 * The service runs an ImageNet-pretrained ResNet50 with the final
 * fully-connected layer removed. The model emits a 2048-dim L2-normalized
 * embedding for any input image, and {@link #predict predict()} classifies
 * the image by finding the nearest class centroid via cosine similarity.
 * <p>
 * The active execution backend (CoreML / CUDA / DirectML / CPU / ...) is
 * picked once at {@link ru.ashesha.buildBattleAI.core.PluginService#enable()
 * service enable} by probing providers in order of preference, so per-call
 * cost is just preprocessing + a single ONNX inference.
 * <p>
 * <b>Thread-safety:</b> All methods are safe to call from any thread. The
 * underlying ONNX session is thread-safe for concurrent inference; the
 * centroid table is mutated only during enable.
 * <p>
 * <b>Blocking:</b> All methods perform CPU/GPU work and block the calling
 * thread. Call from an async context to avoid stalling the main server tick.
 */
public interface BBAIMLService {

    // ── single-image vectorization ─────────────────────────────────────────

    /**
     * Computes the embedding for one already-decoded image.
     *
     * @param image any non-null {@link BufferedImage}; resized internally to
     *              the model's expected input resolution
     * @return a fresh L2-normalized embedding of length {@link #embeddingDim()}
     */
    @NonNull
    float[] embed(@NonNull BufferedImage image);

    /**
     * Computes the embedding for one encoded image (PNG / JPEG / any
     * {@link javax.imageio.ImageIO}-readable format).
     *
     * @param encodedImage the encoded image bytes
     * @return a fresh L2-normalized embedding of length {@link #embeddingDim()}
     */
    @NonNull
    float[] embed(@NonNull byte[] encodedImage);

    /**
     * Computes the embedding for one raw RGB pixel buffer (the format produced
     * by {@link ru.ashesha.buildBattleAI.render.CpuRenderer#render CpuRenderer.render()}).
     * The buffer is row-major, three bytes per pixel (R, G, B), values 0–255.
     *
     * @param rgbPixels row-major RGB byte array of length {@code width * height * 3}
     * @param width     image width in pixels
     * @param height    image height in pixels
     * @return a fresh L2-normalized embedding of length {@link #embeddingDim()}
     */
    @NonNull
    float[] embedRgb(@NonNull byte[] rgbPixels, int width, int height);

    // ── batch vectorization ────────────────────────────────────────────────

    /**
     * Batch variant of {@link #embed(BufferedImage)}. The whole batch is sent
     * to the model in one inference call so the per-image cost is amortized
     * over the batch dimension.
     *
     * @param images non-null, non-empty array of images
     * @return one row per input image; each row is an L2-normalized embedding
     */
    @NonNull
    float[][] embedBatch(@NonNull BufferedImage[] images);

    /**
     * Batch variant of {@link #embed(byte[])}.
     *
     * @param encodedImages non-null, non-empty array of encoded image buffers
     * @return one row per input image; each row is an L2-normalized embedding
     */
    @NonNull
    float[][] embedBatch(@NonNull byte[][] encodedImages);

    /**
     * Batch variant of {@link #embedRgb(byte[], int, int)}. All buffers in the
     * batch must share the same {@code width × height} dimensions.
     *
     * @param rgbBatch non-null, non-empty array of row-major RGB pixel buffers
     * @param width    image width in pixels (same for every batch entry)
     * @param height   image height in pixels (same for every batch entry)
     * @return one row per input image; each row is an L2-normalized embedding
     */
    @NonNull
    float[][] embedBatchRgb(@NonNull byte[][] rgbBatch, int width, int height);

    // ── single-image prediction ────────────────────────────────────────────

    /**
     * Embeds the image and returns the closest classes by cosine similarity
     * against the service's registered class centroids.
     *
     * @param image any non-null image
     * @param topK  number of top candidates to include in the ranking (clamped
     *              to {@code [1, classNames().size()]})
     * @return the prediction result including the embedding and top-K ranking
     */
    @NonNull
    PredictionResult predict(@NonNull BufferedImage image, int topK);

    /**
     * Encoded-image variant of {@link #predict(BufferedImage, int)}.
     *
     * @param encodedImage the encoded image bytes
     * @param topK         number of top candidates to include
     * @return the prediction result
     */
    @NonNull
    PredictionResult predict(@NonNull byte[] encodedImage, int topK);

    /**
     * Raw-RGB variant of {@link #predict(BufferedImage, int)}.
     *
     * @param rgbPixels row-major RGB byte array
     * @param width     image width in pixels
     * @param height    image height in pixels
     * @param topK      number of top candidates to include
     * @return the prediction result
     */
    @NonNull
    PredictionResult predictRgb(@NonNull byte[] rgbPixels, int width, int height, int topK);

    // ── batch prediction ───────────────────────────────────────────────────

    /**
     * Batch variant of {@link #predict(BufferedImage, int)}.
     *
     * @param images non-null, non-empty array of images
     * @param topK   number of top candidates per result
     * @return one prediction result per input image (same order as {@code images})
     */
    @NonNull
    PredictionResult[] predictBatch(@NonNull BufferedImage[] images, int topK);

    /**
     * Batch variant of {@link #predict(byte[], int)}.
     *
     * @param encodedImages non-null, non-empty array of encoded images
     * @param topK          number of top candidates per result
     * @return one prediction result per input image
     */
    @NonNull
    PredictionResult[] predictBatch(@NonNull byte[][] encodedImages, int topK);

    /**
     * Batch variant of {@link #predictRgb(byte[], int, int, int)}.
     *
     * @param rgbBatch non-null, non-empty array of row-major RGB pixel buffers
     * @param width    image width in pixels (same for every batch entry)
     * @param height   image height in pixels (same for every batch entry)
     * @param topK     number of top candidates per result
     * @return one prediction result per input image
     */
    @NonNull
    PredictionResult[] predictBatchRgb(@NonNull byte[][] rgbBatch, int width, int height, int topK);

    // ── metadata ───────────────────────────────────────────────────────────

    /**
     * Returns the ordered list of class names the service can predict.
     * Centroids are aligned with this list — the {@code i}-th name corresponds
     * to the {@code i}-th centroid.
     *
     * @return an unmodifiable view of all registered class names
     */
    @NonNull
    List<String> classNames();

    /**
     * Returns all class centroids as an unmodifiable name → vector map.
     * Each vector is the L2-normalized prototype embedding for its class
     * and has length {@link #embeddingDim()}.
     *
     * @return name → centroid mapping
     */
    @NonNull
    Map<String, float[]> centroids();

    /**
     * Returns the embedding dimensionality emitted by the loaded model.
     * For ResNet50 without the final FC layer this is {@code 2048}.
     *
     * @return the embedding vector length
     */
    int embeddingDim();

    /**
     * Returns the name of the ONNX execution provider currently in use, e.g.
     * {@code "CoreMLExecutionProvider"}, {@code "CUDAExecutionProvider"}, or
     * {@code "CPUExecutionProvider"}. Returns {@code "DISABLED"} if model
     * loading failed and the service is running in inert fallback mode.
     *
     * @return a stable identifier for the active inference backend
     */
    @NonNull
    String backend();
}
