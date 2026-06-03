package ru.ashesha.buildBattleAI.game.feedback;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Immutable snapshot of the {@code game.feedback.*} section of
 * {@code config.yml}. Captured once at session-start by
 * {@link FeedbackController} so a {@code /bbai reload} during a game
 * does not flip switches mid-round.
 * <p>
 * All getters are fluent ({@code feedbackConfig.enabled()}, not
 * {@code isEnabled()}) to match the rest of the codebase.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
@Accessors(fluent = true)
public final class FeedbackConfig {

    private final boolean enabled;
    private final boolean actionBarEnabled;
    private final boolean chatThoughtsEnabled;
    private final double chatThoughtsChance;
    private final boolean titleOnCorrectEnabled;
    private final boolean broadcastOnCorrectEnabled;
    private final boolean scoreboardEnabled;
    private final boolean tabEnabled;
    private final double confusedThreshold;
    private final boolean showConfidence;
    /** Play villager "hmm" sound when AI shows a thinking message. */
    private final boolean soundOnThinking;
    /** Probability [0..1] that a thinking message also triggers the hmm sound. */
    private final double soundOnThinkingChance;
    /** Give players a skip-theme feather in their last hotbar slot. */
    private final boolean skipFeatherEnabled;

    /**
     * Reads the {@code game.feedback.*} section from the given YAML config,
     * applying compile-time defaults for any missing key. The defaults match
     * the bundled {@code config.yml} so older config files (missing keys
     * after a plugin update) still get a sensible feedback experience.
     */
    public static FeedbackConfig fromYaml(@NonNull YamlConfiguration cfg) {
        return new FeedbackConfig(
                cfg.getBoolean("game.feedback.enabled", true),
                cfg.getBoolean("game.feedback.actionbar-enabled", true),
                cfg.getBoolean("game.feedback.chat-thoughts-enabled", true),
                cfg.getDouble("game.feedback.chat-thoughts-chance", 0.15),
                cfg.getBoolean("game.feedback.title-on-correct-enabled", true),
                cfg.getBoolean("game.feedback.broadcast-on-correct-enabled", true),
                cfg.getBoolean("game.feedback.scoreboard-enabled", true),
                cfg.getBoolean("game.feedback.tab-enabled", true),
                cfg.getDouble("game.feedback.confused-threshold", 0.04),
                cfg.getBoolean("game.feedback.show-confidence", true),
                cfg.getBoolean("game.feedback.sound-on-thinking", true),
                cfg.getDouble("game.feedback.sound-on-thinking-chance", 0.4),
                cfg.getBoolean("game.feedback.skip-feather-enabled", true)
        );
    }
}
