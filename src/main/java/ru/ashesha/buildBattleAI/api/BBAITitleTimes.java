package ru.ashesha.buildBattleAI.api;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Immutable timing configuration for title overlay display.
 * All durations are expressed in server ticks (1 tick = 50ms at 20 TPS).
 * Negative values are clamped to zero.
 */
@Getter
@EqualsAndHashCode
public class BBAITitleTimes {

    /** Default title timing: 1 second fade-in, 3.5 seconds stay, 0.5 second fade-out. */
    public static final BBAITitleTimes DEFAULT = new BBAITitleTimes(20, 70, 10);

    /** Duration of the title fade-in animation, in ticks. */
    private final int fadeIn;
    /** Duration the title remains fully visible on screen, in ticks. */
    private final int stay;
    /** Duration of the title fade-out animation, in ticks. */
    private final int fadeOut;

    /**
     * Creates a new title timing configuration.
     *
     * @param fadeIn  fade-in duration in ticks (clamped to >= 0)
     * @param stay    stay duration in ticks (clamped to >= 0)
     * @param fadeOut fade-out duration in ticks (clamped to >= 0)
     */
    public BBAITitleTimes(int fadeIn, int stay, int fadeOut) {
        this.fadeIn = Math.max(0, fadeIn);
        this.stay = Math.max(0, stay);
        this.fadeOut = Math.max(0, fadeOut);
    }
}
