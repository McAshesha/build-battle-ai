package ru.ashesha.buildBattleAI.util;

import com.cryptomorin.xseries.XSound;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Curated palette of cross-version sound effects for UI feedback and game events.
 * <p>
 * All sounds use {@link XSound} for automatic version mapping (1.8–1.21+).
 * Each {@link SoundEffect} bundles a sound with pre-tuned volume and pitch
 * so callers never hardcode audio parameters — just {@code SoundPalette.CONFIRM.play(player)}.
 * <p>
 * <b>Design principle:</b> sounds are grouped by semantic purpose, not by
 * Minecraft sound name. This lets code read as intent ({@code SoundPalette.CONFIRM})
 * rather than implementation ({@code BLOCK_NOTE_BLOCK_PLING}).
 * <p>
 * <b>Volume philosophy:</b> all volumes are kept at 0.3–0.8 (never full 1.0).
 * UI feedback should be subtle and non-intrusive, especially during repeated
 * interactions like the arena setup wizard.
 *
 * @see XSound
 */
@UtilityClass
public class SoundPalette {

    // ── UI Feedback ───────────────────────────────────────────────────
    //
    // Sounds for admin tools, setup wizards, menus, and interactive panels.

    /**
     * General confirmation — a setting saved, a position recorded.
     * Bright, snappy electronic pling at moderate volume.
     * <p>
     * Use for: lobby/spectator/spawn/corner saved, config value applied.
     * <p>
     * 1.8: {@code NOTE_PLING} → 1.13+: {@code BLOCK_NOTE_BLOCK_PLING}
     */
    public static final SoundEffect CONFIRM = new SoundEffect(
            XSound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.2f);

    /**
     * Alternative confirmation with a distinct tonal character.
     * Higher-pitched piano note — crystalline and clear, stands apart
     * from the standard pling to signal a different kind of action.
     * <p>
     * Use for: camera angle saved, viewpoint recorded, special setting.
     * <p>
     * 1.8: {@code NOTE_PIANO} → 1.13+: {@code BLOCK_NOTE_BLOCK_HARP}
     */
    public static final SoundEffect CONFIRM_ALT = new SoundEffect(
            XSound.BLOCK_NOTE_BLOCK_HARP, 0.6f, 1.5f);

    /**
     * Selection made — a number picked, an option chosen from a list.
     * Soft, satisfying sparkle that feels responsive without being loud.
     * <p>
     * Use for: player count selection, menu choice, toggle switch, page turn.
     * <p>
     * 1.8: {@code ORB_PICKUP} → 1.13+: {@code ENTITY_EXPERIENCE_ORB_PICKUP}
     */
    public static final SoundEffect SELECT = new SoundEffect(
            XSound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);

    /**
     * Session opened — a wizard started, a menu opened, a greeting.
     * Warm piano at natural pitch — inviting without demanding attention.
     * <p>
     * Use for: arena setup started, game lobby opened, welcome screen.
     * <p>
     * 1.8: {@code NOTE_PIANO} → 1.13+: {@code BLOCK_NOTE_BLOCK_HARP}
     */
    public static final SoundEffect WELCOME = new SoundEffect(
            XSound.BLOCK_NOTE_BLOCK_HARP, 0.7f, 1.0f);

    /**
     * Major success — arena created, game won, achievement unlocked.
     * The iconic Minecraft level-up ascending chime — unmistakable triumph.
     * <p>
     * Use for: arena creation confirmed, game victory, milestone reached.
     * <p>
     * 1.8: {@code LEVEL_UP} → 1.13+: {@code ENTITY_PLAYER_LEVELUP}
     */
    public static final SoundEffect CELEBRATE = new SoundEffect(
            XSound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.0f);

    /**
     * Action denied — validation failed, prerequisites not met.
     * A short villager "hmm" grunt — universally recognized as "nope".
     * <p>
     * Use for: confirm with missing fields, invalid input, no permission.
     * <p>
     * 1.8: {@code VILLAGER_NO} → 1.13+: {@code ENTITY_VILLAGER_NO}
     */
    public static final SoundEffect DENY = new SoundEffect(
            XSound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);

