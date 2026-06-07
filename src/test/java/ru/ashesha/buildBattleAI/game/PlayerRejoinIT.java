package ru.ashesha.buildBattleAI.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.arena.api.Arena;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration test — covers GAME-02.
 * Invariant: rejoin of the same UUID produces no duplicate in players().
 * The session's players map MUST replace, not add, when the same key
 * is encountered (LinkedHashMap semantics).
 */
@Tag("integration")
class PlayerRejoinIT {

    @Test
    @DisplayName("GAME-02: disconnect/rejoin cycle leaves exactly one entry")
    void rejoinDoesNotDuplicate() {
        Arena arena = mock(Arena.class);
        when(arena.maxPlayers()).thenReturn(4);
        GameSession session = new GameSession(arena);

        UUID id = UUID.randomUUID();
        GamePlayer gp1 = new GamePlayer(id, "Alice", 0, mock(PlayerSnapshot.class), 120);

        session.players().put(id, gp1);
        assertEquals(1, session.players().size());

        session.players().remove(id);
        assertEquals(0, session.players().size());

        GamePlayer gp2 = new GamePlayer(id, "Alice", 1, mock(PlayerSnapshot.class), 120);
        session.players().put(id, gp2);

        assertEquals(1, session.players().size(),
                "rejoin must not produce duplicates");
        assertSame(gp2, session.players().get(id),
                "the latest GamePlayer instance must be the one in the map");
    }

    @Test
    @DisplayName("GAME-02: re-adding without removing replaces the value (no duplicate)")
    void readdReplacesNotDuplicates() {
        Arena arena = mock(Arena.class);
        GameSession session = new GameSession(arena);

        UUID id = UUID.randomUUID();
        GamePlayer first = new GamePlayer(id, "Bob", 0, mock(PlayerSnapshot.class), 120);
        GamePlayer second = new GamePlayer(id, "Bob", 0, mock(PlayerSnapshot.class), 120);

        session.players().put(id, first);
        session.players().put(id, second);

        assertEquals(1, session.players().size());
        assertSame(second, session.players().get(id));
    }
}
