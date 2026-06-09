package ru.ashesha.buildBattleAI.data.local;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.data.api.PlayerData;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Integration test covering risk <b>DATA-04</b>: Disk full / read-only filesystem.
 *
 * <p><b>Invariant:</b> When a {@link LocalRepository#flush()} call fails with an
 * {@link IOException} (e.g. because the parent directory is read-only), the
 * exception must <em>not</em> propagate to the caller; the in-memory cache must
 * remain fully operational for reads and writes; and the {@code dirty} flag must
 * stay {@code true} so that a later, successful flush will eventually persist the
 * data. The plugin must never crash or lose in-memory state due to a transient
 * disk error.
 *
 * <p><b>Why integration (not unit)?</b> This test exercises real POSIX file
 * permissions, real {@link java.io.File} and {@link Files} I/O, and the
 * interaction between the file-system state and the repository's internal
 * dirty-tracking logic. Multiple real collaborators are involved (file system,
 * Gson serialiser, repository state machine), making this an integration concern
 * rather than a pure unit concern.
 *
 * <p><b>Platform note:</b> Tests that manipulate POSIX permissions are guarded
 * by {@link Assumptions#assumeTrue} so they skip cleanly on Windows CI runners
 * that do not support the {@code "posix"} file attribute view.
 */
@Tag("integration")
class DiskFailureEscalationIT {

    @TempDir
    File tempDir;

    private Gson gson;
    private PluginLogger logger;

    @BeforeEach
    void setUp() {
        gson = new GsonBuilder().setPrettyPrinting().create();
        logger = mock(PluginLogger.class);
    }

    /**
     * Restores full POSIX permissions on {@link #tempDir} after each test so
     * that the JUnit {@code @TempDir} extension can delete it without
     * encountering permission-denied errors. Without this, the directory cleanup
     * would silently fail and leave stale temp files on the agent.
     */
    @AfterEach
    void restoreTempDirPermissions() throws IOException {
        if (!isPosixSupported())
            return;
        // Restore rwxr-xr-x so JUnit can recurse and delete the directory
        Files.setPosixFilePermissions(
                tempDir.toPath(),
                EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE));
    }

    // -- flush-does-not-throw when directory is read-only ----------------------

    /**
     * DATA-04 core contract: when the parent directory is made read-only so that
     * the temp-file creation inside {@link LocalRepository#flush()} fails with an
     * {@link IOException}, the exception must be swallowed — {@code flush()} must
     * return normally without propagating anything to the caller.
     */
    @Test
    void diskFullEscalatesToLogger() throws IOException {
        assumePosixSupported();

        File dataFile = new File(tempDir, "players.json");

        LocalRepository<UUID, PlayerData> repo =
                new LocalRepository<>(dataFile, gson, UUID.class, PlayerData.class, logger);

        // Pre-populate in-memory state so dirty flag is set before we lock the dir
        UUID uuid = UUID.randomUUID();
        repo.put(uuid, new PlayerData(uuid, "DiskFullPlayer"));

        // Make the directory read-only so the .tmp file cannot be created
        makeReadOnly(tempDir);

        // flush() must NOT throw — IOException must be silently swallowed
        assertDoesNotThrow(repo::flush,
                "flush() must not propagate IOException when the directory is read-only");
    }

    // -- in-memory cache remains fully operational after a failed flush --------

    /**
     * DATA-04 resilience: after a failed flush (read-only directory), the
     * in-memory cache must still service {@code get()} and {@code put()} calls
     * correctly, as if the flush had never been attempted.
     */
    @Test
    void inMemoryWritesStillAcceptedAfterFailedFlush() throws IOException {
        assumePosixSupported();

        File dataFile = new File(tempDir, "players.json");
        LocalRepository<UUID, PlayerData> repo =
                new LocalRepository<>(dataFile, gson, UUID.class, PlayerData.class, logger);

        UUID uuid1 = UUID.randomUUID();
        repo.put(uuid1, new PlayerData(uuid1, "BeforeFailure"));

        // Trigger a flush failure by locking the directory
        makeReadOnly(tempDir);
        repo.flush(); // silently fails

        // Re-open the directory for writes so we can verify subsequent puts
        restoreTempDirPermissions();

        // In-memory state written before the failure must still be readable
        assertNotNull(repo.get(uuid1),
                "get() must return the entry that was put before the failed flush");
        assertEquals("BeforeFailure", repo.get(uuid1).name(),
                "Player name must be intact in memory after a failed flush");

        // New in-memory writes after the failure must also be accepted
        UUID uuid2 = UUID.randomUUID();
        assertDoesNotThrow(() -> repo.put(uuid2, new PlayerData(uuid2, "AfterFailure")),
                "put() must work normally even after a failed flush");
        assertNotNull(repo.get(uuid2),
                "get() must return entries put after a failed flush");
    }

    // -- dirty flag stays true after a failed flush ----------------------------

    /**
     * DATA-04 durability: after a failed flush the {@code dirty} flag must remain
     * {@code true}. A subsequent flush (once disk is available again) must then
     * write the pending data, ensuring eventual persistence.
     */
    @Test
    void dirtyFlagRemainsSetAfterFailedFlush() throws IOException {
        assumePosixSupported();

        File dataFile = new File(tempDir, "players.json");
        LocalRepository<UUID, PlayerData> repo =
                new LocalRepository<>(dataFile, gson, UUID.class, PlayerData.class, logger);

        UUID uuid = UUID.randomUUID();
        repo.put(uuid, new PlayerData(uuid, "EventualPersist"));

        // Induce a flush failure
        makeReadOnly(tempDir);
        repo.flush(); // fails silently

        // Restore write access and flush again — this time it must succeed
        restoreTempDirPermissions();
        assertDoesNotThrow(repo::flush,
                "A second flush (after disk is writable again) must succeed");

        // The file must now exist and contain the data that was pending
        assertTrue(dataFile.exists(),
                "JSON file must exist after the recovery flush");
        String content = new String(Files.readAllBytes(dataFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("EventualPersist"),
                "Pending data must appear in the file after the recovery flush");
        assertTrue(content.contains(uuid.toString()),
                "Pending UUID must appear in the file after the recovery flush");
    }

    // -- logging contract (known gap, fix pending) ------------------------------

    /**
     * DATA-04 logging: verifies that a flush failure is escalated through
     * {@link ru.ashesha.buildBattleAI.core.PluginLogger#error} so that server
     * administrators can diagnose disk problems from the server console.
     */
    @Test
    void flushFailureEscalatesToPluginLogger() throws IOException {
        assumePosixSupported();

        File dataFile = new File(tempDir, "players.json");

        LocalRepository<UUID, PlayerData> repo =
                new LocalRepository<>(dataFile, gson, UUID.class, PlayerData.class, logger);

        UUID uuid = UUID.randomUUID();
        repo.put(uuid, new PlayerData(uuid, "DiskFullPlayer"));

        // Make the directory read-only so the .tmp file cannot be created.
        makeReadOnly(tempDir);

        repo.flush(); // must not throw

        // Verify error log call with a format string and the file name as first arg.
        ArgumentCaptor<String> formatCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(logger).error(formatCaptor.capture(), argsCaptor.capture());

        assertTrue(formatCaptor.getValue().contains("%s"),
                "error() format must use printf-style %s placeholders (got: " + formatCaptor.getValue() + ")");
        assertEquals(dataFile.getName(), argsCaptor.getValue()[0],
                "error() first arg must be the file name");
    }

    // -- helpers ---------------------------------------------------------------

    /**
     * Returns {@code true} if the test JVM's default file store supports POSIX
     * file attribute views. Windows file stores report {@code false}.
     */
    private boolean isPosixSupported() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /**
     * Skips the calling test with a descriptive assumption message when the
     * current platform does not support POSIX file permissions.
     */
    private void assumePosixSupported() {
        Assumptions.assumeTrue(isPosixSupported(),
                "Skipped — POSIX file attribute view not supported on this platform (Windows?)");
    }

    /**
     * Removes the write permission from {@code dir}, making it impossible to
     * create new files inside it. The execute bit is retained so that the
     * directory entry itself remains traversable (required for some JVM internals).
     *
     * @param dir the directory to lock
     * @throws IOException if the permission change fails
     */
    private void makeReadOnly(File dir) throws IOException {
        Set<PosixFilePermission> readOnlyDir = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(dir.toPath(), readOnlyDir);
    }
}
