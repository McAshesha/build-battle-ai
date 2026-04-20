package ru.ashesha.buildBattleAI.ml;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST proxy implementation of {@link BBAIMLService}.
 * <p>
 * Delegates all ML inference to an external FastAPI microservice over HTTP/JSON.
 * This is a temporary implementation — the ML inference logic will eventually be
 * migrated to native Java, at which point only this class changes while the
 * {@link BBAIMLService} interface and all callers remain untouched.
 * <p>
 * The microservice exposes three endpoints:
 * <ul>
 *     <li>{@code GET /health} — service health and model metadata</li>
 *     <li>{@code GET /centroids} — per-class mean embedding centroids</li>
 *     <li>{@code POST /predict} — image classification with embedding + top-K results</li>
 * </ul>
 * <p>
 * <b>All public methods are blocking.</b> Call from an async context to avoid
 * stalling the main server thread.
 *
 * @see BBAIMLService
 */
public class MLService implements BBAIMLService, PluginService {

    /** Default base URL for the ML microservice. */
    private static final String DEFAULT_BASE_URL = "http://localhost:8001";

    /** Connection timeout for HTTP requests in milliseconds. */
    private static final int CONNECT_TIMEOUT = 5000;

    /** Read timeout for HTTP requests in milliseconds. */
    private static final int READ_TIMEOUT = 30000;

    /** The plugin instance, used for logging. */
    private final BuildBattleAI plugin;

    /** Base URL of the ML microservice (no trailing slash). */
    private final String baseUrl;

    /**
     * Creates the ML service with the default base URL ({@value DEFAULT_BASE_URL}).
     *
     * @param plugin the plugin instance
     */
    public MLService(@NonNull BuildBattleAI plugin) {
        this(plugin, DEFAULT_BASE_URL);
    }

    /**
     * Creates the ML service pointing at a custom base URL.
     *
     * @param plugin  the plugin instance
     * @param baseUrl the ML microservice base URL (e.g. {@code "http://localhost:8001"})
     */
    public MLService(@NonNull BuildBattleAI plugin, @NonNull String baseUrl) {
        this.plugin = plugin;
        // Strip trailing slash for consistent URL construction
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    // ── public API ──────────────────────────────────────────────────────────

    @Override
    @NonNull
    public PredictionResult predict(byte @NonNull [] rgbPixels, int width, int height, int topK) {
        byte[] pngData = encodeToPng(rgbPixels, width, height);
        return predictImage(pngData, topK);
    }

    @Override
    @NonNull
    public PredictionResult predictImage(byte @NonNull [] imageData, int topK) {
        String base64 = "data:image/png;base64," + Base64.getEncoder().encodeToString(imageData);

        JsonObject request = new JsonObject();
        request.addProperty("image_base64", base64);
        request.addProperty("top_k", topK);

        JsonObject response = postJson(baseUrl + "/predict", request);
        return parsePredictionResult(response);
    }

    @Override
    @NonNull
    public HealthInfo health() {
        JsonObject response = getJson(baseUrl + "/health");
        return parseHealthInfo(response);
    }

    @Override
    @NonNull
    public Map<String, float[]> centroids() {
        JsonObject response = getJson(baseUrl + "/centroids");
        JsonObject centroidsObj = response.getAsJsonObject("centroids");

        Map<String, float[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : centroidsObj.entrySet())
            result.put(entry.getKey(), parseFloatArray(entry.getValue().getAsJsonArray()));
        return result;
    }

    /**
     * No-op for the REST proxy implementation — no connection pool or cache is
     * held between calls (each request opens its own {@link HttpURLConnection}).
     * When ML inference is migrated to native Java this method will warm up the
     * model, allocate inference buffers, and pin GPU resources.
     */
    @Override
    public void enable() {
        // Intentionally empty — REST proxy has nothing to initialize per-cycle.
    }

    @Override
    public void shutdown() {
        // No persistent resources to clean up for the REST proxy implementation.
        // When migrated to native Java ML, this method will release model weights
        // and any GPU/thread-pool resources.
    }

    // ── image encoding ──────────────────────────────────────────────────────

    /**
     * Converts raw RGB pixel data to PNG format.
     * <p>
     * The input is a row-major byte array where each pixel is 3 consecutive bytes
     * (R, G, B) with values 0–255 stored as signed Java bytes. This matches the
     * output format of {@link ru.ashesha.buildBattleAI.render.CpuRenderer#render}.
     *
     * @param rgbPixels row-major RGB byte array
     * @param width     image width in pixels
     * @param height    image height in pixels
     * @return the PNG-encoded image bytes
     * @throws RuntimeException if PNG encoding fails
     */
    private static byte[] encodeToPng(byte[] rgbPixels, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int idx = (y * width + x) * 3;
                int r = rgbPixels[idx] & 0xFF;
                int g = rgbPixels[idx + 1] & 0xFF;
                int b = rgbPixels[idx + 2] & 0xFF;
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "PNG", baos);
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode image to PNG", e);
        }
        return baos.toByteArray();
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────

