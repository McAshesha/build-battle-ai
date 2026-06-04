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

        // Refresh the plugin JAR if a newer build exists. We never proceed
        // without *some* jar in plugins/ — that would be an undetectable
        // false pass.
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

        ProcessBuilder pb = new ProcessBuilder("bash", startScript.toAbsolutePath().toString())
                .directory(serverDir.toFile())
                .redirectErrorStream(true);
        Process server = pb.start();

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

            sendCommand(server, "stop");
            boolean exited = server.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                server.destroyForcibly();
                fail(serverFlavor() + " did not stop within "
                        + SHUTDOWN_TIMEOUT_SECONDS + " s; process forcibly killed.\n"
                        + "Tail:\n" + tail(output.toString(), 4000));
            }
            assertEquals(0, server.exitValue(),
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

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Searches {@code target/} for the most recently built shaded JAR
     * (excluding {@code -lite}, {@code -sources}, {@code -javadoc}).
     *
     * @return path to the freshest plugin jar, or {@code null} if no build
     *         has been produced yet
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
     */
    private static void ensureEulaAccepted(Path serverDir) throws IOException {
        Path eula = serverDir.resolve("eula.txt");
        Files.write(eula, "eula=true\n".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Spawns a daemon thread that continuously drains the server's stdout
     * into {@link #output}. We buffer the full output so assertions can
     * search and tail it deterministically after the process exits.
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
     * Polls the buffered server output for {@code needle} until it appears
     * or the timeout elapses. Polling avoids a separate condition-variable
     * dance with the reader thread.
     */
    private void waitForMarker(String needle, long timeoutSeconds, String failureMessage)
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
     * Writes {@code command\n} to the server's stdin, flushing immediately
     * so the server console picks it up on the next tick.
     */
    private static void sendCommand(Process server, String command) throws IOException {
        OutputStream stdin = server.getOutputStream();
        stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    /**
     * Scans the server log for the first line that mentions our plugin
     * being enabled (case-insensitive contains on {@code "BuildBattleAI"}
     * combined with the canonical Bukkit enable marker, or our own
     * {@code [BBAI]} prefix). Returns {@code null} if no evidence is
     * present.
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
     */
    private static boolean isTolerableCrossVersionWarning(String line) {
        return line.contains("failed to register events for class")
                && line.contains("does not exist");
    }

    /**
     * Returns at most the last {@code n} characters of {@code s}, with the
     * cut point snapped to the previous newline so the tail is line-aligned.
     */
    private static String tail(String s, int n) {
        if (s.length() <= n)
            return s;
        int start = s.length() - n;
        int nl = s.indexOf('\n', start);
        return s.substring(nl >= 0 ? nl + 1 : start);
    }
}
