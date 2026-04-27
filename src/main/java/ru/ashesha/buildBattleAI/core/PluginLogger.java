package ru.ashesha.buildBattleAI.core;

import lombok.Getter;
import lombok.NonNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Configurable logging wrapper around the plugin's {@link Logger}.
 * <p>
 * Provides four severity levels — {@code DEBUG}, {@code INFO}, {@code WARN},
 * and {@code ERROR} — with a configurable minimum level that determines which
 * messages actually reach the server console. Debug messages are emitted at
 * Java's {@code INFO} level with a {@code [DEBUG]} prefix, because most Bukkit
 * console handlers silently drop anything below {@code INFO}.
 * <p>
 * <b>Design:</b> instead of checking the log level on every call, the logger
 * resolves a bundle of action lambdas (or no-ops) once when the level changes.
 * Each public log method is a straight delegation to the pre-resolved lambda
 * with no conditional branching in the hot path. The bundle is an immutable
 * {@link Actions} object published through a single {@code volatile} write,
 * so each log call performs exactly one volatile read followed by a plain
 * field access and a lambda invocation.
 * <p>
 * The current log level is read from {@code log-level} in {@code config.yml}
 * and applied by the config service during each enable cycle. Between plugin
 * construction and the first config read, the level defaults to {@link LogLevel#INFO}.
 *
 * @see LogLevel
 */
public class PluginLogger {

    // ── functional interfaces for action lambdas ─────────────────────────

    /** Action that accepts a pre-built message string. */
    @FunctionalInterface
    private interface MessageAction {
        void accept(String message);
    }

    /** Action that accepts a format pattern and arguments, formatting lazily. */
    @FunctionalInterface
    private interface FormatAction {
        void accept(String format, Object[] args);
    }

    /** Action that accepts a message and a throwable cause. */
    @FunctionalInterface
    private interface ThrowableAction {
        void accept(String message, Throwable cause);
    }

    // ── shared no-op singletons ──────────────────────────────────────────

    /** No-op message action — used when the level is disabled. */
    private static final MessageAction NO_OP_MSG = msg -> {};

    /** No-op format action — avoids {@code String.format} when disabled. */
    private static final FormatAction NO_OP_FMT = (fmt, args) -> {};

    /** No-op throwable action — skips exception logging when disabled. */
    private static final ThrowableAction NO_OP_ERR = (msg, t) -> {};

    // ── state ────────────────────────────────────────────────────────────

    /**
     * The underlying Bukkit/Java logger that actually writes to the console.
     */
    private final Logger logger;

    /**
     * Current minimum log level, exposed for external queries (e.g. config
     * display). Updated atomically alongside {@link #actions}.
     */
    @Getter
    private volatile LogLevel level;

    /**
     * Immutable bundle of pre-resolved action lambdas. A single volatile
     * read on each log call fetches the bundle; all subsequent field
     * accesses within it are plain (non-volatile) reads of {@code final}
     * fields, safe by the Java Memory Model's safe-publication guarantee.
     * <p>
     * Replaced atomically in {@link #setLevel(LogLevel)} — no partial
     * visibility is possible.
     */
    private volatile Actions actions;

    // ── constructor ──────────────────────────────────────────────────────

    /**
     * Creates a plugin logger wrapping the given Java logger.
     * The initial level is {@link LogLevel#INFO}.
     *
     * @param logger the underlying logger (typically {@code JavaPlugin.getLogger()})
     */
    public PluginLogger(@NonNull Logger logger) {
        this.logger = logger;
        applyLevel(LogLevel.INFO);
    }

    // ── level management ─────────────────────────────────────────────────

    /**
     * Updates the minimum log level. Resolves a new set of action lambdas
     * and publishes them via a single volatile write. Takes effect
     * immediately for all threads — subsequent log calls on any thread
     * will use the new level without any per-call checks.
     *
     * @param level the new minimum level
     */
    public void setLevel(@NonNull LogLevel level) {
        applyLevel(level);
    }

    /**
     * Resolves action lambdas for the given level and publishes the
     * immutable bundle. Called from the constructor and from
     * {@link #setLevel(LogLevel)}.
     */
    private void applyLevel(@NonNull LogLevel level) {
        this.level = level;
        this.actions = new Actions(logger, level);
    }

    // ── debug ────────────────────────────────────────────────────────────

    /**
     * Logs a debug message. When debug is enabled, emitted as {@code INFO}
     * with a {@code [DEBUG]} prefix. When disabled, a no-op — zero work.
     *
     * @param message the message
     */
    public void debug(String message) {
        actions.debugMsg.accept(message);
    }

    /**
     * Logs a formatted debug message. {@link String#format} is only
     * invoked when debug is actually enabled.
     *
     * @param format {@link String#format} pattern
     * @param args   format arguments
     */
    public void debug(String format, Object... args) {
        actions.debugFmt.accept(format, args);
    }

    // ── info ─────────────────────────────────────────────────────────────

    /**
     * Logs an informational message.
     *
     * @param message the message
     */
    public void info(String message) {
        actions.infoMsg.accept(message);
    }

    /**
     * Logs a formatted informational message.
     *
     * @param format {@link String#format} pattern
     * @param args   format arguments
     */
    public void info(String format, Object... args) {
        actions.infoFmt.accept(format, args);
    }

    // ── warn ─────────────────────────────────────────────────────────────

    /**
     * Logs a warning message.
     *
     * @param message the message
     */
    public void warn(String message) {
        actions.warnMsg.accept(message);
    }

    /**
     * Logs a formatted warning message.
     *
     * @param format {@link String#format} pattern
     * @param args   format arguments
     */
    public void warn(String format, Object... args) {
        actions.warnFmt.accept(format, args);
    }

    // ── error ────────────────────────────────────────────────────────────

    /**
     * Logs an error message.
     *
     * @param message the message
     */
    public void error(String message) {
        actions.errorMsg.accept(message);
    }

    /**
     * Logs a formatted error message.
     *
     * @param format {@link String#format} pattern
     * @param args   format arguments
     */
    public void error(String format, Object... args) {
        actions.errorFmt.accept(format, args);
    }

    /**
     * Logs an error message with the full exception stack trace.
     *
     * @param message the message
     * @param cause   the exception that caused the error
     */
    public void error(String message, Throwable cause) {
        actions.errorThrow.accept(message, cause);
    }

    // ── immutable action bundle ──────────────────────────────────────────

    /**
     * Immutable holder for pre-resolved log action lambdas.
     * <p>
     * Created once per level change in {@link #applyLevel(LogLevel)} and
     * published through a volatile write. All fields are {@code final},
     * so any thread that reads the volatile reference to this object is
     * guaranteed to see fully constructed field values — no additional
     * synchronization is needed for the individual fields.
     * <p>
     * For disabled levels, the corresponding fields point to shared
     * no-op singletons ({@link #NO_OP_MSG}, {@link #NO_OP_FMT},
     * {@link #NO_OP_ERR}), so the lambda invocation does literally
     * nothing — no format, no string concatenation, no I/O.
     */
    private static final class Actions {

        final MessageAction debugMsg;
        final FormatAction debugFmt;
        final MessageAction infoMsg;
        final FormatAction infoFmt;
        final MessageAction warnMsg;
        final FormatAction warnFmt;
        final MessageAction errorMsg;
        final FormatAction errorFmt;
        final ThrowableAction errorThrow;

        Actions(@NonNull Logger logger, @NonNull LogLevel level) {
            // Debug — emitted as INFO with [DEBUG] prefix so Bukkit consoles show it
            if (level.includes(LogLevel.DEBUG)) {
                debugMsg = msg -> logger.info("[DEBUG] " + msg);
                debugFmt = (fmt, args) -> logger.info("[DEBUG] " + String.format(fmt, args));
            } else {
                debugMsg = NO_OP_MSG;
                debugFmt = NO_OP_FMT;
            }

            // Info
            if (level.includes(LogLevel.INFO)) {
                infoMsg = logger::info;
                infoFmt = (fmt, args) -> logger.info(String.format(fmt, args));
            } else {
                infoMsg = NO_OP_MSG;
                infoFmt = NO_OP_FMT;
            }

            // Warn
            if (level.includes(LogLevel.WARN)) {
                warnMsg = logger::warning;
                warnFmt = (fmt, args) -> logger.warning(String.format(fmt, args));
            } else {
                warnMsg = NO_OP_MSG;
                warnFmt = NO_OP_FMT;
            }

            // Error
            if (level.includes(LogLevel.ERROR)) {
                errorMsg = logger::severe;
                errorFmt = (fmt, args) -> logger.severe(String.format(fmt, args));
                errorThrow = (msg, t) -> logger.log(Level.SEVERE, msg, t);
            } else {
                errorMsg = NO_OP_MSG;
                errorFmt = NO_OP_FMT;
                errorThrow = NO_OP_ERR;
            }
        }
    }

    // ── log level enum ───────────────────────────────────────────────────

    /**
     * Supported log levels, ordered from least to most verbose.
     * <p>
     * The level configured in {@code config.yml} determines the minimum
     * severity that reaches the console:
     * <ul>
     *     <li>{@link #OFF} — suppresses all plugin log output</li>
     *     <li>{@link #ERROR} — only errors</li>
     *     <li>{@link #WARN} — errors and warnings</li>
     *     <li>{@link #INFO} — errors, warnings, and informational messages (default)</li>
     *     <li>{@link #DEBUG} — all messages, including verbose diagnostics</li>
     * </ul>
     */
    public enum LogLevel {

        /** Suppresses all plugin log output. */
        OFF(0),
        /** Only critical errors that prevent normal operation. */
        ERROR(1),
        /** Errors and non-fatal issues that may need attention. */
        WARN(2),
        /** Errors, warnings, and normal operational messages. Default level. */
        INFO(3),
        /** All messages including verbose diagnostics for troubleshooting. */
        DEBUG(4);

        /** Numeric priority — higher value means more verbose. */
        private final int priority;

        LogLevel(int priority) {
            this.priority = priority;
        }

        /**
         * Checks whether this level includes messages of the given severity.
         * For example, {@code INFO.includes(WARN)} is {@code true} (INFO is
         * verbose enough to show warnings), but {@code ERROR.includes(INFO)}
         * is {@code false}.
         *
         * @param messageLevel the severity of the message being logged
         * @return {@code true} if this level allows the message through
         */
        public boolean includes(@NonNull LogLevel messageLevel) {
            return this.priority >= messageLevel.priority;
        }

        /**
         * Parses a log level from a config string, case-insensitively.
         * Returns {@link #INFO} if the string is {@code null} or unrecognized.
         *
         * @param name the level name (e.g. {@code "debug"}, {@code "INFO"}, {@code "off"})
         * @return the corresponding log level, or {@link #INFO} as default
         */
        public static LogLevel fromString(String name) {
            if (name == null)
                return INFO;
            switch (name.trim().toLowerCase()) {
                case "off":
                case "none":
                case "null":
                    return OFF;
                case "error":
                case "severe":
                    return ERROR;
                case "warn":
                case "warning":
                    return WARN;
                case "debug":
                case "fine":
                case "verbose":
                case "all":
                    return DEBUG;
                default:
                    return INFO;
            }
        }
    }
}