    /**
     * Performs a GET request and parses the JSON response.
     *
     * @param url the full request URL
     * @return the parsed JSON response body
     * @throws RuntimeException if the request fails or returns a non-200 status
     */
    private JsonObject getJson(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Accept", "application/json");

            int status = conn.getResponseCode();
            if (status != 200) {
                String error = readErrorBody(conn);
                conn.disconnect();
                throw new RuntimeException("ML service GET " + url + " returned " + status + ": " + error);
            }

            //noinspection deprecation — instance parse() is required for Gson 2.2.4 (Spigot 1.8)
            JsonObject result = new JsonParser()
                    .parse(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            conn.disconnect();
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("ML service GET " + url + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Performs a POST request with a JSON body and parses the JSON response.
     *
     * @param url  the full request URL
     * @param body the JSON request body
     * @return the parsed JSON response body
     * @throws RuntimeException if the request fails or returns a non-200 status
     */
    private JsonObject postJson(String url, JsonObject body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");

            // Write the JSON request body
            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bodyBytes.length);
            OutputStream out = conn.getOutputStream();
            out.write(bodyBytes);
            out.flush();
            out.close();

            int status = conn.getResponseCode();
            if (status != 200) {
                String error = readErrorBody(conn);
                conn.disconnect();
                throw new RuntimeException("ML service POST " + url + " returned " + status + ": " + error);
            }

            //noinspection deprecation
            JsonObject result = new JsonParser()
                    .parse(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            conn.disconnect();
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("ML service POST " + url + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Reads the error response body from a failed HTTP connection.
     * Returns a best-effort string; never throws.
     *
     * @param conn the HTTP connection with a non-200 response
     * @return the error body text, or a fallback message if unreadable
     */
    private static String readErrorBody(HttpURLConnection conn) {
        try {
            if (conn.getErrorStream() == null)
                return "(no error body)";
            InputStreamReader reader = new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int n;
            while ((n = reader.read(buf)) != -1)
                sb.append(buf, 0, n);
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return "(failed to read error body: " + e.getMessage() + ")";
        }
    }

    // ── JSON parsing ────────────────────────────────────────────────────────

    /**
     * Parses a {@code /predict} JSON response into a {@link PredictionResult}.
     *
     * @param json the raw JSON response object
     * @return the parsed prediction result
     */
    private static PredictionResult parsePredictionResult(JsonObject json) {
        String modelName = json.get("model_name").getAsString();
        float[] embedding = parseFloatArray(json.getAsJsonArray("embedding"));
        String predictedClass = json.get("predicted_class").getAsString();
        float predictedScore = json.get("predicted_score").getAsFloat();
        float[] predictedCentroid = parseFloatArray(json.getAsJsonArray("predicted_centroid"));

        // Parse top-K entries
        JsonArray topKArray = json.getAsJsonArray("top_k");
        List<TopKEntry> topK = new ArrayList<>(topKArray.size());
        for (int i = 0; i < topKArray.size(); i++) {
            JsonObject entry = topKArray.get(i).getAsJsonObject();
            topK.add(new TopKEntry(
                    entry.get("class_name").getAsString(),
                    entry.get("score").getAsFloat()
            ));
        }

        // Parse available classes
        List<String> availableClasses = parseStringList(json.getAsJsonArray("available_classes"));

        return new PredictionResult(
                modelName, embedding, predictedClass, predictedScore,
                predictedCentroid, Collections.unmodifiableList(topK),
                Collections.unmodifiableList(availableClasses)
        );
    }

    /**
     * Parses a {@code /health} JSON response into a {@link HealthInfo}.
     *
     * @param json the raw JSON response object
     * @return the parsed health info
     */
    private static HealthInfo parseHealthInfo(JsonObject json) {
        return new HealthInfo(
                json.get("status").getAsString(),
                json.get("model_name").getAsString(),
                json.get("device").getAsString(),
                json.get("num_classes").getAsInt(),
                json.get("embedding_dim").getAsInt(),
                json.get("input_size").getAsInt(),
                Collections.unmodifiableList(parseStringList(json.getAsJsonArray("classes")))
        );
    }

    /**
     * Parses a JSON array of numbers into a float array.
     *
     * @param array the JSON array of numeric elements
     * @return the corresponding float array
     */
    private static float[] parseFloatArray(JsonArray array) {
        float[] result = new float[array.size()];
        for (int i = 0; i < array.size(); i++)
            result[i] = array.get(i).getAsFloat();
        return result;
    }

    /**
     * Parses a JSON array of strings into a list.
     *
     * @param array the JSON array of string elements
     * @return the corresponding string list
     */
    private static List<String> parseStringList(JsonArray array) {
        List<String> result = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++)
            result.add(array.get(i).getAsString());
        return result;
    }

    // ── response data classes ───────────────────────────────────────────────

    /**
     * Result of an image classification request.
     * <p>
     * Contains the 128-dimensional embedding vector, the top predicted class with
     * its similarity score, and the ranked top-K class predictions.
     */
    @Getter
    @Accessors(fluent = true)
    public static final class PredictionResult {

        /** The model that produced this prediction (e.g. {@code "resnet50"}). */
        private final String modelName;

        /** 128-dimensional L2-normalized embedding vector for the input image. */
        private final float[] embedding;

        /** Top-1 predicted class name (highest cosine similarity to prototypes). */
        private final String predictedClass;

        /** Cosine similarity score (0.0–1.0) for the top-1 predicted class. */
        private final float predictedScore;

        /** 128-dimensional centroid embedding of the top-1 predicted class. */
        private final float[] predictedCentroid;

        /** Ranked list of top-K class predictions with similarity scores. */
        private final List<TopKEntry> topK;

        /** All class names known to the model. */
        private final List<String> availableClasses;

        PredictionResult(String modelName, float[] embedding, String predictedClass,
                         float predictedScore, float[] predictedCentroid,
                         List<TopKEntry> topK, List<String> availableClasses) {
            this.modelName = modelName;
            this.embedding = embedding;
            this.predictedClass = predictedClass;
            this.predictedScore = predictedScore;
            this.predictedCentroid = predictedCentroid;
            this.topK = topK;
            this.availableClasses = availableClasses;
        }
    }

    /**
     * A single entry in the top-K prediction ranking.
     */
    @Getter
    @Accessors(fluent = true)
    public static final class TopKEntry {

        /** The class name for this prediction. */
        private final String className;

        /** Cosine similarity score (0.0–1.0) between the image embedding and this class. */
        private final float score;

        TopKEntry(String className, float score) {
            this.className = className;
            this.score = score;
        }
    }

    /**
     * ML service health and model metadata.
     * <p>
     * Returned by the {@code /health} endpoint; provides information about the
     * loaded model, available device, and recognized class set.
     */
    @Getter
    @Accessors(fluent = true)
    public static final class HealthInfo {

        /** Service status (e.g. {@code "ok"}). */
        private final String status;

        /** Name of the loaded model (e.g. {@code "resnet50"}). */
        private final String modelName;

        /** Compute device the model is running on ({@code "cpu"} or {@code "cuda"}). */
        private final String device;

        /** Total number of classes the model can predict. */
        private final int numClasses;

        /** Dimensionality of the embedding vector (typically 128). */
        private final int embeddingDim;

        /** Expected input image size in pixels (typically 224). */
        private final int inputSize;

        /** All class names recognized by the model. */
        private final List<String> classes;

        HealthInfo(String status, String modelName, String device,
                   int numClasses, int embeddingDim, int inputSize, List<String> classes) {
            this.status = status;
            this.modelName = modelName;
            this.device = device;
            this.numClasses = numClasses;
            this.embeddingDim = embeddingDim;
            this.inputSize = inputSize;
            this.classes = classes;
        }
    }
}
