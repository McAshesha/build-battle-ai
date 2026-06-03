package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationCallback;
import ru.ashesha.buildBattleAI.game.GameSession;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
    void callback_isExposedAndForwardsAllArgs() {
        AtomicReference<UUID> capturedPid = new AtomicReference<>();
        AtomicReference<Integer> capturedTheme = new AtomicReference<>();
        AtomicReference<Boolean> capturedMatched = new AtomicReference<>();
        EvaluationCallback cb = (p, t, topK, matched) -> {
            capturedPid.set(p);
            capturedTheme.set(t);
            capturedMatched.set(matched);
        };
        SessionHandle h = new SessionHandle(mock(GameSession.class), cb);
        UUID pid = UUID.randomUUID();
        h.callback().onEvaluated(pid, 7, Collections.emptyList(), true);
        assertEquals(pid, capturedPid.get());
        assertEquals(7, capturedTheme.get());
        assertEquals(Boolean.TRUE, capturedMatched.get());
    }

    private static SessionHandle sample() {
        return new SessionHandle(mock(GameSession.class), (p, t, topK, matched) -> {});
    }
}
