package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Verifies that {@link EvaluationService#stats()} returns a safe,
 * all-zero snapshot when the service has not been enabled yet — this is
 * the contract relied on by {@code /bbai stats} during early-boot or
 * post-shutdown phases.
 */
class EvaluationServiceStatsTest {

    @Test
    void statsBeforeEnable_returnsAllZeros() {
        EvaluationService service = new EvaluationService(mock(BuildBattleAI.class));
        EvaluationStats s = service.stats();
        assertEquals(0L, s.rendersCompleted());
        assertEquals(0L, s.matchesDispatched());
        assertEquals(0, s.registeredSessions());
        assertEquals(0, s.batchSizeHistogram().length);
    }
}
