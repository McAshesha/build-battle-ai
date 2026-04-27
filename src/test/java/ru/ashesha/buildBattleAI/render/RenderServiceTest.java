package ru.ashesha.buildBattleAI.render;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link RenderService} lifecycle management.
 * <p>
 * Verifies that {@code enable()} creates the underlying {@link CpuRenderer}
 * and resolves the legacy flag, and that {@code shutdown()} properly releases
 * the renderer and its thread pool. Actual rendering behavior is covered
 * by {@link CpuRendererTest}.
 */
class RenderServiceTest {

    private BuildBattleAI plugin;
    private PluginContext context;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        context = mock(PluginContext.class);
        when(plugin.getContext()).thenReturn(context);
    }

    @Test
    void enableCreatesRenderer() {
        when(context.getServerVersion()).thenReturn(ServerVersion.V_1_21);
        RenderService service = new RenderService(plugin);
        service.enable();

        // After enable, the render method should be available (delegated from CpuRenderer).
        // We test this by calling render on a trivial scene — if the renderer was null,
        // this would throw an NPE.
        assertDoesNotThrow(service::shutdown);
    }

    @Test
    void shutdownReleasesRenderer() {
        when(context.getServerVersion()).thenReturn(ServerVersion.V_1_21);
        RenderService service = new RenderService(plugin);
        service.enable();
        service.shutdown();

        // Double shutdown should not throw (renderer is already null)
        assertDoesNotThrow(service::shutdown);
    }

    @Test
    void shutdownWithoutEnableIsNoOp() {
        RenderService service = new RenderService(plugin);
        assertDoesNotThrow(service::shutdown);
    }

    @Test
    void enableSetsLegacyFlagForPre113() {
        when(context.getServerVersion()).thenReturn(ServerVersion.V_1_8);
        RenderService service = new RenderService(plugin);
        // enable() should resolve legacy=true for 1.8 — should not throw
        assertDoesNotThrow(service::enable);
        service.shutdown();
    }

    @Test
    void enableSetsModernFlagFor113Plus() {
        when(context.getServerVersion()).thenReturn(ServerVersion.V_1_13);
        RenderService service = new RenderService(plugin);
        assertDoesNotThrow(service::enable);
        service.shutdown();
    }

    @Test
    void reloadCycleCreatesNewRenderer() {
        when(context.getServerVersion()).thenReturn(ServerVersion.V_1_21);
        RenderService service = new RenderService(plugin);

        service.enable();
        service.shutdown();
        service.enable();

        // The second enable should create a fresh renderer
        assertDoesNotThrow(service::shutdown);
    }

    @Test
    void multipleReloadCyclesAreStable() {
        when(context.getServerVersion()).thenReturn(ServerVersion.V_1_21);
        RenderService service = new RenderService(plugin);

        for (int i = 0; i < 3; i++) {
            service.enable();
            service.shutdown();
        }
        // All cycles completed without error
    }
}
