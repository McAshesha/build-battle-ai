package ru.ashesha.buildBattleAI.e2e;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared end-to-end driver that launches a real Minecraft server process,
 * waits for plugin enable, exercises a single command through stdin, and
 * shuts the server down cleanly.
 * <p>
 * The driver is intentionally process-isolated: it does <i>not</i> share a
 * JVM with the server, doesn't reflect into its classloader, and doesn't
 * patch any classes. All observation happens by tailing stdout/stderr and
 * pattern-matching the well-known server log markers ({@code "Done ("}
 * for startup, {@code "Stopping server"} for shutdown). This makes the
 * driver robust against the API drift that exists between Paper 1.8.8 and
 * Purpur 1.21.11.
 * <p>
 * <b>Activation.</b> The test is gated by the {@code bbai.e2e} system
 * property. Pass {@code -Dbbai.e2e=true} (the {@code -Pe2e} Maven profile
 * sets this automatically) to enable the test. Otherwise the test reports
 * itself as skipped — E2E is too slow (a 30–90 s server startup) to belong
 * in the default {@code mvn test} cycle.
 * <p>
 * <b>Prerequisites.</b> The plugin JAR must have been built and copied into
 * {@code Servers/<version>/plugins/} (the antrun step of {@code mvn package}
 * does this automatically). If neither the built JAR nor the in-place server
 * directory exist, the test skips with a meaningful message rather than
 * failing — this lets developers without the local server checkout still
 * run {@code mvn test -Pe2e} without surprise failures.
 */
public abstract class AbstractServerE2ETest {

    /** Maximum wall-clock time we wait for "Done (" to appear in server output. */
    private static final long STARTUP_TIMEOUT_SECONDS = 240;

    /** Maximum wall-clock time we wait for the server process to exit after `stop`. */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 90;

    /**
     * Pattern that extracts a named counter value from a stats log line of the
     * form {@code ... counterName=12345 ...}. Used by
     * {@link #extractStatsCounter(String, String)}.
     */
    private static final Pattern STATS_COUNTER_PATTERN =
            Pattern.compile("([A-Za-z0-9_\\-\\.]+)=(\\d+)");

    /** Buffered tail of every byte of server stdout — used for assertions. */
    private final StringBuilder output = new StringBuilder();

    /**
     * Returns the absolute path to the server directory containing
     * {@code start.command} and the server jar. Subclasses pin a specific
     * server version.
     */
    protected abstract Path serverDirectory();

    /**
     * A short human-readable label for the server (e.g. {@code "Paper 1.8.8"},
     * {@code "Purpur 1.21.11"}) — used purely in failure messages.
     */
    protected abstract String serverFlavor();

