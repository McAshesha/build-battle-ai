package ru.ashesha.buildBattleAI.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
}
