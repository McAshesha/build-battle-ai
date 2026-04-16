package ru.ashesha.buildBattleAI.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import ru.ashesha.buildBattleAI.BuildBattleAI;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GameManager}.
 * <p>
 * Currently a stub — verifies construction and shutdown behavior.
 */
class GameManagerTest {

    private BuildBattleAI plugin;
    private Logger logger;
    private GameManager manager;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        manager = new GameManager(plugin);
    }

    @Test
    void constructorRejectsNullPlugin() {
        assertThrows(NullPointerException.class, () -> new GameManager(null));
    }

    @Test
    void enableLogsMessage() {
        manager.enable();
        verify(logger).info("GameManager enabled.");
    }

    @Test
    void shutdownLogsMessage() {
        manager.shutdown();
        verify(logger).info("GameManager shut down.");
    }

    @Test
    void shutdownCanBeCalledMultipleTimes() {
        manager.shutdown();
        manager.shutdown();
        verify(logger, times(2)).info("GameManager shut down.");
    }

    @Test
    void reloadCallsShutdownThenEnable() {
        // Default PluginService.reload() must run shutdown before enable so
        // the service is re-bootstrapped exactly as on a fresh server start.
        manager.reload();
        InOrder order = inOrder(logger);
        order.verify(logger).info("GameManager shut down.");
        order.verify(logger).info("GameManager enabled.");
    }
}
