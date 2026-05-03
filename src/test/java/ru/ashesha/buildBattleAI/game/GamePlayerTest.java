package ru.ashesha.buildBattleAI.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GamePlayer}.
 * <p>
 * Covers constructor validation, score tracking, theme advancement
 * with wrapping, build-time decrement/reset, and zone-dirty flag.
 * PlayerSnapshot is mocked — it is tested separately via manual
 * live-server tests (requires Bukkit Player).
 */
class GamePlayerTest {

    private UUID playerId;
    private PlayerSnapshot snapshot;
    private GamePlayer player;

    @BeforeEach
    void setUp() {
        playerId = UUID.randomUUID();
        snapshot = mock(PlayerSnapshot.class);
        player = new GamePlayer(playerId, "TestPlayer", 0, snapshot, 60);
    }

    // ── constructor ──────────────────────────────────────────────────

    @Test
    void constructorSetsInitialValues() {
        assertEquals(playerId, player.playerId());
        assertEquals("TestPlayer", player.playerName());
        assertEquals(0, player.plotIndex());
        assertSame(snapshot, player.snapshot());
        assertEquals(0, player.score());
        assertEquals(0, player.themeIndex());
        assertEquals(60, player.buildTimeRemaining());
        assertFalse(player.zoneDirty());
    }

    @Test
    void constructorRejectsNullPlayerId() {
        assertThrows(NullPointerException.class,
                () -> new GamePlayer(null, "Name", 0, snapshot, 60));
    }

    @Test
    void constructorRejectsNullPlayerName() {
        assertThrows(NullPointerException.class,
                () -> new GamePlayer(playerId, null, 0, snapshot, 60));
    }

    @Test
    void constructorRejectsNullSnapshot() {
        assertThrows(NullPointerException.class,
                () -> new GamePlayer(playerId, "Name", 0, null, 60));
    }

    // ── score ────────────────────────────────────────────────────────

    @Test
    void incrementScoreIncreasesScore() {
        player.incrementScore();
        player.incrementScore();
        assertEquals(2, player.score());
    }

    // ── theme advancement ────────────────────────────────────────────

    @Test
    void advanceThemeWrapsAround() {
        // 3 themes: indices 0, 1, 2, then wraps to 0
        assertEquals(0, player.themeIndex());

        player.advanceTheme(3);
        assertEquals(1, player.themeIndex());

        player.advanceTheme(3);
        assertEquals(2, player.themeIndex());

        player.advanceTheme(3);
        assertEquals(0, player.themeIndex());
    }

    // ── build time ───────────────────────────────────────────────────

    @Test
    void decrementBuildTimeReducesTime() {
        GamePlayer p = new GamePlayer(playerId, "P", 0, snapshot, 10);
        p.decrementBuildTime();
        assertEquals(9, p.buildTimeRemaining());
    }

    @Test
    void decrementBuildTimeStopsAtZero() {
        GamePlayer p = new GamePlayer(playerId, "P", 0, snapshot, 1);
        p.decrementBuildTime();
        assertEquals(0, p.buildTimeRemaining());

        // Second decrement should not go negative
        p.decrementBuildTime();
        assertEquals(0, p.buildTimeRemaining());
    }

    @Test
    void resetBuildTimeSetsNewValue() {
        player.resetBuildTime(120);
        assertEquals(120, player.buildTimeRemaining());
    }

    // ── zone dirty flag ──────────────────────────────────────────────

    @Test
    void markZoneDirtySetsFlag() {
        assertFalse(player.zoneDirty());
        player.markZoneDirty();
        assertTrue(player.zoneDirty());
    }

    @Test
    void clearZoneDirtyClearsFlag() {
        player.markZoneDirty();
        assertTrue(player.zoneDirty());

        player.clearZoneDirty();
        assertFalse(player.zoneDirty());
    }
}
