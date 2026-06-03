package ru.ashesha.buildBattleAI.game.feedback;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link FeedbackConfig#fromYaml(YamlConfiguration)} reads explicit
 * values when present and falls back to the bundled defaults otherwise.
 */
class FeedbackConfigTest {

    @Test
    void defaults_applyWhenAllKeysAreMissing() {
        YamlConfiguration cfg = new YamlConfiguration(); // empty
        FeedbackConfig fc = FeedbackConfig.fromYaml(cfg);
        // Defaults match the bundled config.yml — any change there must be
        // mirrored here and vice versa.
        assertTrue(fc.enabled());
        assertTrue(fc.actionBarEnabled());
        assertTrue(fc.chatThoughtsEnabled());
        assertEquals(0.15, fc.chatThoughtsChance(), 1e-9);
        assertTrue(fc.titleOnCorrectEnabled());
        assertTrue(fc.broadcastOnCorrectEnabled());
        assertTrue(fc.scoreboardEnabled());
        assertTrue(fc.tabEnabled());
        assertEquals(0.04, fc.confusedThreshold(), 1e-9);
        assertTrue(fc.showConfidence());
    }

    @Test
    void explicitValues_overrideDefaults() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("game.feedback.enabled", false);
        cfg.set("game.feedback.actionbar-enabled", false);
        cfg.set("game.feedback.chat-thoughts-enabled", false);
        cfg.set("game.feedback.chat-thoughts-chance", 0.5);
        cfg.set("game.feedback.title-on-correct-enabled", false);
        cfg.set("game.feedback.broadcast-on-correct-enabled", false);
        cfg.set("game.feedback.scoreboard-enabled", false);
        cfg.set("game.feedback.tab-enabled", false);
        cfg.set("game.feedback.confused-threshold", 0.1);
        cfg.set("game.feedback.show-confidence", false);

        FeedbackConfig fc = FeedbackConfig.fromYaml(cfg);
        assertFalse(fc.enabled());
        assertFalse(fc.actionBarEnabled());
        assertFalse(fc.chatThoughtsEnabled());
        assertEquals(0.5, fc.chatThoughtsChance(), 1e-9);
        assertFalse(fc.titleOnCorrectEnabled());
        assertFalse(fc.broadcastOnCorrectEnabled());
        assertFalse(fc.scoreboardEnabled());
        assertFalse(fc.tabEnabled());
        assertEquals(0.1, fc.confusedThreshold(), 1e-9);
        assertFalse(fc.showConfidence());
    }

    @Test
    void partialOverride_keepsDefaultsForMissingKeys() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("game.feedback.enabled", true);
        cfg.set("game.feedback.scoreboard-enabled", false);
        FeedbackConfig fc = FeedbackConfig.fromYaml(cfg);
        assertTrue(fc.enabled());
        assertFalse(fc.scoreboardEnabled());
        // Untouched keys keep defaults.
        assertTrue(fc.actionBarEnabled());
        assertEquals(0.15, fc.chatThoughtsChance(), 1e-9);
    }
}
