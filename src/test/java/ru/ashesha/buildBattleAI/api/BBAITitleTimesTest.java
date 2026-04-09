package ru.ashesha.buildBattleAI.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BBAITitleTimesTest {

    @Test
    void defaultTimesValues() {
        BBAITitleTimes defaults = BBAITitleTimes.DEFAULT;
        assertEquals(20, defaults.getFadeIn());
        assertEquals(70, defaults.getStay());
        assertEquals(10, defaults.getFadeOut());
    }

    @Test
    void customPositiveValues() {
        BBAITitleTimes times = new BBAITitleTimes(5, 40, 15);
        assertEquals(5, times.getFadeIn());
        assertEquals(40, times.getStay());
        assertEquals(15, times.getFadeOut());
    }

    @Test
    void zeroValues() {
        BBAITitleTimes times = new BBAITitleTimes(0, 0, 0);
        assertEquals(0, times.getFadeIn());
        assertEquals(0, times.getStay());
        assertEquals(0, times.getFadeOut());
    }

    @Test
    void negativeFadeInClampedToZero() {
        BBAITitleTimes times = new BBAITitleTimes(-10, 50, 5);
        assertEquals(0, times.getFadeIn());
        assertEquals(50, times.getStay());
        assertEquals(5, times.getFadeOut());
    }

    @Test
    void negativeStayClampedToZero() {
        BBAITitleTimes times = new BBAITitleTimes(10, -1, 5);
        assertEquals(10, times.getFadeIn());
        assertEquals(0, times.getStay());
        assertEquals(5, times.getFadeOut());
    }

    @Test
    void negativeFadeOutClampedToZero() {
        BBAITitleTimes times = new BBAITitleTimes(10, 50, -100);
        assertEquals(10, times.getFadeIn());
        assertEquals(50, times.getStay());
        assertEquals(0, times.getFadeOut());
    }

    @Test
    void allNegativeClampedToZero() {
        BBAITitleTimes times = new BBAITitleTimes(-5, -10, -20);
        assertEquals(0, times.getFadeIn());
        assertEquals(0, times.getStay());
        assertEquals(0, times.getFadeOut());
    }

    @Test
    void largeValues() {
        BBAITitleTimes times = new BBAITitleTimes(Integer.MAX_VALUE, 1000, 500);
        assertEquals(Integer.MAX_VALUE, times.getFadeIn());
        assertEquals(1000, times.getStay());
        assertEquals(500, times.getFadeOut());
    }

    @Test
    void equalityForSameValues() {
        BBAITitleTimes a = new BBAITitleTimes(20, 70, 10);
        BBAITitleTimes b = new BBAITitleTimes(20, 70, 10);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void inequalityForDifferentValues() {
        BBAITitleTimes a = new BBAITitleTimes(20, 70, 10);
        BBAITitleTimes b = new BBAITitleTimes(20, 70, 11);
        assertNotEquals(a, b);
    }

    @Test
    void defaultEqualsManuallyConstructedSameValues() {
        BBAITitleTimes manual = new BBAITitleTimes(20, 70, 10);
        assertEquals(BBAITitleTimes.DEFAULT, manual);
    }

    @Test
    void minIntegerNegativeClampedToZero() {
        BBAITitleTimes times = new BBAITitleTimes(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        assertEquals(0, times.getFadeIn());
        assertEquals(0, times.getStay());
        assertEquals(0, times.getFadeOut());
    }
}
