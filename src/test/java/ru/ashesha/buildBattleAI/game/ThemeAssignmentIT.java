package ru.ashesha.buildBattleAI.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.arena.api.Arena;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration test — covers GAME-03.
 * <p>
 * Invariant: theme indices wrap when themes.size() &lt; plots.size().
 * <ul>
 *   <li>{@code GameSession.getTheme(i)} returns {@code themes.get(i % themes.size())}</li>
 *   <li>{@code GamePlayer.advanceTheme(themeCount)} wraps via {@code (themeIndex + 1) % themeCount}</li>
 * </ul>
 * Pure arithmetic — no Bukkit involvement.
 */
@Tag("integration")
class ThemeAssignmentIT {

    @Test
    @DisplayName("GAME-03: getTheme(i) wraps via index % themes.size()")
    void themesShorterThanPlots() {
        Arena arena = mock(Arena.class);
        when(arena.maxPlayers()).thenReturn(4); // 4 plots
        GameSession session = new GameSession(arena);

        List<String> themes = Arrays.asList("castle", "tree");
        session.setThemes(themes);

        assertEquals("castle", session.getTheme(0));
        assertEquals("tree", session.getTheme(1));
        assertEquals("castle", session.getTheme(2), "index 2 must wrap to themes[0]");
        assertEquals("tree", session.getTheme(3), "index 3 must wrap to themes[1]");
        assertEquals("castle", session.getTheme(8), "index 8 must wrap to themes[0]");
    }

    @Test
    @DisplayName("GAME-03: advanceTheme wraps via (themeIndex + 1) % themeCount")
    void advanceThemeWrapsToZero() {
        UUID pid = UUID.randomUUID();
        GamePlayer gp = new GamePlayer(pid, "Charlie", 0, mock(PlayerSnapshot.class), 120);

        int themeCount = 3;
        gp.advanceTheme(themeCount); // 0 -> 1
        assertEquals(1, gp.themeIndex());
        gp.advanceTheme(themeCount); // 1 -> 2
        assertEquals(2, gp.themeIndex());
        gp.advanceTheme(themeCount); // 2 -> 0 (wrap)
        assertEquals(0, gp.themeIndex(), "advanceTheme must wrap at themeCount");
    }

    @Test
    @DisplayName("GAME-03: getTheme on empty theme list returns 'unknown' sentinel")
    void emptyThemeListReturnsUnknown() {
        Arena arena = mock(Arena.class);
        GameSession session = new GameSession(arena);
        // themes not initialised — must return sentinel
        assertEquals("unknown", session.getTheme(0),
                "empty theme list must return 'unknown' (getTheme guard)");
    }
}