    @Test
    void pluginBootsExecutesCommandAndShutsDownCleanly() throws Exception {
        Assumptions.assumeTrue(
                Boolean.parseBoolean(System.getProperty("bbai.e2e", "false")),
                "E2E tests skipped — pass -Dbbai.e2e=true (or use -Pe2e) to enable.");

        Path serverDir = serverDirectory();
        Assumptions.assumeTrue(Files.isDirectory(serverDir),
                serverFlavor() + " server directory missing: " + serverDir);

        Path startScript = serverDir.resolve("start.command");
        Assumptions.assumeTrue(Files.isRegularFile(startScript),
                serverFlavor() + " start script missing: " + startScript);

        Process server = launchServerWithPluginRefresh(serverDir);
        Thread streamReader = startStdoutReader(server.getInputStream());

        try {
            waitForMarker("Done (", STARTUP_TIMEOUT_SECONDS,
                    "server failed to reach 'Done (' marker");

            String pluginEnableEvidence = findFirstPluginMarker(output.toString());
            assertNotNull(pluginEnableEvidence,
                    serverFlavor() + " did not log BuildBattleAI enable. Tail of output:\n"
                            + tail(output.toString(), 4000));

            // Smoke a single command. Any of these should produce output without
            // throwing into the server console.
            sendCommand(server, "bbai list");
            Thread.sleep(2500);

            int exitCode = stopServerGracefully(server, SHUTDOWN_TIMEOUT_SECONDS);
            if (exitCode == -1)
                fail(serverFlavor() + " did not stop within "
                        + SHUTDOWN_TIMEOUT_SECONDS + " s; process forcibly killed.\n"
                        + "Tail:\n" + tail(output.toString(), 4000));
            assertEquals(0, exitCode,
                    serverFlavor() + " exited with non-zero status. Tail:\n"
                            + tail(output.toString(), 4000));

            List<String> pluginErrors = extractPluginErrors(output.toString());
            assertTrue(pluginErrors.isEmpty(),
                    serverFlavor() + " emitted plugin-related ERROR/SEVERE lines:\n"
                            + String.join("\n", pluginErrors));
        } finally {
            if (server.isAlive())
                server.destroyForcibly();
            streamReader.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    // ── protected helpers available to subclasses ────────────────────────

    /**
     * Refreshes the plugin JAR in the server's plugins directory, writes
     * {@code eula.txt}, launches the server process via {@code bash
     * start.command}, and returns the live {@link Process}. The caller is
     * responsible for spawning the stdout-reader thread (via
     * {@link #startStdoutReader}) and for eventually killing the process if
     * an exception is thrown before a clean shutdown.
     * <p>
     * The method is intentionally side-effect-free with respect to the
     * {@link #output} buffer: the caller supplies the reader thread so it can
     * decide where to attach it. This separation keeps the helper usable from
     * subclass {@code @BeforeEach} methods that need to capture output before
     * the test proper starts.
     *
     * @param serverDir absolute path to the server directory (must contain
     *                  {@code start.command})
     * @return a live {@link Process} whose stdin is writable and whose stdout
     *         has not yet been consumed
     * @throws IOException          if file operations or process launch fail
     * @throws InterruptedException if the thread is interrupted while setting up
     */
    protected Process launchServerWithPluginRefresh(Path serverDir)
            throws IOException, InterruptedException {
        Path pluginsDir = serverDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        Path builtJar = findBuiltPluginJar();
        Path targetJar = pluginsDir.resolve("buildbattleai-e2e.jar");
        if (builtJar != null) {
            // The antrun copy step of `mvn package` already places a copy of
            // the freshly built jar under plugins/ with the canonical Maven
            // name. Having BOTH that copy AND ours present triggers Bukkit's
            // "Ambiguous plugin name" error and refuses to load either —
            // remove every other BuildBattleAI jar before we lay down ours.
            for (Path existing : listJarsIn(pluginsDir)) {
                if (existing.equals(targetJar))
                    continue;
                String name = existing.getFileName().toString().toLowerCase();
                if (name.contains("buildbattleai"))
                    Files.deleteIfExists(existing);
            }
            Files.copy(builtJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
        } else {
            // No fresh build — fall back to whatever's already in plugins/.
            // If nothing is there at all we have to skip; otherwise the test
            // would meaninglessly assert on an empty plugin set.
            boolean hasJar = listJarsIn(pluginsDir).stream()
                    .anyMatch(p -> p.getFileName().toString().toLowerCase().contains("buildbattleai"));
            Assumptions.assumeTrue(hasJar,
                    "No built plugin JAR found in target/ and no existing BuildBattleAI jar in "
                            + pluginsDir + " — run `mvn package` before `-Pe2e`.");
        }

        ensureEulaAccepted(serverDir);

        Path startScript = serverDir.resolve("start.command");
        ProcessBuilder pb = new ProcessBuilder("bash", startScript.toAbsolutePath().toString())
                .directory(serverDir.toFile())
                .redirectErrorStream(true);
        return pb.start();
    }

    /**
     * Sends the {@code stop} command to the server's stdin and waits up to
     * {@code timeoutSeconds} for the process to exit cleanly. If the process
     * does not exit in time it is killed forcibly via
     * {@link Process#destroyForcibly()}.
     *
     * @param server         the live server process
     * @param timeoutSeconds maximum seconds to wait for the process to exit
     * @return the process exit code, or {@code -1} if the timeout elapsed and
     *         the process was force-killed
     * @throws IOException          if writing to stdin fails
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    protected int stopServerGracefully(Process server, long timeoutSeconds)
            throws IOException, InterruptedException {
        sendCommand(server, "stop");
        boolean exited = server.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) {
            server.destroyForcibly();
            return -1;
        }
        return server.exitValue();
    }

    /**
     * Polls the buffered server output for {@code needle} until it appears or
     * the timeout elapses. Polling avoids a separate condition-variable dance
     * with the reader thread.
     *
     * @param needle         substring to search for in the accumulated output
     * @param timeoutSeconds maximum seconds to wait before failing the test
     * @param failureMessage prefix included in the assertion failure message
     * @throws InterruptedException if the polling thread is interrupted
     */
    protected void waitForMarker(String needle, long timeoutSeconds, String failureMessage)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            synchronized (output) {
                if (output.indexOf(needle) >= 0)
                    return;
            }
            Thread.sleep(250);
        }
        fail(failureMessage + " (timeout=" + timeoutSeconds + "s). Tail of output:\n"
                + tail(output.toString(), 4000));
    }

    /**
     * Writes {@code command\n} to the server's stdin, flushing immediately so
     * the server console picks it up on the next tick.
     *
     * @param server  the live server process
     * @param command the raw command string (without trailing newline)
     * @throws IOException if the write fails
     */
    protected static void sendCommand(Process server, String command) throws IOException {
        OutputStream stdin = server.getOutputStream();
        stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    /**
     * Returns a defensive copy of the full server output accumulated so far.
     * Safe to call from any thread; the returned {@link String} is an immutable
     * snapshot — subsequent server output does not affect it.
     *
     * @return all server stdout lines joined by {@code '\n'}, or an empty
     *         string if nothing has been captured yet
     */
    protected String output() {
        synchronized (output) {
            return output.toString();
        }
    }

    /**
     * Writes a minimal arena YAML file to
     * {@code <serverDir>/plugins/BuildBattleAI/arena/<arenaName>.yml} before
     * the server starts. Creates the full directory tree if absent. This
     * method is idempotent — calling it twice with the same arguments simply
     * overwrites the file.
     * <p>
     * This helper is intended for E2E test setup in a {@code @BeforeEach} or
     * directly in the test body, before {@link #launchServerWithPluginRefresh}
     * is called, so the plugin discovers the arena on its first load.
     *
     * @param serverDir   absolute path to the server root (e.g.
     *                    {@code Paths.get("Servers","1.21").toAbsolutePath()})
     * @param arenaName   logical arena name; becomes the file name
     *                    {@code <arenaName>.yml}
     * @param yamlContent the full YAML string to write verbatim
     * @throws IOException if directory creation or file write fails
     */
    protected static void preSeedArenaYaml(Path serverDir, String arenaName, String yamlContent)
            throws IOException {
        Path arenaDir = serverDir.resolve("plugins")
                .resolve("BuildBattleAI")
                .resolve("arena");
        Files.createDirectories(arenaDir);
        Path arenaFile = arenaDir.resolve(arenaName + ".yml");
        Files.write(arenaFile,
                yamlContent.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Parses a single named counter value from a stats log line. The expected
     * line format is any string containing {@code <counterName>=<number>} where
     * {@code number} is a non-negative integer. Both the full log output and
     * individual lines are accepted as {@code output}.
     * <p>
     * Example line produced by {@code /bbai stats}:
     * <pre>
     *   [BBAI] EvaluationStats completedRenders=14 completedMlBatches=3 ...
     * </pre>
     *
     * @param output      the full server output (or a subsection) to search
     * @param counterName the exact counter name as it appears in the log line,
     *                    e.g. {@code "completedRenders"}
     * @return the parsed {@code long} value, or {@code -1} if the counter is
     *         not found anywhere in {@code output}
     */
    protected static long extractStatsCounter(String output, String counterName) {
        Matcher m = STATS_COUNTER_PATTERN.matcher(output);
        while (m.find()) {
            if (m.group(1).equals(counterName)) {
                try {
                    return Long.parseLong(m.group(2));
                } catch (NumberFormatException ignored) {
                    // Malformed number — keep searching.
                }
            }
        }
        return -1L;
    }

    /**
     * Builds a minimal, syntactically valid arena YAML string that the plugin's
     * {@code ArenaManager} will accept without validation errors. The lobby spawn
     * is placed at {@code (0, 65, 0)}. Each plot is offset by 20 blocks along
     * the X axis from the previous one. Camera positions are placed above the
     * plot looking inward.
     * <p>
     * The returned YAML is intended to be passed directly to
     * {@link #preSeedArenaYaml(Path, String, String)}.
     *
     * @param name       the arena name (must match the file name used with
     *                   {@link #preSeedArenaYaml})
     * @param maxPlayers the number of player plots to generate (must be &ge; 1)
     * @return a minimal valid arena YAML string
     */
    protected static String buildMinimalArenaYaml(String name, int maxPlayers) {
        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(name).append('\n');
        sb.append("lobby:\n");
        sb.append("  world: bbai_").append(name).append('\n');
        sb.append("  x: 0.5\n");
        sb.append("  y: 65.0\n");
        sb.append("  z: 0.5\n");
        sb.append("  yaw: 0.0\n");
        sb.append("  pitch: 0.0\n");
        sb.append("spectator:\n");
        sb.append("  world: bbai_").append(name).append('\n');
        sb.append("  x: 0.5\n");
        sb.append("  y: 80.0\n");
        sb.append("  z: 0.5\n");
        sb.append("  yaw: 0.0\n");
        sb.append("  pitch: -90.0\n");
        sb.append("min-players: 1\n");
        sb.append("build-time: 120\n");
        sb.append("game-time: 300\n");
        sb.append("countdown-time: 10\n");
        sb.append("plots:\n");

        // Each plot is 8 blocks wide (x: offset*20 to offset*20+7), 8 deep
        // (z: 0 to 7), and 8 tall (y: 64 to 72). Cameras orbit above.
        for (int i = 0; i < maxPlayers; i++) {
            int ox = i * 20; // X origin for this plot
            int cx = ox + 4; // centre X
            sb.append("  - spawn:\n");
            sb.append("      world: bbai_").append(name).append('\n');
            sb.append("      x: ").append(cx).append(".5\n");
            sb.append("      y: 65.0\n");
            sb.append("      z: 4.5\n");
            sb.append("      yaw: 180.0\n");
            sb.append("      pitch: 0.0\n");
            sb.append("    corner1:\n");
            sb.append("      world: bbai_").append(name).append('\n');
            sb.append("      x: ").append(ox).append(".0\n");
            sb.append("      y: 64.0\n");
            sb.append("      z: 0.0\n");
            sb.append("    corner2:\n");
            sb.append("      world: bbai_").append(name).append('\n');
            sb.append("      x: ").append(ox + 7).append(".0\n");
            sb.append("      y: 72.0\n");
            sb.append("      z: 7.0\n");
            // Camera 1 — front view (south, looking north)
            sb.append("    camera1:\n");
            sb.append("      world: bbai_").append(name).append('\n');
            sb.append("      x: ").append(cx).append(".5\n");
            sb.append("      y: 68.0\n");
            sb.append("      z: -8.0\n");
            sb.append("      yaw: 0.0\n");
            sb.append("      pitch: -20.0\n");
            // Camera 2 — side view (east, looking west)
            sb.append("    camera2:\n");
            sb.append("      world: bbai_").append(name).append('\n');
            sb.append("      x: ").append(ox + 16).append(".0\n");
            sb.append("      y: 68.0\n");
            sb.append("      z: 4.5\n");
            sb.append("      yaw: -90.0\n");
            sb.append("      pitch: -20.0\n");
            // Camera 3 — top-down view
            sb.append("    camera3:\n");
            sb.append("      world: bbai_").append(name).append('\n');
            sb.append("      x: ").append(cx).append(".5\n");
            sb.append("      y: 80.0\n");
            sb.append("      z: 4.5\n");
            sb.append("      yaw: 0.0\n");
            sb.append("      pitch: -75.0\n");
            // Picture region — a 2×2 block face on the south wall of the plot
            sb.append("    picture:\n");
            sb.append("      corner1:\n");
            sb.append("        world: bbai_").append(name).append('\n');
            sb.append("        x: ").append(cx - 1).append(".0\n");
            sb.append("        y: 66.0\n");
            sb.append("        z: -1.0\n");
            sb.append("      corner2:\n");
            sb.append("        world: bbai_").append(name).append('\n');
            sb.append("        x: ").append(cx).append(".0\n");
            sb.append("        y: 67.0\n");
            sb.append("        z: -1.0\n");
            sb.append("      face: SOUTH\n");
        }

        return sb.toString();
    }

    // ── package-private helpers ──────────────────────────────────────────

    /**
     * Searches {@code target/} for the most recently built shaded JAR
     * (excluding {@code -lite}, {@code -sources}, {@code -javadoc}).
     *
     * @return path to the freshest plugin jar, or {@code null} if no build
     *         has been produced yet
     * @throws IOException if directory traversal fails
     */
    private static Path findBuiltPluginJar() throws IOException {
        Path target = Paths.get("target");
        if (!Files.isDirectory(target))
            return null;
        try (Stream<Path> stream = Files.list(target)) {
            return stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".jar")
                                && n.startsWith("buildbattleai-")
                                && !n.endsWith("-lite.jar")
                                && !n.contains("sources")
                                && !n.contains("javadoc")
                                && !n.contains("original");
                    })
                    .reduce((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a).compareTo(
                                    Files.getLastModifiedTime(b)) >= 0 ? a : b;
                        } catch (IOException e) {
                            return a;
                        }
                    })
                    .orElse(null);
        }
    }

    /**
     * Returns every jar file in the given directory; used to verify a
     * fallback plugin jar is already present when {@code target/} is empty.
     *
     * @param dir the directory to list
     * @return mutable list of {@code .jar} paths; empty if the directory is
     *         absent or contains no jars
     * @throws IOException if directory listing fails
     */
    private static List<Path> listJarsIn(Path dir) throws IOException {
        if (!Files.isDirectory(dir))
            return new ArrayList<Path>();
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> result = new ArrayList<Path>();
            stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .forEach(result::add);
            return result;
        }
    }

    /**
     * Ensures the server's {@code eula.txt} agrees to the Minecraft EULA so
     * the server is allowed to boot. Idempotent — overwriting is cheap and
     * race-free.
     *
     * @param serverDir the server root directory
     * @throws IOException if the file cannot be written
     */
    private static void ensureEulaAccepted(Path serverDir) throws IOException {
        Path eula = serverDir.resolve("eula.txt");
        Files.write(eula, "eula=true\n".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Spawns a daemon thread that continuously drains the server's stdout into
     * {@link #output}. We buffer the full output so assertions can search and
     * tail it deterministically after the process exits.
     *
     * @param in the server's stdout {@link InputStream}
     * @return the running daemon thread (already started)
     */
    private Thread startStdoutReader(InputStream in) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // Pipe broken — happens normally during forced shutdown.
            }
        }, "e2e-stdout-reader");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Scans the server log for the first line that mentions our plugin being
     * enabled (case-insensitive contains on {@code "BuildBattleAI"} combined
     * with the canonical Bukkit enable marker, or our own {@code [BBAI]}
     * prefix). Returns {@code null} if no evidence is present.
     *
     * @param fullOutput the full buffered server output
     * @return a matching line, or {@code null} if none is found
     */
    private static String findFirstPluginMarker(String fullOutput) {
        for (String line : fullOutput.split("\\n")) {
            String lowered = line.toLowerCase();
            if (lowered.contains("buildbattleai") && (lowered.contains("enabling")
                    || lowered.contains("enabled") || lowered.contains("[bbai]")))
                return line;
        }
        return null;
    }

    /**
     * Pulls out lines that look like plugin-related ERROR/SEVERE entries.
     * Restricted to lines that explicitly mention BuildBattleAI/BBAI so that
     * unrelated 3rd-party warnings (e.g. ViaVersion, WorldEdit) don't fail
     * the build.
     * <p>
     * Tolerated patterns — known cross-version Bukkit warnings that are
     * cosmetically logged at ERROR level but do not actually break plugin
     * functionality:
     * <ul>
     *   <li>{@code "failed to register events for class ... because ... does not exist"}
     *       — Bukkit emits this when a {@code @EventHandler} references an
     *       event class introduced in a later API version (e.g.
     *       {@code PlayerSwapHandItemsEvent} on 1.8). The plugin still
     *       loads; only that specific listener is skipped, which is the
     *       intended behaviour on the older version.</li>
     * </ul>
     *
     * @param fullOutput the full buffered server output
     * @return mutable list of offending lines; empty means no errors
     */
    private static List<String> extractPluginErrors(String fullOutput) {
        List<String> errors = new ArrayList<String>();
        for (String line : fullOutput.split("\\n")) {
            boolean severe = line.contains("ERROR") || line.contains("SEVERE");
            boolean ours = line.contains("BuildBattleAI") || line.contains("BBAI");
            if (!(severe && ours))
                continue;
            if (isTolerableCrossVersionWarning(line))
                continue;
            errors.add(line);
        }
        return errors;
    }

    /**
     * Returns true when an ERROR-level line is one of the known cross-version
     * Bukkit warnings that do not actually break plugin functionality.
     *
     * @param line a single log line to inspect
     * @return {@code true} if the line is a known tolerable warning
     */
    private static boolean isTolerableCrossVersionWarning(String line) {
        return line.contains("failed to register events for class")
                && line.contains("does not exist");
    }

    /**
     * Returns at most the last {@code n} characters of {@code s}, with the
     * cut point snapped to the previous newline so the tail is line-aligned.
     *
     * @param s the string to trim
     * @param n maximum number of characters to return
     * @return a suffix of {@code s} no longer than {@code n} characters
     */
    private static String tail(String s, int n) {
        if (s.length() <= n)
            return s;
        int start = s.length() - n;
        int nl = s.indexOf('\n', start);
        return s.substring(nl >= 0 ? nl + 1 : start);
    }
}
