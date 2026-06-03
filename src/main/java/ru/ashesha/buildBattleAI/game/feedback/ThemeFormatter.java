package ru.ashesha.buildBattleAI.game.feedback;

import lombok.NonNull;
import ru.ashesha.buildBattleAI.config.api.Lang;

/**
 * Maps the ML class name (the canonical form stored in
 * {@code centroids.json}, e.g. {@code "castle_tower"}) to a player-facing
 * display string (e.g. {@code "a Castle Tower"}). The mapping is admin-editable
 * through {@code lang/<lang>.yml} under {@code game.ai.theme-names.<class>}.
 * <p>
 * When the lang file has no entry for a class name, falls back to a generic
 * humanisation: underscores → spaces, each word title-cased, no article.
 * <p>
 * Pure helper, no state — safe to call from any thread.
 */
public final class ThemeFormatter {

    private ThemeFormatter() {
        // utility class
    }

    /**
     * Returns the display string for the given class name.
     * <p>
     * Lookup: {@code game.ai.theme-names.<className>} (lowercased). If the
     * key is missing — or the file falls back to the raw key name (no
     * translation) — the class name is humanised in-place.
     *
     * @param lang      the active language
     * @param className the raw class name from the model (case-insensitive)
     * @return display string for the player; never {@code null}
     */
    public static String format(@NonNull Lang lang, @NonNull String className) {
        String key = "game.ai.theme-names." + className.toLowerCase();
        String mapped = lang.get(key);
        // Lang#get returns the key itself when nothing is found — detect that
        // and fall back to the generic humanisation rather than show the raw
        // dotted path in chat.
        if (mapped == null || mapped.isEmpty() || mapped.equals(key))
            return humanise(className);
        return mapped;
    }

    /**
     * Lowercase-with-underscores → Title Case With Spaces.
     * {@code "castle_tower"} → {@code "Castle Tower"}.
     */
    static String humanise(String className) {
        if (className == null || className.isEmpty())
            return "";
        StringBuilder sb = new StringBuilder(className.length());
        boolean nextUpper = true;
        for (int i = 0; i < className.length(); i++) {
            char c = className.charAt(i);
            if (c == '_' || c == '-') {
                sb.append(' ');
                nextUpper = true;
                continue;
            }
            if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else
                sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
