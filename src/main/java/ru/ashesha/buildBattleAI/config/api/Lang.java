package ru.ashesha.buildBattleAI.config.api;

import java.util.List;

/**
 * Read-only accessor for a single language's translation messages.
 * <p>
 * Each {@code Lang} instance wraps one language file (e.g. {@code en.yml})
 * and provides convenient message retrieval with placeholder substitution.
 * <p>
 * <b>Fallback behavior:</b> if a key is missing in this language, the lookup
 * automatically falls back to the default language configured in
 * {@code config.yml}. If the key is not found there either, the raw key name
 * is returned — a visible placeholder is always better than {@code null} or
 * an empty string for debugging missing translations.
 * <p>
 * <b>Placeholder substitution:</b> the {@link #get(String, Object...)} overload
 * accepts alternating key-value pairs:
 * <pre>{@code
 * lang.get("join-message", "%player%", player.getName(), "%arena%", arenaName);
 * }</pre>
 * <p>
 * <b>Design note for per-player localization:</b> game logic retrieves the
 * appropriate {@code Lang} via {@link BBAIConfigService#getLang(String)} using
 * the player's chosen language, then reads messages through this interface.
 * This decouples the translation system from the messaging pipeline —
 * {@code Lang} produces the text, the message service delivers it.
 *
 * @see BBAIConfigService#getLang(String)
 * @see BBAIConfigService#getDefaultLang()
 */
public interface Lang {

    /**
     * Returns the language name (e.g. {@code "en"}, {@code "ru"}).
     * Matches the filename (without {@code .yml}) in the {@code lang/} directory.
     *
     * @return the language name
     */
    String name();

    /**
     * Returns the raw message for the given key.
     * <p>
     * Lookup order:
     * <ol>
     *     <li>This language's configuration</li>
     *     <li>The default language (fallback)</li>
     *     <li>The raw key name itself (last resort)</li>
     * </ol>
     *
     * @param key the message key (e.g. {@code "join-message"})
     * @return the translated message, or the key name if not found anywhere
     */
    String get(String key);

    /**
     * Returns the message for the given key with placeholder substitution.
     * <p>
     * Replacements are specified as alternating placeholder-value pairs:
     * <pre>{@code
     * lang.get("welcome", "%player%", "Steve", "%count%", "5");
     * // "Welcome, %player%! There are %count% players online."
     * // → "Welcome, Steve! There are 5 players online."
     * }</pre>
     *
     * @param key          the message key
     * @param replacements alternating pairs: placeholder, value, placeholder, value, ...
     * @return the translated message with all placeholders replaced
     */
    String get(String key, Object... replacements);

    /**
     * Checks whether this language's configuration contains the given key
     * (without falling back to the default language).
     *
     * @param key the message key to check
     * @return {@code true} if the key exists in this language's file
     */
    boolean has(String key);

    /**
     * Returns the message list stored at the given key.
     * <p>
     * Used for keys that hold multiple message variants (e.g. randomly-picked
     * AI thoughts, alternative subtitles). Color codes inside each entry are
     * preserved unprocessed; the caller is responsible for translation.
     * <p>
     * Lookup order matches {@link #get(String)}:
     * <ol>
     *     <li>This language's configuration (must be an actual list there)</li>
     *     <li>The default language (fallback, when this language has no entry)</li>
     *     <li>An empty immutable list (last resort — caller should treat as "no variants")</li>
     * </ol>
     * Scalar values at the key are returned as a single-element list so admins
     * can write either a string or a list interchangeably.
     *
     * @param key the message key (e.g. {@code "game.ai.thinking"})
     * @return immutable list of message variants — never {@code null}
     */
    List<String> getList(String key);
}
