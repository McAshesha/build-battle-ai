package ru.ashesha.buildBattleAI.arena;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginLogger;

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
    private PluginLogger pluginLogger;
    private ArenaManager manager;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        pluginLogger = mock(PluginLogger.class);
        when(plugin.getPluginLogger()).thenReturn(pluginLogger);
        manager = new ArenaManager(plugin);
    }

    @Test
    void constructorRejectsNullPlugin() {
        assertThrows(NullPointerException.class, () -> new ArenaManager(null));
    }

    @Test
    void enableLogsMessage() {
        manager.enable();
        verify(pluginLogger).info("ArenaManager enabled.");
    }

    @Test
    void shutdownLogsMessage() {
        manager.shutdown();
        verify(pluginLogger).debug("ArenaManager shut down.");
    }

    @Test
    void shutdownCanBeCalledMultipleTimes() {
        manager.shutdown();
        manager.shutdown();
        verify(pluginLogger, times(2)).debug("ArenaManager shut down.");
    }

    @Test
    void reloadCallsShutdownThenEnable() {
        // Default PluginService.reload() must run shutdown before enable so
        // the service is re-bootstrapped exactly as on a fresh server start.
        manager.reload();
        InOrder order = inOrder(pluginLogger);
        order.verify(pluginLogger).debug("ArenaManager shut down.");
        order.verify(pluginLogger).info("ArenaManager enabled.");
    }
}
