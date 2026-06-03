package ru.ashesha.buildBattleAI.game.feedback;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.config.api.Lang;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link ThemeFormatter}'s mapping behaviour: lang lookup first,
 * humanised fallback otherwise. Uses a hand-rolled stub {@link Lang} so the
 * test stays free of MockBukkit and the {@code ConfigService} machinery.
 */
class ThemeFormatterTest {

    @Test
    void lookup_usesLangMappingWhenPresent() {
        StubLang lang = new StubLang();
        lang.put("game.ai.theme-names.castle", "a Castle");
        assertEquals("a Castle", ThemeFormatter.format(lang, "castle"));
    }

    @Test
    void lookup_isCaseInsensitive() {
        StubLang lang = new StubLang();
        lang.put("game.ai.theme-names.castle_tower", "a Castle Tower");
        // Caller may pass mixed case (model emits lowercase, arena config may not)
        assertEquals("a Castle Tower", ThemeFormatter.format(lang, "Castle_Tower"));
        assertEquals("a Castle Tower", ThemeFormatter.format(lang, "CASTLE_TOWER"));
    }

    @Test
    void lookup_fallsBackToHumanisedNameWhenLangKeyMissing() {
        StubLang lang = new StubLang(); // no theme-names entries
        // "castle_tower" → "Castle Tower" (the dotted key sentinel is detected
        // and replaced with the humanised form).
        assertEquals("Castle Tower", ThemeFormatter.format(lang, "castle_tower"));
    }

    @Test
    void humanise_underscoresBecomeSpacesAndWordsAreTitleCased() {
        assertEquals("Default House", ThemeFormatter.humanise("default_house"));
        assertEquals("Overgrown House", ThemeFormatter.humanise("overgrown_house"));
        assertEquals("Skyscraper", ThemeFormatter.humanise("skyscraper"));
    }

    @Test
    void humanise_handlesDashAndEmptyAndSingleChar() {
        assertEquals("Foo Bar", ThemeFormatter.humanise("foo-bar"));
        assertEquals("", ThemeFormatter.humanise(""));
        assertEquals("X", ThemeFormatter.humanise("x"));
    }

    /** Minimal {@link Lang} stub — only the {@code get(String)} hook is used. */
    private static final class StubLang implements Lang {
        private final Map<String, String> entries = new HashMap<>();

        void put(String key, String value) {
            entries.put(key, value);
        }

        @Override
        public String name() {
            return "test";
        }

        @Override
        public String get(String key) {
            // Mirror the production Lang contract: a missing key is signalled by
            // returning the key itself (so ThemeFormatter knows to fall back).
            String v = entries.get(key);
            return v != null ? v : key;
        }

        @Override
        public String get(String key, Object... replacements) {
            return get(key);
        }

        @Override
        public boolean has(String key) {
            return entries.containsKey(key);
        }

        @Override
        public List<String> getList(String key) {
            return Collections.emptyList();
        }
    }
}
