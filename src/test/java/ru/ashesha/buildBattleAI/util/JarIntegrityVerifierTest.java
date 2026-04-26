package ru.ashesha.buildBattleAI.util;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JarIntegrityVerifier}.
 * <p>
 * Covers three scenarios:
 * <ol>
 *     <li>Unsigned JAR → verification passes (development builds).</li>
 *     <li>Signed JAR with intact content → verification passes.</li>
 *     <li>Signed JAR with tampered content → verification fails.</li>
 * </ol>
 * Scenarios 2 and 3 require {@code keytool} and {@code jarsigner} on the
 * PATH — if unavailable the tests are skipped gracefully.
 */
class JarIntegrityVerifierTest {

    private static final Logger LOG = Logger.getLogger(JarIntegrityVerifierTest.class.getName());

    /** Shared temp directory for test JARs and keystore. */
    @TempDir
    static Path tempDir;

    /** Whether jarsigner tooling is available on this machine. */
    private static boolean signingAvailable;

    /** Paths reused across tests. */
    private static File keystore;
    private static File unsignedJar;
    private static File signedJar;
    private static File tamperedJar;

    @BeforeAll
    static void setUp() throws Exception {
        // --- Create an unsigned JAR with a single dummy class entry ---
        unsignedJar = tempDir.resolve("unsigned.jar").toFile();
        createDummyJar(unsignedJar);

        // --- Try to generate a keystore and sign a JAR ---
        keystore = tempDir.resolve("test.jks").toFile();
        signingAvailable = tryGenerateKeystore(keystore);
        if (!signingAvailable)
            return;

        // --- Create and sign a JAR (intact) ---
        signedJar = tempDir.resolve("signed.jar").toFile();
        createDummyJar(signedJar);
        signJar(signedJar);

        // --- Create, sign, then tamper a JAR ---
        tamperedJar = tempDir.resolve("tampered.jar").toFile();
        createDummyJar(tamperedJar);
        signJar(tamperedJar);
        tamperJar(tamperedJar);
    }

    // ---------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------

    @Test
    void unsignedJarPassesVerification() {
        assertTrue(JarIntegrityVerifier.verify(unsignedJar, LOG),
                "Unsigned JAR should pass verification");
    }

    @Test
    void signedIntactJarPassesVerification() {
        if (!signingAvailable) {
            LOG.warning("keytool/jarsigner not available — skipping signed JAR test");
            return;
        }
        assertTrue(JarIntegrityVerifier.verify(signedJar, LOG),
                "Signed intact JAR should pass verification");
    }

    @Test
    void tamperedSignedJarFailsVerification() {
        if (!signingAvailable) {
            LOG.warning("keytool/jarsigner not available — skipping tampered JAR test");
            return;
        }
        assertFalse(JarIntegrityVerifier.verify(tamperedJar, LOG),
                "Tampered signed JAR should fail verification");
    }

    @Test
    void nullFilePassesVerification() {
        assertTrue(JarIntegrityVerifier.verify((File) null, LOG),
                "Null file should pass (lenient for non-JAR environments)");
    }

    @Test
    void nonExistentFilePassesVerification() {
        File ghost = new File("/tmp/does_not_exist_" + System.nanoTime() + ".jar");
        assertTrue(JarIntegrityVerifier.verify(ghost, LOG),
                "Non-existent file should pass (lenient)");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Creates a minimal JAR with a single dummy entry.
     */
    private static void createDummyJar(File file) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(file), manifest)) {
            // Add a dummy class-like entry
            jos.putNextEntry(new JarEntry("com/example/Dummy.class"));
            jos.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52});
            jos.closeEntry();

            // Add a second entry for thoroughness
            jos.putNextEntry(new JarEntry("com/example/Helper.class"));
            jos.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52});
            jos.closeEntry();
        }
    }

    /**
     * Generates a self-signed keystore via {@code keytool}. Returns
     * {@code true} on success, {@code false} if the tool is unavailable.
     */
    private static boolean tryGenerateKeystore(File ks) {
        try {
            Process p = new ProcessBuilder(
                    "keytool", "-genkeypair",
                    "-alias", "test",
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "1",
                    "-keystore", ks.getAbsolutePath(),
                    "-storepass", "testpass",
                    "-keypass", "testpass",
                    "-dname", "CN=Test"
            ).redirectErrorStream(true).start();
            int exit = p.waitFor();
            return exit == 0 && ks.exists();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Signs a JAR with the test keystore via {@code jarsigner}.
     */
    private static void signJar(File jar) throws Exception {
        Process p = new ProcessBuilder(
                "jarsigner",
                "-keystore", keystore.getAbsolutePath(),
                "-storepass", "testpass",
                "-keypass", "testpass",
                jar.getAbsolutePath(),
                "test"
        ).redirectErrorStream(true).start();
        int exit = p.waitFor();
        if (exit != 0)
            throw new RuntimeException("jarsigner failed with exit code " + exit);
    }

    /**
     * Corrupts a signed JAR by overwriting bytes in the middle of
     * a class entry, breaking the digest without removing the signature.
     */
    private static void tamperJar(File jar) throws Exception {
        // Re-create the JAR with modified content but preserve the signature
        // files from the original — this simulates content tampering.
        // The simplest way: just flip some bytes in the file at a known offset
        // past the signature entries.
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(jar, "rw");
        try {
            long len = raf.length();
            // Write garbage near the end (likely inside a class entry)
            raf.seek(len - 20);
            raf.write(new byte[]{0x00, 0x00, 0x00, 0x00, 0x00});
        } finally {
            raf.close();
        }
    }

    @AfterAll
    static void cleanUp() {
        // TempDir handles cleanup automatically
    }

}
