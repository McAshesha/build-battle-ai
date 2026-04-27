package ru.ashesha.buildBattleAI.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginLogger;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GameManager}.
 * <p>
 * Currently a stub — verifies construction and shutdown behavior.
 */
class GameManagerTest {

    private BuildBattleAI plugin;
    private PluginLogger pluginLogger;
    private GameManager manager;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        pluginLogger = mock(PluginLogger.class);
        when(plugin.getPluginLogger()).thenReturn(pluginLogger);
        manager = new GameManager(plugin);
    }

    @Test
    void constructorRejectsNullPlugin() {
        assertThrows(NullPointerException.class, () -> new GameManager(null));
    }

    @Test
    void enableLogsMessage() {
        manager.enable();
        verify(pluginLogger).info("GameManager enabled.");
    }

    @Test
    void shutdownLogsMessage() {
        manager.shutdown();
        verify(pluginLogger).debug("GameManager shut down.");
    }

    @Test
    void shutdownCanBeCalledMultipleTimes() {
        manager.shutdown();
        manager.shutdown();
        verify(pluginLogger, times(2)).debug("GameManager shut down.");
    }

    @Test
    void reloadCallsShutdownThenEnable() {
        // Default PluginService.reload() must run shutdown before enable so
        // the service is re-bootstrapped exactly as on a fresh server start.
        manager.reload();
        InOrder order = inOrder(pluginLogger);
        order.verify(pluginLogger).debug("GameManager shut down.");
        order.verify(pluginLogger).info("GameManager enabled.");
    }
}
