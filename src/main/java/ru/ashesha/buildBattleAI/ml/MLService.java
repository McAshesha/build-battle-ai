package ru.ashesha.buildBattleAI.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;
import ru.ashesha.buildBattleAI.ml.api.PredictionResult;
import ru.ashesha.buildBattleAI.ml.api.TopKEntry;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Native ONNX Runtime implementation of {@link BBAIMLService}.
 * <p>
 * Loads a custom-trained ConvNeXt-Tiny embedder from the
 * {@code models/custom_convnext_embeddings.onnx} classpath resource. On
 * {@link #enable()} the service probes available execution providers in
 * descending order of preference — CoreML, CUDA, DirectML, ROCm, then CPU —
 * and keeps the first one that produces a session that successfully runs a
 * warm-up inference. The chosen backend is logged at INFO so deployment
 * operators can confirm which device is doing the work.
 * <p>
 * Class centroids are loaded from {@code models/centroids.json} (15 build
 * themes, 128-dim each, already L2-normalized). If the JSON resource is
 * missing the service falls back to a hardcoded class list with random unit
 * centroids so the rest of the plugin keeps running.
 * <p>
 * <b>TTA pipeline:</b> {@code *WithTTA} methods build {@link #TTA_VIEWS}
 * augmented views per input — matching the training-time augmentation
 * (Resize-246 → RandomCrop-224 → HFlip → optional rotation ±7° → ColorJitter
 * → ImageNet normalize) — and submit them as a single ONNX super-batch. The
 * resulting embeddings are summed and L2-normalized, giving a more stable
 * retrieval embedding at the cost of one wider inference. For batched TTA
 * calls, all {@code N × TTA_VIEWS} views go in a single super-batch so the
 * ONNX runtime only sees one {@code run()} call regardless of {@code N}.
 * <p>
 * <b>Thread-safety:</b> The session is shared across threads; ORT permits
 * concurrent {@code run()} calls on the same session. Each request allocates
 * its own input tensor and closes it before returning. TTA augmentation uses
 * {@link ThreadLocalRandom} so concurrent calls produce independent views
 * without contention.
 */
@RequiredArgsConstructor
public class MLService implements BBAIMLService, PluginService {

    // ── model + runtime constants ──────────────────────────────────────────

    /** Classpath path to the embedded ONNX model file. */
    private static final String MODEL_RESOURCE = "/models/custom_convnext_embeddings.onnx";

    /** Classpath path to the JSON-encoded class centroids file. */
    private static final String CENTROIDS_RESOURCE = "/models/centroids.json";

    /** Input edge length expected by the ConvNeXt-Tiny preprocessing pipeline. */
    private static final int INPUT_SIZE = 224;

    /**
     * Pre-resize edge length used by the TTA pipeline: source → 246×246, then
     * RandomCrop to 224×224. Mirrors the training-time {@code tta_tf}
     * augmentation in {@code train_pipeline.py}.
     */
    private static final int TTA_RESIZE_EDGE = 246;

    /** Margin between {@link #TTA_RESIZE_EDGE} and {@link #INPUT_SIZE}. */
    private static final int TTA_CROP_RANGE = TTA_RESIZE_EDGE - INPUT_SIZE; // 22

    /** Number of color channels (RGB). */
    private static final int CHANNELS = 3;

    /** Embedding dimensionality emitted by the custom ConvNeXt-Tiny export. */
    private static final int EMBEDDING_DIM = 128;

    /** Number of augmented views the TTA pipeline produces per input image. */
    private static final int TTA_VIEWS = 8;

    /**
     * ImageNet RGB channel means. Subtracted from the [0, 1]-normalized
     * float pixel values before being divided by {@link #IMAGENET_STD}.
     */
    private static final float[] IMAGENET_MEAN = {0.485f, 0.456f, 0.406f};

    /** ImageNet RGB channel standard deviations. */
    private static final float[] IMAGENET_STD = {0.229f, 0.224f, 0.225f};

    /** Probability that rotation is applied to any given augmented view. */
    private static final double TTA_ROTATE_PROB = 0.5;

    /** Maximum absolute rotation angle in degrees (uniform [-7°, +7°]). */
    private static final double TTA_ROTATE_MAX_DEG = 7.0;

    /** Magnitude of the color-jitter perturbation per channel (±15%). */
    private static final float TTA_JITTER_STRENGTH = 0.15f;

    /** Grayscale conversion coefficients matching PIL / torchvision (Rec. 601). */
    private static final float GRAY_R = 0.2989f;
    private static final float GRAY_G = 0.5870f;
    private static final float GRAY_B = 0.1140f;

    /**
     * Seed used for fallback random centroids when the JSON resource is
     * absent. Fixed so repeat runs see deterministic vectors — handy for
     * unit tests that exercise the disabled path.
     */
    private static final long FALLBACK_CENTROID_SEED = 0xBBA1L;

    /**
     * Hardcoded class list used when {@link #CENTROIDS_RESOURCE} cannot be
     * loaded. Mirrors the trained model's 15 themes (with {@code mansion}
     * removed) in the same order as the bundled JSON, so a fallback service
     * keeps the same ordinal mapping that a healthy service would expose.
     */
    private static final List<String> FALLBACK_CLASSES = Collections.unmodifiableList(Arrays.asList(
            "candles", "cube", "sword", "tree", "barrel",
            "castle", "castle_tower", "church", "default_house", "desert_modern_villa",
            "house_from_hell", "modern_house", "overgrown_house", "ship", "skyscraper"
    ));

    /**
     * Backend tag returned by {@link #backend()} when model loading failed and
     * the service is running in inert fallback mode.
     */
    private static final String BACKEND_DISABLED = "DISABLED";

    /** Ordered list of execution provider attempts. */
    private enum Backend {
        COREML("CoreMLExecutionProvider") {
            @Override
            void apply(SessionOptions opts) throws OrtException {
                // Flag 0 = run on CPU + ANE/GPU as available. We do not pass
                // ENABLE_ON_SUBGRAPHS / ONLY_ENABLE_DEVICE_WITH_ANE because the
                // public Java API on older ORT releases doesn't expose the
                // builder-style options.
                opts.addCoreML();
            }
        },
        CUDA("CUDAExecutionProvider") {
            @Override
            void apply(SessionOptions opts) throws OrtException {
                opts.addCUDA(0);
            }
        },
        DIRECTML("DmlExecutionProvider") {
            @Override
            void apply(SessionOptions opts) throws OrtException {
                opts.addDirectML(0);
            }
        },
        ROCM("ROCMExecutionProvider") {
            @Override
            void apply(SessionOptions opts) throws OrtException {
                opts.addROCM(0);
            }
        },
        CPU("CPUExecutionProvider") {
            @Override
            void apply(SessionOptions opts) {
                // CPU EP is registered last by default — nothing to do.
            }
        };

        final String label;

        Backend(String label) {
            this.label = label;
        }

        /**
         * Registers this execution provider on the given session options.
         * Throws if the provider is not bundled in the active ONNX Runtime
         * native library (e.g. CUDA on a CPU-only build).
         */
        abstract void apply(SessionOptions opts) throws OrtException;
    }

    // ── instance state ─────────────────────────────────────────────────────

    @NonNull
    private final BuildBattleAI plugin;

    /** ORT environment — shared singleton. Created lazily on first enable(). */
    private OrtEnvironment env;

    /** Active inference session. {@code null} when the service is disabled. */
    private OrtSession session;

    /**
     * Input tensor name read out of the loaded session metadata so we don't
     * hard-code "input" and break if the export pipeline ever renames it.
     */
    private String inputName;

    /** Output tensor name; same robustness rationale as {@link #inputName}. */
    private String outputName;

    /** Friendly tag for the active backend, returned via {@link #backend()}. */
    private String backendLabel;

    /** Ordered class names — index aligned with {@link #centroidVectors}. */
    private List<String> classNames;

    /** Row-aligned centroid matrix. Each row is L2-normalized. */
    private float[][] centroidVectors;

    /** Cached unmodifiable view of {@link #classNames} for callers. */
    private List<String> classNamesView;

    /** Cached unmodifiable name → centroid map for {@link #centroids()}. */
    private Map<String, float[]> centroidsView;

    // ── lifecycle ──────────────────────────────────────────────────────────

    /**
     * Brings the ML pipeline online: loads the ONNX model bytes, walks through
     * available execution providers until one succeeds, runs a single warm-up
     * inference to confirm the session works, then loads the centroid table
     * from JSON (falling back to hardcoded classes if the resource is
     * missing). Failures are non-fatal — the service stays alive in
     * "disabled" mode and every inference call short-circuits to a zero
     * embedding so the rest of the plugin keeps running.
     */
    @Override
    public void enable() {
        long start = System.currentTimeMillis();
        try {
            env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "BuildBattleAI");
        } catch (Throwable t) {
            // Some old ORT releases reject re-initializing the environment with
            // a different name; fall back to the default singleton.
            env = OrtEnvironment.getEnvironment();
        }

        byte[] modelBytes = loadModelBytes();
        if (modelBytes == null) {
            initDisabled("model resource not found on classpath");
            return;
        }

        // Probe providers in preference order; first one that yields a working
        // session wins.
        for (Backend backend : Backend.values()) {
            OrtSession candidate = tryOpenSession(modelBytes, backend);
            if (candidate == null)
                continue;
            session = candidate;
            backendLabel = backend.label;
            break;
        }

        if (session == null) {
            initDisabled("no execution provider could load the model");
            return;
        }

        try {
            // ORT 1.20 changed these getters to declare no checked exceptions;
            // older releases threw OrtException. Catch Throwable to stay
            // compatible across versions without depending on the signature.
            inputName = session.getInputNames().iterator().next();
            outputName = session.getOutputNames().iterator().next();
        } catch (Throwable e) {
            plugin.getPluginLogger().warn("Failed to read ONNX I/O names: %s", e.getMessage());
            initDisabled("failed to read I/O names");
            return;
        }

        // Load real centroids from the bundled JSON; fall back to randoms if
        // the file is missing or malformed.
        if (!loadCentroidsFromJson())
            initFallbackCentroids();

        long elapsed = System.currentTimeMillis() - start;
        plugin.getPluginLogger().info(
                "MLService online — backend=%s, embeddingDim=%d, classes=%d, ttaViews=%d, init=%dms.",
                backendLabel, EMBEDDING_DIM, classNames.size(), TTA_VIEWS, elapsed);
    }

    /**
     * Releases the ONNX session. The shared {@link OrtEnvironment} is left
     * alive — it is process-global and closing it from one service can break
     * any other instance that might be in flight (e.g. during a hot reload).
     */
    @Override
    public void shutdown() {
        if (session != null) {
            try {
                session.close();
            } catch (Throwable t) {
                plugin.getPluginLogger().warn("Error closing ONNX session: %s", t.getMessage());
            }
            session = null;
        }
        backendLabel = null;
        classNames = null;
        classNamesView = null;
        centroidVectors = null;
        centroidsView = null;
        inputName = null;
        outputName = null;
        plugin.getPluginLogger().debug("MLService shut down.");
    }

    // ── BBAIMLService — single-image vectorization ─────────────────────────

    @Override
    public float @NonNull [] embed(@NonNull BufferedImage image) {
        float[] input = preprocess(image);
        return runSingle(input);
    }

    @Override
    public float @NonNull [] embed(byte @NonNull [] encodedImage) {
        return embed(decode(encodedImage));
    }

    @Override
    public float @NonNull [] embedRgb(byte @NonNull [] rgbPixels, int width, int height) {
        float[] input = preprocessRgb(rgbPixels, width, height);
        return runSingle(input);
    }

    // ── BBAIMLService — batch vectorization ────────────────────────────────

    @Override
    public float @NonNull [][] embedBatch(@NonNull BufferedImage[] images) {
        if (images.length == 0)
            return new float[0][];
        float[][] inputs = new float[images.length][];
        for (int i = 0; i < images.length; i++)
            inputs[i] = preprocess(images[i]);
        return runBatch(inputs);
    }

    @Override
    public float @NonNull [][] embedBatch(byte @NonNull [][] encodedImages) {
        if (encodedImages.length == 0)
            return new float[0][];
        BufferedImage[] decoded = new BufferedImage[encodedImages.length];
        for (int i = 0; i < encodedImages.length; i++)
            decoded[i] = decode(encodedImages[i]);
        return embedBatch(decoded);
    }

    @Override
    public float @NonNull [][] embedBatchRgb(byte @NonNull [][] rgbBatch, int width, int height) {
        if (rgbBatch.length == 0)
            return new float[0][];
        float[][] inputs = new float[rgbBatch.length][];
        for (int i = 0; i < rgbBatch.length; i++)
            inputs[i] = preprocessRgb(rgbBatch[i], width, height);
        return runBatch(inputs);
    }

    // ── BBAIMLService — single-image prediction ────────────────────────────

    @Override
    @NonNull
    public PredictionResult predict(@NonNull BufferedImage image, int topK) {
        return classify(embed(image), topK);
    }

    @Override
    @NonNull
    public PredictionResult predict(byte @NonNull [] encodedImage, int topK) {
        return classify(embed(encodedImage), topK);
    }

    @Override
    @NonNull
    public PredictionResult predictRgb(byte @NonNull [] rgbPixels, int width, int height, int topK) {
        return classify(embedRgb(rgbPixels, width, height), topK);
    }

    // ── BBAIMLService — batch prediction ───────────────────────────────────

    @Override
    public PredictionResult @NonNull [] predictBatch(@NonNull BufferedImage[] images, int topK) {
        float[][] embeddings = embedBatch(images);
        return classifyBatch(embeddings, topK);
    }

    @Override
    public PredictionResult @NonNull [] predictBatch(byte @NonNull [][] encodedImages, int topK) {
        float[][] embeddings = embedBatch(encodedImages);
        return classifyBatch(embeddings, topK);
    }

    @Override
    public PredictionResult @NonNull [] predictBatchRgb(byte @NonNull [][] rgbBatch, int width, int height, int topK) {
        float[][] embeddings = embedBatchRgb(rgbBatch, width, height);
        return classifyBatch(embeddings, topK);
    }

    // ── BBAIMLService — TTA single-image vectorization ─────────────────────

    @Override
    public float @NonNull [] embedWithTTA(@NonNull BufferedImage image) {
        // Decode once into raw RGB, then run the native TTA pipeline.
        byte[] rgb = bufferedImageToRgb(image);
        return embedWithTTA(rgb, image.getWidth(), image.getHeight());
    }

    @Override
    public float @NonNull [] embedWithTTA(byte @NonNull [] encodedImage) {
        // ImageIO.read is the one decode we cannot avoid; everything downstream
        // works on the raw RGB buffer with zero extra allocations of
        // BufferedImage / ARGB pixels.
        BufferedImage decoded = decode(encodedImage);
        byte[] rgb = bufferedImageToRgb(decoded);
        return embedWithTTA(rgb, decoded.getWidth(), decoded.getHeight());
    }

    @Override
    public float @NonNull [] embedWithTTA(byte @NonNull [] rgbPixels, int width, int height) {
        validateRgbBuffer(rgbPixels, width, height);
        // 1) One bilinear resize to 246×246 — shared across all TTA views so
        //    we don't pay the resize cost 8 times.
        byte[] resized = bilinearResizeRgb(rgbPixels, width, height, TTA_RESIZE_EDGE, TTA_RESIZE_EDGE);
        // 2) Build TTA_VIEWS independent augmented views (each a normalized
        //    CHW float tensor).
        float[][] views = buildTtaViews(resized);
        // 3) One batched inference for all views.
        float[][] embeddings = runBatch(views);
        // 4) Sum embeddings, L2-normalize → fused query vector.
        return fuseEmbeddings(embeddings);
    }

    // ── BBAIMLService — TTA batch vectorization ────────────────────────────

    @Override
    public float @NonNull [][] embedBatchWithTTA(@NonNull BufferedImage[] images) {
        if (images.length == 0)
            return new float[0][];
        // Convert each image to raw RGB once. We can't assume all images share
        // dimensions, so we hold per-image (rgb, w, h) tuples.
        byte[][] rgbBuffers = new byte[images.length][];
        int[] widths = new int[images.length];
        int[] heights = new int[images.length];
        for (int i = 0; i < images.length; i++) {
            rgbBuffers[i] = bufferedImageToRgb(images[i]);
            widths[i] = images[i].getWidth();
            heights[i] = images[i].getHeight();
        }
        return embedBatchWithTtaMixed(rgbBuffers, widths, heights);
    }

    @Override
    public float @NonNull [][] embedBatchWithTTA(byte @NonNull [][] encodedImages) {
        if (encodedImages.length == 0)
            return new float[0][];
        byte[][] rgbBuffers = new byte[encodedImages.length][];
        int[] widths = new int[encodedImages.length];
        int[] heights = new int[encodedImages.length];
        for (int i = 0; i < encodedImages.length; i++) {
            BufferedImage decoded = decode(encodedImages[i]);
            rgbBuffers[i] = bufferedImageToRgb(decoded);
            widths[i] = decoded.getWidth();
            heights[i] = decoded.getHeight();
        }
        return embedBatchWithTtaMixed(rgbBuffers, widths, heights);
    }

    @Override
    public float @NonNull [][] embedBatchWithTTA(byte @NonNull [][] rgbBatch, int width, int height) {
        if (rgbBatch.length == 0)
            return new float[0][];
        for (int i = 0; i < rgbBatch.length; i++)
            validateRgbBuffer(rgbBatch[i], width, height);
        // 1) Resize every input to 246×246 (shared optimisation across views).
        byte[][] resized = new byte[rgbBatch.length][];
        for (int i = 0; i < rgbBatch.length; i++)
            resized[i] = bilinearResizeRgb(rgbBatch[i], width, height, TTA_RESIZE_EDGE, TTA_RESIZE_EDGE);
        // 2) Build N × TTA_VIEWS augmented tensors and stuff them into a single
        //    super-batch.
        return runTtaSuperBatch(resized);
    }

    // ── BBAIMLService — TTA single-image prediction ────────────────────────

    @Override
    @NonNull
    public PredictionResult predictWithTTA(@NonNull BufferedImage image, int topK) {
        return classify(embedWithTTA(image), topK);
    }

    @Override
    @NonNull
    public PredictionResult predictWithTTA(byte @NonNull [] encodedImage, int topK) {
        return classify(embedWithTTA(encodedImage), topK);
    }

    @Override
    @NonNull
    public PredictionResult predictWithTTA(byte @NonNull [] rgbPixels, int width, int height, int topK) {
        return classify(embedWithTTA(rgbPixels, width, height), topK);
    }

    // ── BBAIMLService — TTA batch prediction ───────────────────────────────

    @Override
    public PredictionResult @NonNull [] predictBatchWithTTA(@NonNull BufferedImage[] images, int topK) {
        return classifyBatch(embedBatchWithTTA(images), topK);
    }

    @Override
    public PredictionResult @NonNull [] predictBatchWithTTA(byte @NonNull [][] encodedImages, int topK) {
        return classifyBatch(embedBatchWithTTA(encodedImages), topK);
    }

    @Override
    public PredictionResult @NonNull [] predictBatchWithTTA(byte @NonNull [][] rgbBatch, int width, int height, int topK) {
        return classifyBatch(embedBatchWithTTA(rgbBatch, width, height), topK);
    }

    // ── BBAIMLService — metadata ───────────────────────────────────────────

    @Override
    @NonNull
    public List<String> classNames() {
        return classNamesView != null ? classNamesView : Collections.<String>emptyList();
    }

    @Override
    @NonNull
    public Map<String, float[]> centroids() {
        return centroidsView != null ? centroidsView : Collections.<String, float[]>emptyMap();
    }

    @Override
    public int embeddingDim() {
        return EMBEDDING_DIM;
    }

    @Override
    public int ttaViews() {
        return TTA_VIEWS;
    }

    @Override
    @NonNull
    public String backend() {
        return backendLabel != null ? backendLabel : BACKEND_DISABLED;
    }

    // ── internal helpers — model loading ───────────────────────────────────

    /**
     * Reads the embedded ONNX model bytes from the classpath. Returns
     * {@code null} if the resource is missing (e.g. the JAR was built without
     * the model file) so the caller can fall back to disabled mode.
     */
    private byte[] loadModelBytes() {
        InputStream in = MLService.class.getResourceAsStream(MODEL_RESOURCE);
        if (in == null) {
            plugin.getPluginLogger().error("ONNX model resource not found at %s", MODEL_RESOURCE);
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, in.available()));
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1)
                out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            plugin.getPluginLogger().error("Failed to read ONNX model: %s", e.getMessage());
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Attempts to open a session with the given backend and warm it up with a
     * single inference. Returns the live session on success or {@code null} if
     * any step fails — the next backend will be tried.
     */
    private OrtSession tryOpenSession(byte[] modelBytes, Backend backend) {
        SessionOptions opts = null;
        OrtSession candidate = null;
        try {
            opts = new SessionOptions();
            // Optimize the graph as aggressively as possible — pays for itself
            // even on a single warm-up inference.
            opts.setOptimizationLevel(SessionOptions.OptLevel.ALL_OPT);
            backend.apply(opts);
            candidate = env.createSession(modelBytes, opts);

            // Warm-up: many EPs (especially CoreML) JIT-compile kernels on the
            // first run, so we pay that cost now and confirm the session works.
            float[] warmup = new float[CHANNELS * INPUT_SIZE * INPUT_SIZE];
            long[] shape = {1, CHANNELS, INPUT_SIZE, INPUT_SIZE};
            OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(warmup), shape);
            try {
                String firstInputName = candidate.getInputNames().iterator().next();
                Map<String, OnnxTensor> feed = Collections.singletonMap(firstInputName, input);
                OrtSession.Result result = candidate.run(feed);
                try {
                    // Touch the result so we know inference produced output.
                    result.iterator().hasNext();
                } finally {
                    result.close();
                }
            } finally {
                input.close();
            }

            plugin.getPluginLogger().debug("ONNX backend %s accepted the model.", backend.label);
            return candidate;
        } catch (Throwable t) {
            // Close partially-opened resources before trying the next backend.
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (Throwable ignored) {
                }
            }
            plugin.getPluginLogger().debug("Backend %s unavailable: %s", backend.label, t.getMessage());
            return null;
        } finally {
            if (opts != null) {
                try {
                    opts.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Switches the service into inert disabled mode. Inference calls keep
     * working but return zero embeddings and random rankings so callers don't
     * crash.
     *
     * @param reason short human-readable reason for the warning log
     */
    private void initDisabled(String reason) {
        plugin.getPluginLogger().warn("MLService disabled (%s) — predictions will be random.", reason);
        session = null;
        backendLabel = null;
        // Even in disabled mode we still want a centroid table and class names
        // so predict() returns deterministic shapes for callers. We prefer the
        // real bundled centroids when available so disabled-mode rankings are
        // at least over the right class space.
        if (!loadCentroidsFromJson())
            initFallbackCentroids();
    }

    // ── internal helpers — centroid table ──────────────────────────────────

    /**
     * Container for the JSON deserialization of the bundled centroids file.
     * The on-disk format is produced by {@code train_pipeline.py} and bundles
     * the centroid matrix together with the class list and pre-processing
     * metadata (which we ignore here — the model itself encodes that contract).
     */
    private static final class CentroidsBundle {
        List<String> classes;
        List<List<Double>> centroids;
        // The remaining fields (embedding_dim, image_size, normalize,
        // input_format, output_format) are intentionally not read — we already
        // hard-code our matching values as Java constants. Defining them here
        // would only invite drift.
    }

    /**
     * Populates {@link #classNames} / {@link #centroidVectors} from the
     * bundled {@code centroids.json} resource. Returns {@code true} on
     * success; {@code false} if the resource is missing, malformed, or shape-
     * mismatched, in which case the caller is expected to fall back to
     * synthetic centroids.
     */
    private boolean loadCentroidsFromJson() {
        InputStream in = MLService.class.getResourceAsStream(CENTROIDS_RESOURCE);
        if (in == null) {
            plugin.getPluginLogger().warn("Centroids resource not found at %s — using fallback centroids.",
                    CENTROIDS_RESOURCE);
            return false;
        }
        try {
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            Gson gson = new Gson();
            Type bundleType = new TypeToken<CentroidsBundle>() {
            }.getType();
            CentroidsBundle bundle = gson.fromJson(reader, bundleType);
            if (bundle == null || bundle.classes == null || bundle.centroids == null) {
                plugin.getPluginLogger().warn("Centroids JSON missing required fields — using fallback.");
                return false;
            }
            if (bundle.classes.size() != bundle.centroids.size()) {
                plugin.getPluginLogger().warn(
                        "Centroids JSON has %d classes but %d vectors — using fallback.",
                        bundle.classes.size(), bundle.centroids.size());
                return false;
            }

            float[][] vectors = new float[bundle.centroids.size()][];
            for (int i = 0; i < bundle.centroids.size(); i++) {
                List<Double> row = bundle.centroids.get(i);
                if (row.size() != EMBEDDING_DIM) {
                    plugin.getPluginLogger().warn(
                            "Centroid %d ('%s') has dim %d, expected %d — using fallback.",
                            i, bundle.classes.get(i), row.size(), EMBEDDING_DIM);
                    return false;
                }
                float[] v = new float[EMBEDDING_DIM];
                for (int j = 0; j < EMBEDDING_DIM; j++)
                    v[j] = row.get(j).floatValue();
                // Defensive re-normalisation: the on-disk vectors are unit-
                // norm already, but a small numerical drift after float-cast
                // is cheap to fix and prevents a subtle skew in cosine scores.
                l2Normalize(v);
                vectors[i] = v;
            }
            applyCentroids(new ArrayList<String>(bundle.classes), vectors);
            return true;
        } catch (Throwable t) {
            plugin.getPluginLogger().warn("Failed to parse centroids JSON: %s — using fallback.",
                    t.getMessage());
            return false;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Populates the centroid table with random L2-normalized vectors for the
     * hardcoded fallback class list. Used when the JSON resource cannot be
     * read so the rest of the plugin still has a well-shaped centroid map to
     * work against.
     */
    private void initFallbackCentroids() {
        Random rng = new Random(FALLBACK_CENTROID_SEED);
        float[][] vectors = new float[FALLBACK_CLASSES.size()][EMBEDDING_DIM];
        for (int c = 0; c < FALLBACK_CLASSES.size(); c++) {
            float[] v = vectors[c];
            for (int i = 0; i < EMBEDDING_DIM; i++)
                // Gaussian draws give a uniform distribution on the unit sphere
                // after L2 normalization — what cosine similarity expects.
                v[i] = (float) rng.nextGaussian();
            l2Normalize(v);
        }
        applyCentroids(new ArrayList<String>(FALLBACK_CLASSES), vectors);
    }

    /**
     * Atomically installs a new (name → centroid) table on the service. The
     * unmodifiable views are rebuilt eagerly so callers always observe a
     * consistent snapshot.
     */
    private void applyCentroids(List<String> names, float[][] vectors) {
        classNames = names;
        centroidVectors = vectors;
        classNamesView = Collections.unmodifiableList(classNames);
        LinkedHashMap<String, float[]> centroidMap = new LinkedHashMap<String, float[]>();
        for (int i = 0; i < classNames.size(); i++)
            centroidMap.put(classNames.get(i), centroidVectors[i]);
        centroidsView = Collections.unmodifiableMap(centroidMap);
    }

    // ── internal helpers — preprocessing (non-TTA path) ────────────────────

    /**
     * Resizes an arbitrary {@link BufferedImage} to {@link #INPUT_SIZE}² and
     * encodes it as a CHW float tensor using ImageNet normalization.
     */
    private static float[] preprocess(BufferedImage image) {
        BufferedImage resized = resizeTo(image, INPUT_SIZE, INPUT_SIZE);
        // ARGB pixel data — read via getRGB() for maximum compatibility (works
        // regardless of the source image's internal type).
        int[] argb = new int[INPUT_SIZE * INPUT_SIZE];
        resized.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, argb, 0, INPUT_SIZE);

        float[] out = new float[CHANNELS * INPUT_SIZE * INPUT_SIZE];
        int planeSize = INPUT_SIZE * INPUT_SIZE;
        for (int i = 0; i < planeSize; i++) {
            int p = argb[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            // CHW layout: out[c * H*W + i].
            out[i] = ((r / 255f) - IMAGENET_MEAN[0]) / IMAGENET_STD[0];
            out[planeSize + i] = ((g / 255f) - IMAGENET_MEAN[1]) / IMAGENET_STD[1];
            out[2 * planeSize + i] = ((b / 255f) - IMAGENET_MEAN[2]) / IMAGENET_STD[2];
        }
        return out;
    }

    /**
     * Specialized preprocessing for the raw RGB pixel layout produced by
     * {@link ru.ashesha.buildBattleAI.render.CpuRenderer}. If the buffer is
     * already 224×224 we skip the BufferedImage round-trip and convert
     * straight to the model input format — saving an allocation per frame in
     * the hot rendering loop.
     */
    private static float[] preprocessRgb(byte[] rgbPixels, int width, int height) {
        validateRgbBuffer(rgbPixels, width, height);
        if (width == INPUT_SIZE && height == INPUT_SIZE)
            return preprocessRgbExact(rgbPixels);

        // Off-size: resize natively to 224×224 then run the exact-size path.
        // Avoids the BufferedImage / getRGB / setRGB triple round-trip.
        byte[] resized = bilinearResizeRgb(rgbPixels, width, height, INPUT_SIZE, INPUT_SIZE);
        return preprocessRgbExact(resized);
    }

    /**
     * Fast path for the renderer's native 224×224 RGB output. Skips
     * BufferedImage / ARGB conversion entirely.
     */
    private static float[] preprocessRgbExact(byte[] rgbPixels) {
        int planeSize = INPUT_SIZE * INPUT_SIZE;
        float[] out = new float[CHANNELS * planeSize];
        for (int i = 0; i < planeSize; i++) {
            int idx = i * 3;
            int r = rgbPixels[idx] & 0xFF;
            int g = rgbPixels[idx + 1] & 0xFF;
            int b = rgbPixels[idx + 2] & 0xFF;
            out[i] = ((r / 255f) - IMAGENET_MEAN[0]) / IMAGENET_STD[0];
            out[planeSize + i] = ((g / 255f) - IMAGENET_MEAN[1]) / IMAGENET_STD[1];
            out[2 * planeSize + i] = ((b / 255f) - IMAGENET_MEAN[2]) / IMAGENET_STD[2];
        }
        return out;
    }

    /**
     * Scales an image to a target resolution using bilinear filtering. Always
     * returns a new {@code TYPE_INT_RGB} image — even if the source already
     * matches the target — so downstream {@code getRGB()} calls see a
     * consistent format.
     */
    private static BufferedImage resizeTo(BufferedImage source, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    /**
     * Decodes an encoded image buffer into a {@link BufferedImage}. Throws on
     * decoding failure with a clear message; the caller (a public service
     * method) wraps the throw with the request context.
     */
    private static BufferedImage decode(byte[] encoded) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(encoded));
            if (img == null)
                throw new IllegalArgumentException("ImageIO returned null — unsupported image format");
            return img;
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode image: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts a row-major RGB byte buffer from a {@link BufferedImage} in one
     * pass. This is the single bridge between the BufferedImage world and our
     * RGB-bytes pipeline — every native preprocessing step downstream works
     * directly on bytes.
     */
    private static byte[] bufferedImageToRgb(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] argb = new int[w * h];
        img.getRGB(0, 0, w, h, argb, 0, w);
        byte[] rgb = new byte[w * h * CHANNELS];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            int idx = i * 3;
            rgb[idx] = (byte) ((p >> 16) & 0xFF);
            rgb[idx + 1] = (byte) ((p >> 8) & 0xFF);
            rgb[idx + 2] = (byte) (p & 0xFF);
        }
        return rgb;
    }

    /**
     * Validates that a raw RGB buffer matches the declared {@code width *
     * height * 3} byte count. Throws an {@link IllegalArgumentException} with
     * a clear message on mismatch — caught upstream by the public methods.
     */
    private static void validateRgbBuffer(byte[] rgbPixels, int width, int height) {
        int expected = width * height * CHANNELS;
        if (rgbPixels.length != expected)
            throw new IllegalArgumentException(
                    "RGB buffer length " + rgbPixels.length
                            + " does not match width*height*3 (" + expected + ")");
    }

    // ── internal helpers — native bilinear resize on RGB bytes ─────────────

    /**
     * Bilinear resampling of a row-major RGB byte buffer. Matches the
     * "Resize" transform used during training (which calls into PIL's
     * {@code Image.BILINEAR}).
     * <p>
     * Sampling uses the "pixel-center" convention — the same one PIL,
     * torchvision and PyTorch's {@code grid_sample(align_corners=False)}
     * adopt: the source coordinate of an output pixel {@code k} is
     * {@code (k + 0.5) * src/dst - 0.5}. This avoids the off-by-half-pixel
     * skew you get with naïve {@code k * src/dst} sampling.
     *
     * @param src    row-major RGB source pixels of length {@code srcW * srcH * 3}
     * @param srcW   source width
     * @param srcH   source height
     * @param dstW   target width
     * @param dstH   target height
     * @return a freshly allocated {@code dstW * dstH * 3} RGB byte buffer
     */
    private static byte[] bilinearResizeRgb(byte[] src, int srcW, int srcH, int dstW, int dstH) {
        byte[] dst = new byte[dstW * dstH * CHANNELS];
        // Scale factors expressed as floats to keep the math readable; for
        // 246×246 → 224×224 the cost is dwarfed by inference anyway.
        float sx = (float) srcW / dstW;
        float sy = (float) srcH / dstH;
        for (int y = 0; y < dstH; y++) {
            float fy = (y + 0.5f) * sy - 0.5f;
            int y0 = (int) Math.floor(fy);
            int y1 = y0 + 1;
            float wy = fy - y0;
            if (y0 < 0) {
                y0 = 0;
                wy = 0f;
            }
            if (y1 > srcH - 1)
                y1 = srcH - 1;
            for (int x = 0; x < dstW; x++) {
                float fx = (x + 0.5f) * sx - 0.5f;
                int x0 = (int) Math.floor(fx);
                int x1 = x0 + 1;
                float wx = fx - x0;
                if (x0 < 0) {
                    x0 = 0;
                    wx = 0f;
                }
                if (x1 > srcW - 1)
                    x1 = srcW - 1;

                int idx00 = (y0 * srcW + x0) * 3;
                int idx01 = (y0 * srcW + x1) * 3;
                int idx10 = (y1 * srcW + x0) * 3;
                int idx11 = (y1 * srcW + x1) * 3;
                int dstIdx = (y * dstW + x) * 3;
                for (int c = 0; c < 3; c++) {
                    float c00 = src[idx00 + c] & 0xFF;
                    float c01 = src[idx01 + c] & 0xFF;
                    float c10 = src[idx10 + c] & 0xFF;
                    float c11 = src[idx11 + c] & 0xFF;
                    float top = c00 + (c01 - c00) * wx;
                    float bot = c10 + (c11 - c10) * wx;
                    float v = top + (bot - top) * wy;
                    // Add 0.5 before truncation for proper rounding.
                    int iv = (int) (v + 0.5f);
                    if (iv < 0)
                        iv = 0;
                    if (iv > 255)
                        iv = 255;
                    dst[dstIdx + c] = (byte) iv;
                }
            }
        }
        return dst;
    }

    // ── internal helpers — TTA augmentation pipeline ───────────────────────

    /**
     * Builds {@link #TTA_VIEWS} augmented CHW float tensors from a single
     * 246×246 RGB buffer. Each view is sampled independently with its own
     * random crop / flip / rotation / colour-jitter parameters drawn from
     * {@link ThreadLocalRandom}.
     */
    private static float[][] buildTtaViews(byte[] resized) {
        float[][] views = new float[TTA_VIEWS][];
        Random rng = ThreadLocalRandom.current();
        for (int i = 0; i < TTA_VIEWS; i++)
            views[i] = buildOneTtaView(resized, rng);
        return views;
    }

    /**
     * Generates a single augmented view: sample → optional rotate → optional
     * h-flip → colour-jitter → ImageNet normalize → CHW. Returns a flat float
     * array of length {@code 3 * INPUT_SIZE * INPUT_SIZE}.
     */
    private static float[] buildOneTtaView(byte[] resized, Random rng) {
        // Random crop offset within the 246×246 → 224×224 margin (0..22).
        int cropX = rng.nextInt(TTA_CROP_RANGE + 1);
        int cropY = rng.nextInt(TTA_CROP_RANGE + 1);
        boolean hFlip = rng.nextBoolean();
        boolean doRotate = rng.nextDouble() < TTA_ROTATE_PROB;
        // Uniform [-TTA_ROTATE_MAX_DEG, +TTA_ROTATE_MAX_DEG] when active.
        double rotateRad = doRotate
                ? Math.toRadians((rng.nextDouble() * 2.0 - 1.0) * TTA_ROTATE_MAX_DEG)
                : 0.0;

        // Sample the 224×224 view into an HWC float buffer in [0, 1]. The
        // sampling pass fuses (rotate + flip + crop) into one inverse-mapped
        // bilinear traversal so we touch each source pixel at most twice.
        float[] hwc = sampleAugmentedHwc(resized, cropX, cropY, hFlip, doRotate, rotateRad);

        // Color jitter parameters: uniform [1 - 0.15, 1 + 0.15] each.
        float brightness = jitterFactor(rng);
        float contrast = jitterFactor(rng);
        float saturation = jitterFactor(rng);
        int[] order = randomJitterOrder(rng);
        applyColorJitter(hwc, brightness, contrast, saturation, order);

        // Final step: HWC float[0..1] → CHW normalized float.
        return hwcToChwNormalized(hwc);
    }

    /**
     * Returns a colour-jitter scaling factor drawn from
     * {@code [1 − strength, 1 + strength]}, matching torchvision's
     * {@code ColorJitter} defaults.
     */
    private static float jitterFactor(Random rng) {
        return 1f + (rng.nextFloat() * 2f - 1f) * TTA_JITTER_STRENGTH;
    }

    /**
     * Returns a random permutation of {@code [0, 1, 2]} representing the
     * order in which brightness / contrast / saturation are applied. PIL's
     * {@code ColorJitter} shuffles the operation order per call — matching
     * that here keeps the augmentation distribution identical to training.
     */
    private static int[] randomJitterOrder(Random rng) {
        int[] order = {0, 1, 2};
        // Fisher-Yates shuffle for 3 elements.
        for (int i = order.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        return order;
    }

    /**
     * Inverse-maps every output pixel of a 224×224 view back to the source
     * 246×246 RGB buffer, fusing crop / horizontal-flip / rotation in one
     * pass. Pixels whose pre-rotation coordinate falls outside the 224×224
     * cropped region are filled with 0 — matching torchvision's
     * {@code RandomRotation(fill=0)} default.
     *
     * @return a flat HWC float buffer of length {@code 224 * 224 * 3}, values
     *         in [0, 1]
     */
    private static float[] sampleAugmentedHwc(byte[] resized, int cropX, int cropY,
                                              boolean hFlip, boolean doRotate, double rotateRad) {
        float[] hwc = new float[INPUT_SIZE * INPUT_SIZE * CHANNELS];
        final float center = (INPUT_SIZE - 1) * 0.5f;
        final float cos = (float) Math.cos(-rotateRad);
        final float sin = (float) Math.sin(-rotateRad);
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                // Apply rotation inverse (around the centre of the 224×224
                // image) before crop and flip. Output pixel (x, y) → "pre-
                // rotation" coordinate (px, py) in [0, INPUT_SIZE) when
                // sampling is in-bounds.
                float px, py;
                if (doRotate) {
                    float dx = x - center;
                    float dy = y - center;
                    px = center + dx * cos - dy * sin;
                    py = center + dx * sin + dy * cos;
                    if (px < 0f || px > INPUT_SIZE - 1f || py < 0f || py > INPUT_SIZE - 1f) {
                        // Outside the cropped image — torchvision's
                        // RandomRotation pads with 0 in pixel space. We
                        // operate in [0, 1] HWC here, so a literal 0 is the
                        // right value; the final normalize step will turn it
                        // into the ImageNet-normalized "black" constant.
                        int outIdx = (y * INPUT_SIZE + x) * 3;
                        hwc[outIdx] = 0f;
                        hwc[outIdx + 1] = 0f;
                        hwc[outIdx + 2] = 0f;
                        continue;
                    }
                } else {
                    px = x;
                    py = y;
                }
                // Apply horizontal flip in the cropped 224×224 frame.
                if (hFlip)
                    px = (INPUT_SIZE - 1) - px;
                // Translate into the 246×246 source frame.
                float sx = px + cropX;
                float sy = py + cropY;
                // Bilinear sample (no rotation case bypasses fractional math).
                int outIdx = (y * INPUT_SIZE + x) * 3;
                if (!doRotate) {
                    // Integer addressing — direct lookup.
                    int srcIdx = ((int) sy * TTA_RESIZE_EDGE + (int) sx) * 3;
                    hwc[outIdx] = (resized[srcIdx] & 0xFF) / 255f;
                    hwc[outIdx + 1] = (resized[srcIdx + 1] & 0xFF) / 255f;
                    hwc[outIdx + 2] = (resized[srcIdx + 2] & 0xFF) / 255f;
                } else {
                    bilinearSampleRgb(resized, TTA_RESIZE_EDGE, TTA_RESIZE_EDGE, sx, sy, hwc, outIdx);
                }
            }
        }
        return hwc;
    }

    /**
     * Bilinear-samples a single RGB pixel from a source buffer at fractional
     * {@code (sx, sy)} and writes the normalized [0, 1] float triple to
     * {@code dst[dstIdx..dstIdx+2]}. Out-of-range coordinates are clamped to
     * the nearest valid pixel.
     */
    private static void bilinearSampleRgb(byte[] src, int srcW, int srcH,
                                          float sx, float sy,
                                          float[] dst, int dstIdx) {
        int x0 = (int) Math.floor(sx);
        int y0 = (int) Math.floor(sy);
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        float wx = sx - x0;
        float wy = sy - y0;
        if (x0 < 0) {
            x0 = 0;
            wx = 0f;
        }
        if (y0 < 0) {
            y0 = 0;
            wy = 0f;
        }
        if (x1 > srcW - 1)
            x1 = srcW - 1;
        if (y1 > srcH - 1)
            y1 = srcH - 1;

        int idx00 = (y0 * srcW + x0) * 3;
        int idx01 = (y0 * srcW + x1) * 3;
        int idx10 = (y1 * srcW + x0) * 3;
        int idx11 = (y1 * srcW + x1) * 3;
        for (int c = 0; c < 3; c++) {
            float c00 = (src[idx00 + c] & 0xFF) / 255f;
            float c01 = (src[idx01 + c] & 0xFF) / 255f;
            float c10 = (src[idx10 + c] & 0xFF) / 255f;
            float c11 = (src[idx11 + c] & 0xFF) / 255f;
            float top = c00 + (c01 - c00) * wx;
            float bot = c10 + (c11 - c10) * wx;
            dst[dstIdx + c] = top + (bot - top) * wy;
        }
    }

    /**
     * Applies brightness / contrast / saturation jitter to an HWC float
     * image, in the random order given by {@code order}. Mirrors the per-op
     * semantics of PIL's {@code ColorJitter}:
     * <ul>
     *   <li>brightness: {@code clip(img * factor)};</li>
     *   <li>contrast: {@code clip((img - mean_gray) * factor + mean_gray)},
     *       with {@code mean_gray} computed over the current image;</li>
     *   <li>saturation: {@code clip(gray_p + (img - gray_p) * factor)}, with
     *       per-pixel {@code gray_p} computed from the current image.</li>
     * </ul>
     */
    private static void applyColorJitter(float[] hwc, float brightness, float contrast,
                                         float saturation, int[] order) {
        for (int op : order) {
            switch (op) {
                case 0:
                    applyBrightness(hwc, brightness);
                    break;
                case 1:
                    applyContrast(hwc, contrast);
                    break;
                case 2:
                    applySaturation(hwc, saturation);
                    break;
                default:
                    // Unreachable — randomJitterOrder only emits 0/1/2.
                    break;
            }
        }
    }

    /** {@code clip(img * factor)} applied in place. */
    private static void applyBrightness(float[] hwc, float factor) {
        if (factor == 1f)
            return;
        for (int i = 0; i < hwc.length; i++) {
            float v = hwc[i] * factor;
            if (v < 0f)
                v = 0f;
            else if (v > 1f)
                v = 1f;
            hwc[i] = v;
        }
    }

    /**
     * Contrast adjustment around the mean of the grayscale-converted image.
     * Matches PIL: the pivot is a scalar (the grayscale mean over the entire
     * image), so flat regions stay flat and only the spread changes.
     */
    private static void applyContrast(float[] hwc, float factor) {
        if (factor == 1f)
            return;
        // 1) Compute the grayscale mean (scalar).
        double sumGray = 0.0;
        int pixels = INPUT_SIZE * INPUT_SIZE;
        for (int p = 0; p < pixels; p++) {
            int idx = p * 3;
            sumGray += GRAY_R * hwc[idx] + GRAY_G * hwc[idx + 1] + GRAY_B * hwc[idx + 2];
        }
        float meanGray = (float) (sumGray / pixels);
        // 2) Map each channel around that mean.
        for (int i = 0; i < hwc.length; i++) {
            float v = (hwc[i] - meanGray) * factor + meanGray;
            if (v < 0f)
                v = 0f;
            else if (v > 1f)
                v = 1f;
            hwc[i] = v;
        }
    }

    /**
     * Saturation adjustment toward each pixel's own grayscale value. Matches
     * PIL: per-pixel pivot, so colour vividness changes but luminance is
     * preserved on a per-pixel basis.
     */
    private static void applySaturation(float[] hwc, float factor) {
        if (factor == 1f)
            return;
        int pixels = INPUT_SIZE * INPUT_SIZE;
        for (int p = 0; p < pixels; p++) {
            int idx = p * 3;
            float r = hwc[idx];
            float g = hwc[idx + 1];
            float b = hwc[idx + 2];
            float gray = GRAY_R * r + GRAY_G * g + GRAY_B * b;
            float nr = gray + (r - gray) * factor;
            float ng = gray + (g - gray) * factor;
            float nb = gray + (b - gray) * factor;
            if (nr < 0f) nr = 0f;
            else if (nr > 1f) nr = 1f;
            if (ng < 0f) ng = 0f;
            else if (ng > 1f) ng = 1f;
            if (nb < 0f) nb = 0f;
            else if (nb > 1f) nb = 1f;
            hwc[idx] = nr;
            hwc[idx + 1] = ng;
            hwc[idx + 2] = nb;
        }
    }

    /**
     * Converts an HWC float image in [0, 1] into a CHW float buffer with
     * ImageNet normalization applied. Mirrors torchvision's
     * {@code transforms.ToTensor() → Normalize(mean, std)} pair.
     */
    private static float[] hwcToChwNormalized(float[] hwc) {
        int plane = INPUT_SIZE * INPUT_SIZE;
        float[] out = new float[CHANNELS * plane];
        for (int p = 0; p < plane; p++) {
            int idx = p * 3;
            out[p] = (hwc[idx] - IMAGENET_MEAN[0]) / IMAGENET_STD[0];
            out[plane + p] = (hwc[idx + 1] - IMAGENET_MEAN[1]) / IMAGENET_STD[1];
            out[2 * plane + p] = (hwc[idx + 2] - IMAGENET_MEAN[2]) / IMAGENET_STD[2];
        }
        return out;
    }

    /**
     * TTA batch path that accepts already-decoded RGB buffers with
     * (potentially) per-image dimensions — e.g. when the source is a
     * {@code BufferedImage[]} where each image may have its own size. Resizes
     * each input to 246×246, then routes through the shared super-batch
     * inference path.
     */
    private float[][] embedBatchWithTtaMixed(byte[][] rgbBuffers, int[] widths, int[] heights) {
        byte[][] resized = new byte[rgbBuffers.length][];
        for (int i = 0; i < rgbBuffers.length; i++)
            resized[i] = bilinearResizeRgb(rgbBuffers[i], widths[i], heights[i],
                    TTA_RESIZE_EDGE, TTA_RESIZE_EDGE);
        return runTtaSuperBatch(resized);
    }

    /**
     * Builds {@code N × TTA_VIEWS} augmented views from per-image 246×246
     * buffers and submits them as a single ONNX batch, then fuses each
     * image's 8-view block into one L2-normalized embedding.
     */
    private float[][] runTtaSuperBatch(byte[][] resized) {
        int batch = resized.length;
        int totalViews = batch * TTA_VIEWS;
        float[][] views = new float[totalViews][];
        Random rng = ThreadLocalRandom.current();
        for (int i = 0; i < batch; i++) {
            for (int j = 0; j < TTA_VIEWS; j++)
                views[i * TTA_VIEWS + j] = buildOneTtaView(resized[i], rng);
        }
        float[][] flat = runBatch(views);
        // Reduce each contiguous TTA_VIEWS-block back to a single embedding.
        float[][] fused = new float[batch][];
        for (int i = 0; i < batch; i++) {
            float[] sum = new float[EMBEDDING_DIM];
            int base = i * TTA_VIEWS;
            for (int j = 0; j < TTA_VIEWS; j++) {
                float[] e = flat[base + j];
                for (int k = 0; k < EMBEDDING_DIM; k++)
                    sum[k] += e[k];
            }
            l2Normalize(sum);
            fused[i] = sum;
        }
        return fused;
    }

    /**
     * Sums the rows of an embedding matrix and L2-normalizes the result.
     * Used to collapse the {@link #TTA_VIEWS} per-image augmentation
     * embeddings into a single query vector.
     */
    private static float[] fuseEmbeddings(float[][] embeddings) {
        float[] sum = new float[EMBEDDING_DIM];
        for (float[] e : embeddings)
            for (int k = 0; k < EMBEDDING_DIM; k++)
                sum[k] += e[k];
        l2Normalize(sum);
        return sum;
    }

    // ── internal helpers — inference ───────────────────────────────────────

    /**
     * Runs a single-sample inference and returns the L2-normalized embedding.
     * Falls back to a zero vector if the service is disabled.
     */
    private float[] runSingle(float[] input) {
        if (session == null) {
            // Disabled: return a zero vector so callers don't crash.
            return new float[EMBEDDING_DIM];
        }
        long[] shape = {1, CHANNELS, INPUT_SIZE, INPUT_SIZE};
        OnnxTensor tensor = null;
        OrtSession.Result result = null;
        try {
            tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
            result = session.run(Collections.singletonMap(inputName, tensor));
            float[][] raw = (float[][]) result.get(outputName).get().getValue();
            float[] embedding = raw[0].clone();
            l2Normalize(embedding);
            return embedding;
        } catch (OrtException e) {
            throw new RuntimeException("ONNX inference failed: " + e.getMessage(), e);
        } finally {
            if (result != null)
                result.close();
            if (tensor != null)
                tensor.close();
        }
    }

    /**
     * Runs a batched inference and returns one L2-normalized embedding per
     * input row. Falls back to zero vectors if the service is disabled.
     */
    private float[][] runBatch(float[][] inputs) {
        if (session == null) {
            return new float[inputs.length][EMBEDDING_DIM];
        }
        int batch = inputs.length;
        int rowLen = CHANNELS * INPUT_SIZE * INPUT_SIZE;
        // Flatten all rows into one contiguous buffer because ORT expects a
        // single tensor — not a list of rows — for the input feed.
        float[] flat = new float[batch * rowLen];
        for (int b = 0; b < batch; b++) {
            float[] src = inputs[b];
            if (src.length != rowLen)
                throw new IllegalStateException("Preprocessed input row " + b + " has wrong length");
            System.arraycopy(src, 0, flat, b * rowLen, rowLen);
        }
        long[] shape = {batch, CHANNELS, INPUT_SIZE, INPUT_SIZE};
        OnnxTensor tensor = null;
        OrtSession.Result result = null;
        try {
            tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape);
            result = session.run(Collections.singletonMap(inputName, tensor));
            float[][] raw = (float[][]) result.get(outputName).get().getValue();
            float[][] out = new float[batch][];
            for (int b = 0; b < batch; b++) {
                out[b] = raw[b].clone();
                l2Normalize(out[b]);
            }
            return out;
        } catch (OrtException e) {
            throw new RuntimeException("ONNX batch inference failed: " + e.getMessage(), e);
        } finally {
            if (result != null)
                result.close();
            if (tensor != null)
                tensor.close();
        }
    }

    // ── internal helpers — classification ──────────────────────────────────

    /**
     * Computes the top-K nearest centroids to an embedding by cosine
     * similarity. Both the embedding and centroids are pre-normalized so
     * cosine similarity reduces to a dot product.
     */
    private PredictionResult classify(float[] embedding, int topK) {
        int classes = classNames.size();
        int k = Math.max(1, Math.min(topK, classes));

        // Score every class.
        float[] scores = new float[classes];
        for (int c = 0; c < classes; c++)
            scores[c] = dot(embedding, centroidVectors[c]);

        // Partial-sort by score descending. classes is tiny (15 in the bundled
        // config) so an O(N²) selection is faster than a full O(N log N) sort.
        int[] order = new int[classes];
        for (int i = 0; i < classes; i++)
            order[i] = i;
        for (int i = 0; i < k; i++) {
            int best = i;
            for (int j = i + 1; j < classes; j++)
                if (scores[order[j]] > scores[order[best]])
                    best = j;
            int tmp = order[i];
            order[i] = order[best];
            order[best] = tmp;
        }

        List<TopKEntry> top = new ArrayList<TopKEntry>(k);
        for (int i = 0; i < k; i++) {
            int idx = order[i];
            top.add(new TopKEntry(classNames.get(idx), scores[idx]));
        }
        List<TopKEntry> immutableTop = Collections.unmodifiableList(top);

        int winnerIdx = order[0];
        float[] winnerCentroid = centroidVectors[winnerIdx].clone();
        return new PredictionResult(
                embedding, classNames.get(winnerIdx), scores[winnerIdx],
                winnerCentroid, immutableTop);
    }

    /**
     * Helper that classifies an entire batch of embeddings against the
     * centroid table.
     */
    private PredictionResult[] classifyBatch(float[][] embeddings, int topK) {
        PredictionResult[] out = new PredictionResult[embeddings.length];
        for (int i = 0; i < embeddings.length; i++)
            out[i] = classify(embeddings[i], topK);
        return out;
    }

    // ── internal helpers — vector math ─────────────────────────────────────

    /**
     * Computes the dot product of two equally-sized float arrays. Used as the
     * inner step of cosine similarity when both inputs are L2-normalized.
     */
    private static float dot(float[] a, float[] b) {
        float s = 0f;
        for (int i = 0; i < a.length; i++)
            s += a[i] * b[i];
        return s;
    }

    /**
     * Normalizes a vector in place to unit L2 norm. Vectors with effectively
     * zero norm are left untouched — dividing by 0 would produce NaNs that
     * propagate through every subsequent cosine similarity.
     */
    private static void l2Normalize(float[] v) {
        double sumSq = 0.0;
        for (float x : v)
            sumSq += (double) x * x;
        double norm = Math.sqrt(sumSq);
        if (norm < 1e-12)
            return;
        float inv = (float) (1.0 / norm);
        for (int i = 0; i < v.length; i++)
            v[i] *= inv;
    }
}
