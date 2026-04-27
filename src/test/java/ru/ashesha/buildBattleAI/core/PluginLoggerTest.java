package ru.ashesha.buildBattleAI.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.core.PluginLogger.LogLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PluginLogger} level filtering and formatting.
 * <p>
 * Uses a custom {@link Handler} to capture log output without relying on
 * console output, which makes assertions deterministic.
 */
class PluginLoggerTest {

    private Logger javaLogger;
    private PluginLogger pluginLogger;
    private List<LogRecord> records;

    @BeforeEach
    void setUp() {
        javaLogger = Logger.getLogger("PluginLoggerTest." + System.nanoTime());
        javaLogger.setUseParentHandlers(false);
        // Allow all levels through the Java logger — PluginLogger handles filtering
        javaLogger.setLevel(Level.ALL);

        records = new ArrayList<>();
        javaLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        pluginLogger = new PluginLogger(javaLogger);
    }

    // ── level filtering tests ────────────────────────────────────────────

    @Test
    void defaultLevelIsInfo() {
        assertEquals(LogLevel.INFO, pluginLogger.getLevel());
    }

    @Test
    void infoLevelAllowsInfoWarnError() {
        pluginLogger.setLevel(LogLevel.INFO);

        pluginLogger.debug("d");
        pluginLogger.info("i");
        pluginLogger.warn("w");
        pluginLogger.error("e");

        // Debug should be filtered, the other three should pass
        assertEquals(3, records.size());
        assertEquals("i", records.get(0).getMessage());
        assertEquals("w", records.get(1).getMessage());
        assertEquals("e", records.get(2).getMessage());
    }

    @Test
    void debugLevelAllowsAllMessages() {
        pluginLogger.setLevel(LogLevel.DEBUG);

        pluginLogger.debug("d");
        pluginLogger.info("i");
        pluginLogger.warn("w");
        pluginLogger.error("e");

        assertEquals(4, records.size());
    }

    @Test
    void errorLevelFiltersInfoAndWarn() {
        pluginLogger.setLevel(LogLevel.ERROR);

        pluginLogger.debug("d");
        pluginLogger.info("i");
        pluginLogger.warn("w");
        pluginLogger.error("e");

        assertEquals(1, records.size());
        assertEquals("e", records.get(0).getMessage());
    }

    @Test
    void warnLevelFiltersInfoAndDebug() {
        pluginLogger.setLevel(LogLevel.WARN);

        pluginLogger.debug("d");
        pluginLogger.info("i");
        pluginLogger.warn("w");
        pluginLogger.error("e");

        assertEquals(2, records.size());
        assertEquals("w", records.get(0).getMessage());
        assertEquals("e", records.get(1).getMessage());
    }

    @Test
    void offLevelSuppressesAll() {
        pluginLogger.setLevel(LogLevel.OFF);

        pluginLogger.debug("d");
        pluginLogger.info("i");
        pluginLogger.warn("w");
        pluginLogger.error("e");

        assertTrue(records.isEmpty());
    }

    // ── formatting tests ─────────────────────────────────────────────────

    @Test
    void debugMessageHasDebugPrefix() {
        pluginLogger.setLevel(LogLevel.DEBUG);
        pluginLogger.debug("hello");

        assertEquals(1, records.size());
        assertEquals("[DEBUG] hello", records.get(0).getMessage());
    }

    @Test
    void debugFormattedHasDebugPrefix() {
        pluginLogger.setLevel(LogLevel.DEBUG);
        pluginLogger.debug("value=%d", 42);

        assertEquals("[DEBUG] value=42", records.get(0).getMessage());
    }

    @Test
    void infoFormattedAppliesStringFormat() {
        pluginLogger.info("Hello %s, count=%d", "world", 5);
        assertEquals("Hello world, count=5", records.get(0).getMessage());
    }

    @Test
    void warnFormattedAppliesStringFormat() {
        pluginLogger.warn("Missing '%s' in config", "key.name");
        assertEquals("Missing 'key.name' in config", records.get(0).getMessage());
    }

    @Test
    void errorFormattedAppliesStringFormat() {
        pluginLogger.error("Failed after %d retries", 3);
        assertEquals("Failed after 3 retries", records.get(0).getMessage());
    }

    // ── Java log level mapping tests ───────────────────────────────────���─

    @Test
    void debugUsesInfoJavaLevel() {
        pluginLogger.setLevel(LogLevel.DEBUG);
        pluginLogger.debug("msg");
        assertEquals(Level.INFO, records.get(0).getLevel());
    }

