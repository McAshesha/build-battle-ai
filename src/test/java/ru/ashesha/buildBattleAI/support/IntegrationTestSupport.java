package ru.ashesha.buildBattleAI.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;

/**
 * Common scaffolding for smoke / integration tests that boot a MockBukkit
 * server and need:
 * <ul>
 *   <li>a {@link ServerMock} created in {@code @BeforeEach} and torn down
 *       in {@code @AfterEach};</li>
 *   <li>a default {@code "world"} world ({@link ServerMock#addSimpleWorld})
 *       so production code that falls back to
 *       {@code Bukkit.getWorlds().get(0)} does not NPE;</li>
 *   <li>{@link #addSilentPlayer(String)} that returns a
 *       {@link SilentPlayerMock} avoiding the {@code playSound}
 *       {@code UnimplementedOperationException};</li>
 *   <li>a static-state-reset hook ({@link #resetStaticState()}) subclasses
 *       can override to wipe production-side static maps that survive
 *       across tests in the same JVM (e.g. {@code MLTestListener.SELECTIONS}).</li>
 * </ul>
 * Subclasses should NOT call {@code MockBukkit.mock()} themselves — this
 * base class owns the lifecycle.
 */
public abstract class IntegrationTestSupport {

    /** The MockBukkit server instance for the current test. */
    protected ServerMock server;

    /** The default world added to avoid {@code Bukkit.getWorlds().get(0)} NPE. */
    protected WorldMock defaultWorld;

    /** Players spawned via {@link #addSilentPlayer}; cleared on teardown. */
    private final List<SilentPlayerMock> spawnedPlayers = new ArrayList<SilentPlayerMock>();

    /**
     * Boots the MockBukkit server and adds a default {@code "world"} world.
     * Also calls {@link #resetStaticState()} before each test.
     */
    @BeforeEach
    final void bootServerMock() {
        server = MockBukkit.mock();
        defaultWorld = server.addSimpleWorld("world");
        resetStaticState();
    }

    /**
     * Tears down the MockBukkit server and clears player tracking.
     * Also calls {@link #resetStaticState()} after each test as
     * defence-in-depth against inter-test static pollution.
     */
    @AfterEach
    final void teardownServerMock() {
        try {
            resetStaticState();
        } finally {
            spawnedPlayers.clear();
            MockBukkit.unmock();
            server = null;
            defaultWorld = null;
        }
    }

    /**
     * Spawns a {@link SilentPlayerMock} and registers it with the server.
     * Centralising this avoids every test re-implementing the playSound
     * workaround described in CLAUDE.md.
     *
     * @param name the player's username
     * @return the newly spawned silent player
     */
    protected SilentPlayerMock addSilentPlayer(String name) {
        SilentPlayerMock p = new SilentPlayerMock(server, name);
        server.addPlayer(p);
        spawnedPlayers.add(p);
        return p;
    }

    /**
     * Subclasses override to wipe any production-side static state that
     * outlives a single test. The default implementation is a no-op.
     * Called both before and after each test as defence-in-depth.
     */
    protected void resetStaticState() {
        // override in subclasses as needed
    }
}
