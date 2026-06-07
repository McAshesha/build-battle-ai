package ru.ashesha.buildBattleAI.game;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.arena.api.Arena;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration test — covers GAME-01.
 * <p>
 * Invariant: a cancelled countdown does not later fire startGame().
 * Mechanism under test: {@link GameSession#cancelAllTasks()} must
 * cancel the scheduled countdownTaskId via Bukkit's scheduler and
 * reset the field to -1, so any subsequent tick of the previously
 * scheduled BukkitTask is a no-op.
 * <p>
 * Why integration: the contract spans GameSession's task-id bookkeeping
 * and the static Bukkit scheduler. We mock the latter via
 * {@code MockedStatic<Bukkit>} to keep the test Bukkit-free.
 */
@Tag("integration")
class CountdownCancellationIT {

    @Test
    @DisplayName("GAME-01: cancelAllTasks cancels countdown task ID and clears the slot")
    void cancelledCountdownDoesNotStart() {
        Arena arena = mock(Arena.class);
        when(arena.maxPlayers()).thenReturn(2);

        GameSession session = new GameSession(arena);
        session.state(ArenaState.COUNTDOWN);
        session.countdownTaskId(42);
        session.gameTickTaskId(-1);
        session.endingTaskId(-1);

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            session.cancelAllTasks();
            verify(scheduler).cancelTask(42);
            verify(scheduler, never()).cancelTask(intThat(id -> id < 0));
        }
        assertEquals(-1, session.countdownTaskId(),
                "countdownTaskId must be reset to -1 after cancellation");
        assertEquals(ArenaState.COUNTDOWN, session.state(),
                "cancelAllTasks must NOT alter state — state transitions belong to GameManager");
    }

    @Test
    @DisplayName("GAME-01: cancelAllTasks also cancels gameTick + ending tasks when set")
    void cancelAllTasksCancelsAllSetTaskIds() {
        Arena arena = mock(Arena.class);
        GameSession session = new GameSession(arena);
        session.countdownTaskId(10);
        session.gameTickTaskId(20);
        session.endingTaskId(30);

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            session.cancelAllTasks();
            verify(scheduler).cancelTask(10);
            verify(scheduler).cancelTask(20);
            verify(scheduler).cancelTask(30);
        }
        assertEquals(-1, session.countdownTaskId());
        assertEquals(-1, session.gameTickTaskId());
        assertEquals(-1, session.endingTaskId());
    }
}