    /**
     * Dismissed — session cancelled, menu closed, operation aborted.
     * A low, muted bass note — conveys "closed" without negativity.
     * <p>
     * Use for: setup cancelled, menu closed, wizard aborted, session ended.
     * <p>
     * 1.8: {@code NOTE_BASS} → 1.13+: {@code BLOCK_NOTE_BLOCK_BASS}
     */
    public static final SoundEffect DISMISS = new SoundEffect(
            XSound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.5f);

    /**
     * Minor UI click — page navigation, non-consequential interaction.
     * The vanilla Minecraft button click — subtle and familiar.
     * <p>
     * Use for: tab page turn, scroll, toggle, minor navigation.
     * <p>
     * 1.8: {@code CLICK} → 1.13+: {@code UI_BUTTON_CLICK}
     */
    public static final SoundEffect CLICK = new SoundEffect(
            XSound.UI_BUTTON_CLICK, 0.5f, 1.0f);

    // ── Game Countdown ────────────────────────────────────────────────
    //
    // Sounds for timers, countdowns, and time-pressure moments.

    /**
     * Countdown tick — a percussive hi-hat for regular timer seconds.
     * Subtle and rhythmic, doesn't fatigue over a long countdown.
     * <p>
     * Use for: build phase countdown (normal seconds), lobby wait timer.
     * <p>
     * 1.8: {@code NOTE_STICKS} → 1.13+: {@code BLOCK_NOTE_BLOCK_HAT}
     */
    public static final SoundEffect TICK = new SoundEffect(
            XSound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 1.0f);

    /**
     * Urgent countdown tick — the last few seconds before time expires.
     * Higher-pitched, louder pling that builds tension and urgency.
     * <p>
     * Use for: final 5 seconds of build phase, last-chance warnings.
     * <p>
     * 1.8: {@code NOTE_PLING} → 1.13+: {@code BLOCK_NOTE_BLOCK_PLING}
     */
    public static final SoundEffect TICK_URGENT = new SoundEffect(
            XSound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 2.0f);

    // ── Game Phase Transitions ────────────────────────────────────────
    //
    // Sounds that mark the boundary between game phases.

    /**
     * Game or round start — an energetic, punchy announcement.
     * Snare drum hit — sharp and unmistakable as "go!".
     * <p>
     * Use for: build phase begins, round start, match started.
     * <p>
     * 1.8: {@code NOTE_SNARE_DRUM} → 1.13+: {@code BLOCK_NOTE_BLOCK_SNARE}
     */
    public static final SoundEffect GAME_START = new SoundEffect(
            XSound.BLOCK_NOTE_BLOCK_SNARE, 0.8f, 1.0f);

    /**
     * Phase ended — time's up, transitioning to the next phase.
     * Firework twinkle — sparkly and atmospheric, signals conclusion gracefully.
     * <p>
     * Use for: build phase ended, round complete, transitioning to judging.
     * <p>
     * 1.8: {@code FIREWORK_TWINKLE} → 1.13+: {@code ENTITY_FIREWORK_ROCKET_TWINKLE}
     */
    public static final SoundEffect PHASE_END = new SoundEffect(
            XSound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.6f, 1.0f);

    /**
     * Theme or topic revealed — a moment of anticipation and challenge.
     * Bass drum with a slightly raised pitch — punchy "here it comes!".
     * <p>
     * Use for: build theme assigned, challenge revealed, surprise element.
     * <p>
     * 1.8: {@code NOTE_BASS_DRUM} → 1.13+: {@code BLOCK_NOTE_BLOCK_BASEDRUM}
     */
    public static final SoundEffect REVEAL = new SoundEffect(
            XSound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.6f, 1.2f);

    // ── Scoring & Results ─────────────────────────────────────────────
    //
    // Sounds for points, hits, misses, and outcome feedback.

    /**
     * Point scored — the AI recognized the build, reward earned.
     * Item pickup "pop" — quick, satisfying, non-disruptive.
     * <p>
     * Use for: gaining a point, collecting a reward, small positive outcome.
     * <p>
     * 1.8: {@code ITEM_PICKUP} → 1.13+: {@code ENTITY_ITEM_PICKUP}
     */
    public static final SoundEffect SCORE = new SoundEffect(
            XSound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);

