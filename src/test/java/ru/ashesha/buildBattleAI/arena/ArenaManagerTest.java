package ru.ashesha.buildBattleAI.arena;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import ru.ashesha.buildBattleAI.BuildBattleAI;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ArenaManager}.
 * <p>
 * Currently a stub — verifies construction and shutdown behavior.
 */
class ArenaManagerTest {

    private BuildBattleAI plugin;
    private Logger logger;
    private ArenaManager manager;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        manager = new ArenaManager(plugin);
    }

    @Test
    void constructorRejectsNullPlugin() {
        assertThrows(NullPointerException.class, () -> new ArenaManager(null));
    }

    @Test
    void enableLogsMessage() {
        manager.enable();
        verify(logger).info("ArenaManager enabled.");
    }

    @Test
    void shutdownLogsMessage() {
        manager.shutdown();
        verify(logger).info("ArenaManager shut down.");
    }

    @Test
    void shutdownCanBeCalledMultipleTimes() {
        manager.shutdown();
        manager.shutdown();
        verify(logger, times(2)).info("ArenaManager shut down.");
    }

    @Test
    void reloadCallsShutdownThenEnable() {
        // Default PluginService.reload() must run shutdown before enable so
        // the service is re-bootstrapped exactly as on a fresh server start.
        manager.reload();
        InOrder order = inOrder(logger);
        order.verify(logger).info("ArenaManager shut down.");
        order.verify(logger).info("ArenaManager enabled.");
    }
}
