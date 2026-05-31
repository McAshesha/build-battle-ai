package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.BuildBattleAI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

class EvaluationServiceLifecycleTest {

    @Test
    void enableThenShutdown_isIdempotent() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        EvaluationService service = new EvaluationService(plugin);
        assertDoesNotThrow(service::enable);
        assertDoesNotThrow(service::shutdown);
        assertDoesNotThrow(service::shutdown); // second shutdown is a no-op
    }
}
