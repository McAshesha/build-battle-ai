package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.evaluation.api.BBAIEvaluationService;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats;
import ru.ashesha.buildBattleAI.game.GameSession;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Default implementation of {@link BBAIEvaluationService}. Wires together
 * the bounded queues, render workers, ML coalescer, coordinator task, and
 * metrics. Lifecycle is exposed through {@link PluginService}; the public
 * runtime API is on the interface.
 */
@RequiredArgsConstructor
public class EvaluationService implements PluginService, BBAIEvaluationService {

    private final BuildBattleAI plugin;
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    @Override
    public void enable() {
        if (!enabled.compareAndSet(false, true))
            return;
        // Real wiring is added in later tasks.
    }

    @Override
    public void shutdown() {
        if (!enabled.compareAndSet(true, false))
            return;
        // Real teardown is added in later tasks.
    }

    @Override
    public void registerSession(@NonNull GameSession session,
                                @NonNull BiConsumer<UUID, Integer> scoreCallback) {
        throw new UnsupportedOperationException("Wired in a later task");
    }

    @Override
    public void unregisterSession(@NonNull String arenaName) {
        throw new UnsupportedOperationException("Wired in a later task");
    }

    @Override
    public @NonNull EvaluationStats stats() {
        throw new UnsupportedOperationException("Wired in a later task");
    }
}