    /**
     * Build not recognized — attempt failed, no points awarded.
     * A low bass note — "didn't quite work" without feeling punishing.
     * <p>
     * Use for: AI classification miss, failed attempt, neutral negative.
     * <p>
     * 1.8: {@code NOTE_BASS} → 1.13+: {@code BLOCK_NOTE_BLOCK_BASS}
     */
    public static final SoundEffect MISS = new SoundEffect(
            XSound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);

    // ── Spatial & Movement ────────────────────────────────────────────

    /**
     * Teleportation — a player moved to a new location.
     * The iconic enderman teleport woosh — instantly recognizable.
     * <p>
     * Use for: teleporting to arena, returning from setup, plot assignment.
     * <p>
     * 1.8: {@code ENDERMAN_TELEPORT} → 1.13+: {@code ENTITY_ENDERMAN_TELEPORT}
     */
    public static final SoundEffect TELEPORT = new SoundEffect(
            XSound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.0f);

    // ── Notifications ─────────────────────────────────────────────────
    //
    // Sounds for system messages, alerts, and status updates.

    /**
     * Attention-grabbing alert — something important needs acknowledgment.
     * Anvil clang at low volume — metallic and distinct, cuts through noise.
     * <p>
     * Use for: player joined arena, important announcement, critical state change.
     * <p>
     * 1.8: {@code ANVIL_USE} → 1.13+: {@code BLOCK_ANVIL_USE}
     */
    public static final SoundEffect ALERT = new SoundEffect(
            XSound.BLOCK_ANVIL_USE, 0.3f, 1.2f);

    /**
     * Soft notification — a gentle status update or hint.
     * Low-pitched pling — audible but politely in the background.
     * <p>
     * Use for: "waiting for players", status hint, passive notification.
     * <p>
     * 1.8: {@code NOTE_PLING} → 1.13+: {@code BLOCK_NOTE_BLOCK_PLING}
     */
    public static final SoundEffect NOTIFY = new SoundEffect(
            XSound.BLOCK_NOTE_BLOCK_PLING, 0.4f, 0.8f);

    // ── AI Persona ────────────────────────────────────────────────────
    //
    // Sounds that give the ML classifier a "thinking out loud" personality
    // during gameplay. Used by FeedbackController in tandem with the
    // chat / actionbar / title channels.

    /**
     * AI is thinking — the iconic villager "hmm" grunt. Pairs visually with
     * action-bar messages like "Hmm... I think I see a Tree?".
     * <p>
     * Use sparingly (probability-gated by config) — would be obnoxious on
     * every ML tick.
     * <p>
     * 1.8: {@code VILLAGER_IDLE} → 1.13+: {@code ENTITY_VILLAGER_AMBIENT}
     */
    public static final SoundEffect AI_THINKING = new SoundEffect(
            XSound.ENTITY_VILLAGER_AMBIENT, 0.5f, 1.0f);

    /**
     * Player invoked the skip-theme feather. A short whoosh — signals "this
     * theme is gone" without dramatising failure.
     * <p>
     * 1.8: {@code ITEM_BREAK} → 1.13+: {@code ENTITY_ITEM_BREAK}
     */
    public static final SoundEffect SKIP_THEME = new SoundEffect(
            XSound.ENTITY_ITEM_BREAK, 0.7f, 1.2f);

    // ── SoundEffect ───────────────────────────────────────────────────

    /**
     * A pre-configured sound effect with volume and pitch tuned for
     * a specific purpose. Wraps {@link XSound} for cross-version
     * compatibility (1.8–1.21+).
     * <p>
     * Instances are immutable and reusable — shared safely across threads.
     */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    public static final class SoundEffect {

        private final XSound sound;
        private final float volume;
        private final float pitch;

        /**
         * Plays this sound effect for a single player at their location.
         * Only this player hears the sound.
         *
         * @param player the player to play the sound for
         */
        public void play(Player player) {
            sound.play(player, volume, pitch);
        }

        /**
         * Plays this sound effect at a world location.
         * All players within hearing range will hear it.
         *
         * @param location the world position to play the sound at
         */
        public void playAt(Location location) {
            sound.play(location, volume, pitch);
        }

        /**
         * Plays this sound effect for each player individually.
         * Each player hears it at their own location; other players
         * not in the collection do not hear it.
         *
         * @param players the players to play the sound for
         */
        public void play(Collection<? extends Player> players) {
            for (Player player : players)
                sound.play(player, volume, pitch);
        }
    }
}
