package ru.ashesha.buildBattleAI.util;

import lombok.experimental.UtilityClass;

import java.io.File;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Verifies the integrity of the plugin JAR using standard Java code signing.
 * <p>
 * If the JAR contains digital signatures ({@code META-INF/*.SF}), every
 * non-directory, non-{@code META-INF/} entry must be validly signed. A
 * {@link SecurityException} from the JVM's built-in verifier (content
 * modified after signing) or an unsigned entry in an otherwise signed JAR
 * both indicate tampering.
 * <p>
 * Unsigned JARs (development builds where the {@code sign} Maven profile
 * was not activated) pass verification unconditionally, so the check never
 * blocks local development.
 * <p>
 * Typical integration point is {@code onLoad()} — before any services or
 * PacketEvents are initialised.
 */
@UtilityClass
public class JarIntegrityVerifier {

    /** Buffer size for draining JAR entry streams during verification. */
    private final int BUFFER_SIZE = 8192;

    /**
     * Verifies the integrity of the JAR that contains the given class.
     * <p>
     * The method is intentionally lenient for non-JAR environments (IDE,
     * test runners) and unsigned builds — it only fails when a JAR is
     * demonstrably signed <b>and</b> that signature is broken.
     *
     * @param pluginClass the main plugin class used to locate the JAR
     * @param logger      logger for diagnostic messages
     * @return {@code true} if the JAR is intact or unsigned,
     *         {@code false} if the signature has been tampered with
     */
    public boolean verify(Class<?> pluginClass, Logger logger) {
        try {
            File jarFile = resolveJarFile(pluginClass);
            if (jarFile == null) {
                logger.fine("Not running from a JAR file — skipping integrity verification.");
                return true;
            }
            return verifyJar(jarFile, logger);
        } catch (SecurityException e) {
            // Thrown by JarFile when a signed entry's digest does not match
            logger.severe("JAR signature verification failed: " + e.getMessage());
            return false;
        } catch (Exception e) {
            logger.severe("JAR integrity verification error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Overload accepting a {@link File} directly — useful for testing
     * and for callers that already know the JAR path.
     *
     * @param jarFile the JAR file to verify
     * @param logger  logger for diagnostic messages
     * @return {@code true} if the JAR is intact or unsigned,
     *         {@code false} if tampered
     */
    public boolean verify(File jarFile, Logger logger) {
        try {
            if (jarFile == null || !jarFile.isFile())
                return true;
            return verifyJar(jarFile, logger);
        } catch (SecurityException e) {
            logger.severe("JAR signature verification failed: " + e.getMessage());
            return false;
        } catch (Exception e) {
            logger.severe("JAR integrity verification error: " + e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the JAR file that contains the given class via its
     * {@link CodeSource}. Returns {@code null} when the class is not
     * loaded from a JAR (e.g. IDE class-path, test runner).
     */
    private File resolveJarFile(Class<?> clazz) throws URISyntaxException {
        CodeSource codeSource = clazz.getProtectionDomain().getCodeSource();
        if (codeSource == null)
            return null;

        URL location = codeSource.getLocation();
        if (location == null)
            return null;

        File file = new File(location.toURI());
        if (!file.isFile() || !file.getName().endsWith(".jar"))
            return null;

        return file;
    }

    /**
     * Opens the JAR with verification enabled and walks every entry.
     * <ol>
     *     <li>If no {@code .SF} signature files exist → unsigned build → pass.</li>
     *     <li>For each non-META-INF entry, the stream is fully drained so the
     *         JVM computes and checks the digest. A mismatch throws
     *         {@link SecurityException}.</li>
     *     <li>After draining, {@link JarEntry#getCodeSigners()} is checked —
     *         a {@code null}/empty result in an otherwise signed JAR means
     *         the entry was added after signing → fail.</li>
     * </ol>
     */
    private boolean verifyJar(File file, Logger logger) throws Exception {
        try (JarFile jar = new JarFile(file, true)) {
            // Quick check: does this JAR contain any signature files at all?
            if (!hasSignatureFiles(jar)) {
                logger.info("JAR is unsigned — integrity verification skipped.");
                return true;
            }

            logger.info("Signed JAR detected — verifying integrity...");
            byte[] buffer = new byte[BUFFER_SIZE];
            Enumeration<JarEntry> entries = jar.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                // Directories and META-INF signature infrastructure are not
                // covered by code signers — skip them.
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/"))
                    continue;

                // Fully drain the entry so the JVM verifies its digest.
                // SecurityException is thrown here if content was modified.
                try (InputStream is = jar.getInputStream(entry)) {
                    //noinspection StatementWithEmptyBody
                    while (is.read(buffer) != -1) ;
                }

                // An entry with no code signers in a signed JAR was injected
                // after signing — treat as tampering.
                CodeSigner[] signers = entry.getCodeSigners();
                if (signers == null || signers.length == 0) {
                    logger.severe("Unsigned entry in signed JAR: " + entry.getName());
                    return false;
                }
            }

            logger.info("JAR integrity verification passed.");
            return true;
        }
    }

    /**
     * Returns {@code true} if the JAR's META-INF contains at least one
     * {@code .SF} (Signature File) entry, which is the standard marker
     * that the JAR has been signed with {@code jarsigner}.
     */
    private boolean hasSignatureFiles(JarFile jar) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith("META-INF/") && name.endsWith(".SF"))
                return true;
        }
        return false;
    }

}
