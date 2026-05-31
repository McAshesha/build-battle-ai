package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.game.GameSession;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SessionHandleTest {

    @Test
    void cameraRotates_threeStepsThenWrapsTo0() {
        SessionHandle h = sample();
        assertEquals(0, h.currentCameraIndex());
        h.advanceCamera();
        assertEquals(1, h.currentCameraIndex());
        h.advanceCamera();
        assertEquals(2, h.currentCameraIndex());
        h.advanceCamera();
        assertEquals(0, h.currentCameraIndex());
    }

    @Test
    void lastEvalAt_recordedAndReadBack() {
        SessionHandle h = sample();
        UUID pid = UUID.randomUUID();
        assertEquals(0L, h.lastEvalAtNanos(pid));
        h.recordEvalAttempt(pid, 12345L);
        assertEquals(12345L, h.lastEvalAtNanos(pid));
    }

    @Test
    void forgetPlayer_dropsTimestamp() {
        SessionHandle h = sample();
        UUID pid = UUID.randomUUID();
        h.recordEvalAttempt(pid, 1L);
        h.forgetPlayer(pid);
        assertEquals(0L, h.lastEvalAtNanos(pid));
    }

    @Test
    void scoreCallback_isExposed() {
        AtomicReference<UUID> capturedPid = new AtomicReference<>();
        AtomicReference<Integer> capturedTheme = new AtomicReference<>();
        BiConsumer<UUID, Integer> cb = (p, t) -> { capturedPid.set(p); capturedTheme.set(t); };
        SessionHandle h = new SessionHandle(mock(GameSession.class), cb);
        UUID pid = UUID.randomUUID();
        h.scoreCallback().accept(pid, 7);
        assertEquals(pid, capturedPid.get());
        assertEquals(7, capturedTheme.get());
    }

    private static SessionHandle sample() {
        return new SessionHandle(mock(GameSession.class), (p, t) -> {});
    }
}