    @Test
    void infoUsesInfoJavaLevel() {
        pluginLogger.info("msg");
        assertEquals(Level.INFO, records.get(0).getLevel());
    }

    @Test
    void warnUsesWarningJavaLevel() {
        pluginLogger.warn("msg");
        assertEquals(Level.WARNING, records.get(0).getLevel());
    }

    @Test
    void errorUsesSevereJavaLevel() {
        pluginLogger.error("msg");
        assertEquals(Level.SEVERE, records.get(0).getLevel());
    }

    @Test
    void errorWithThrowableIncludesException() {
        RuntimeException ex = new RuntimeException("boom");
        pluginLogger.error("oops", ex);

        assertEquals(1, records.size());
        assertEquals(Level.SEVERE, records.get(0).getLevel());
        assertEquals("oops", records.get(0).getMessage());
        assertSame(ex, records.get(0).getThrown());
    }

    // ── LogLevel.fromString tests ────────────────────────────────────────

    @Test
    void fromStringParsesAllLevels() {
        assertEquals(LogLevel.OFF, LogLevel.fromString("off"));
        assertEquals(LogLevel.ERROR, LogLevel.fromString("error"));
        assertEquals(LogLevel.WARN, LogLevel.fromString("warn"));
        assertEquals(LogLevel.INFO, LogLevel.fromString("info"));
        assertEquals(LogLevel.DEBUG, LogLevel.fromString("debug"));
    }

    @Test
    void fromStringIsCaseInsensitive() {
        assertEquals(LogLevel.DEBUG, LogLevel.fromString("DEBUG"));
        assertEquals(LogLevel.DEBUG, LogLevel.fromString("Debug"));
        assertEquals(LogLevel.ERROR, LogLevel.fromString("ERROR"));
    }

    @Test
    void fromStringAcceptsAliases() {
        assertEquals(LogLevel.OFF, LogLevel.fromString("none"));
        assertEquals(LogLevel.ERROR, LogLevel.fromString("severe"));
        assertEquals(LogLevel.WARN, LogLevel.fromString("warning"));
        assertEquals(LogLevel.DEBUG, LogLevel.fromString("fine"));
        assertEquals(LogLevel.DEBUG, LogLevel.fromString("verbose"));
        assertEquals(LogLevel.DEBUG, LogLevel.fromString("all"));
    }

    @Test
    void fromStringDefaultsToInfoForNull() {
        assertEquals(LogLevel.INFO, LogLevel.fromString(null));
    }

    @Test
    void fromStringDefaultsToInfoForUnknown() {
        assertEquals(LogLevel.INFO, LogLevel.fromString("potato"));
        assertEquals(LogLevel.INFO, LogLevel.fromString(""));
    }

    @Test
    void fromStringTrimsWhitespace() {
        assertEquals(LogLevel.DEBUG, LogLevel.fromString("  debug  "));
    }

    // ── LogLevel.includes tests ──────────────────────────────────────────

    @Test
    void includesReturnsTrueForSameLevel() {
        for (LogLevel level : LogLevel.values())
            if (level != LogLevel.OFF)
                assertTrue(level.includes(level));
    }

    @Test
    void debugIncludesAllLevels() {
        assertTrue(LogLevel.DEBUG.includes(LogLevel.DEBUG));
        assertTrue(LogLevel.DEBUG.includes(LogLevel.INFO));
        assertTrue(LogLevel.DEBUG.includes(LogLevel.WARN));
        assertTrue(LogLevel.DEBUG.includes(LogLevel.ERROR));
    }

    @Test
    void errorDoesNotIncludeInfoOrDebug() {
        assertFalse(LogLevel.ERROR.includes(LogLevel.INFO));
        assertFalse(LogLevel.ERROR.includes(LogLevel.DEBUG));
    }

    @Test
    void offDoesNotIncludeAnything() {
        assertFalse(LogLevel.OFF.includes(LogLevel.ERROR));
        assertFalse(LogLevel.OFF.includes(LogLevel.DEBUG));
    }

    // ── level change visibility test ─────────────────────────────────────

    @Test
    void levelChangeIsImmediatelyVisible() {
        pluginLogger.setLevel(LogLevel.ERROR);
        pluginLogger.info("filtered");
        assertTrue(records.isEmpty());

        pluginLogger.setLevel(LogLevel.DEBUG);
        pluginLogger.info("visible");
        assertEquals(1, records.size());
    }
}
