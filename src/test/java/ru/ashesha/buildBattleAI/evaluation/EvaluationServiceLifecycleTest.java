package ru.ashesha.buildBattleAI.evaluation;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;
import ru.ashesha.buildBattleAI.render.RenderService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvaluationServiceLifecycleTest {

    @Test
    void enableAndShutdown_areIdempotent_andStatsAvailable() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        PluginContext ctx = mock(PluginContext.class);
        when(plugin.getContext()).thenReturn(ctx);
        when(plugin.getPluginLogger()).thenReturn(mock(PluginLogger.class));

        BBAIConfigService cfg = mock(BBAIConfigService.class);
        when(cfg.config()).thenReturn(new YamlConfiguration());
        when(ctx.getConfigService()).thenReturn(cfg);
        when(ctx.getRenderService()).thenReturn(mock(RenderService.class));
        when(ctx.getMlService()).thenReturn(mock(BBAIMLService.class));

        BukkitScheduler sched = mock(BukkitScheduler.class);
        when(sched.runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong()))
                .thenReturn(mock(BukkitTask.class));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(sched);

            EvaluationService service = new EvaluationService(plugin);
            assertDoesNotThrow(service::enable);

            EvaluationStats s = service.stats();
            assertEquals(0L, s.rendersCompleted());

            assertDoesNotThrow(service::shutdown);
            assertDoesNotThrow(service::shutdown); // idempotent
            assertDoesNotThrow(service::enable);   // re-enable works
            service.shutdown();
        }
    }
}
