package ru.ashesha.buildBattleAI.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.ArenaManager;
import ru.ashesha.buildBattleAI.commands.ArenaCommand;
import ru.ashesha.buildBattleAI.commands.CommandService;
import ru.ashesha.buildBattleAI.commands.MLTestCommand;
import ru.ashesha.buildBattleAI.commands.WorldTpCommand;
import ru.ashesha.buildBattleAI.config.ConfigService;
import ru.ashesha.buildBattleAI.data.DataService;
import ru.ashesha.buildBattleAI.entity.hologram.HologramService;
import ru.ashesha.buildBattleAI.entity.npc.NPCService;
import ru.ashesha.buildBattleAI.entity.picture.PictureService;
import ru.ashesha.buildBattleAI.evaluation.EvaluationService;
import ru.ashesha.buildBattleAI.game.GameManager;
import ru.ashesha.buildBattleAI.listeners.ArenaSetupListener;
import ru.ashesha.buildBattleAI.listeners.GameListener;
import ru.ashesha.buildBattleAI.listeners.ListenerService;
import ru.ashesha.buildBattleAI.listeners.MLTestListener;
import ru.ashesha.buildBattleAI.message.MessageService;
import ru.ashesha.buildBattleAI.ml.MLService;
import ru.ashesha.buildBattleAI.render.RenderService;
import ru.ashesha.buildBattleAI.world.WorldService;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

/**
 * Lifecycle invariants for {@link PluginContext}.
 * <p>
 * Each service class is intercepted with Mockito's
 * {@link MockedConstruction}, so when {@code new PluginContext(plugin)} runs
 * inside the try-with-resources block, every {@code new ConfigService(...)}
 * (and the rest) returns a mock instance. We then exercise
 * {@code enable() / shutdown() / reload()} and verify:
 * <ol>
 *   <li>{@code enable()} invokes every service's {@code enable()} in the
 *       construction order;</li>
 *   <li>{@code enable()} subsequently registers two commands and three
 *       listeners through {@link CommandService} / {@link ListenerService};</li>
 *   <li>{@code shutdown()} invokes every service's {@code shutdown()} in the
 *       <em>reverse</em> construction order;</li>
 *   <li>{@code reload()} is a faithful {@code shutdown() + enable()} pair —
 *       leaving the plugin in the same observable state as a fresh start.</li>
 * </ol>
 * This is a structural test — it does not exercise the real service code,
 * only the orchestration layer.
 */
class PluginContextLifecycleTest {

    private BuildBattleAI plugin;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(mock(PluginLogger.class));
    }

    // ── enable: order of service.enable() ─────────────────────────────────

    @Test
    void enableInvokesEveryServiceInConstructionOrder() {
        try (MockedConstruction<ConfigService> cfg = mockConstruction(ConfigService.class);
             MockedConstruction<DataService> dat = mockConstruction(DataService.class);
             MockedConstruction<WorldService> wld = mockConstruction(WorldService.class);
             MockedConstruction<ArenaManager> arn = mockConstruction(ArenaManager.class);
             MockedConstruction<GameManager> gam = mockConstruction(GameManager.class);
             MockedConstruction<MessageService> msg = mockConstruction(MessageService.class);
             MockedConstruction<NPCService> npc = mockConstruction(NPCService.class);
             MockedConstruction<HologramService> hol = mockConstruction(HologramService.class);
             MockedConstruction<PictureService> pic = mockConstruction(PictureService.class);
             MockedConstruction<MLService> mls = mockConstruction(MLService.class);
             MockedConstruction<RenderService> rnd = mockConstruction(RenderService.class);
             MockedConstruction<EvaluationService> evl = mockConstruction(EvaluationService.class);
             MockedConstruction<CommandService> cmd = mockConstruction(CommandService.class);
             MockedConstruction<ListenerService> lst = mockConstruction(ListenerService.class);
             MockedConstruction<ArenaCommand> arenaCmd = mockConstruction(ArenaCommand.class);
             MockedConstruction<MLTestCommand> mlCmd = mockConstruction(MLTestCommand.class);
             MockedConstruction<ArenaSetupListener> setupLst = mockConstruction(ArenaSetupListener.class);
             MockedConstruction<GameListener> gameLst = mockConstruction(GameListener.class);
             MockedConstruction<MLTestListener> mlLst = mockConstruction(MLTestListener.class)) {

            PluginContext ctx = new PluginContext(plugin);
            ctx.enable();

            ConfigService configMock = cfg.constructed().get(0);
            DataService dataMock = dat.constructed().get(0);
            WorldService worldMock = wld.constructed().get(0);
            ArenaManager arenaMock = arn.constructed().get(0);
            GameManager gameMock = gam.constructed().get(0);
            MessageService msgMock = msg.constructed().get(0);
            NPCService npcMock = npc.constructed().get(0);
            HologramService holoMock = hol.constructed().get(0);
            PictureService picMock = pic.constructed().get(0);
            MLService mlsMock = mls.constructed().get(0);
            RenderService rndMock = rnd.constructed().get(0);
            EvaluationService evlMock = evl.constructed().get(0);
            CommandService cmdMock = cmd.constructed().get(0);
            ListenerService lstMock = lst.constructed().get(0);

            InOrder order = inOrder(configMock, dataMock, worldMock, arenaMock,
                    gameMock, msgMock, npcMock, holoMock, picMock, mlsMock,
                    rndMock, evlMock, cmdMock, lstMock);

            order.verify(configMock).enable();
            order.verify(dataMock).enable();
            order.verify(worldMock).enable();
            order.verify(arenaMock).enable();
            order.verify(mlsMock).enable();
            order.verify(rndMock).enable();
            order.verify(evlMock).enable();
            order.verify(gameMock).enable();
            order.verify(msgMock).enable();
            order.verify(npcMock).enable();
            order.verify(holoMock).enable();
            order.verify(picMock).enable();
            order.verify(cmdMock).enable();
            order.verify(lstMock).enable();
        }
    }

    // ── enable: command + listener registrations ──────────────────────────

    @Test
    void enableRegistersTwoCommandsAndThreeListeners() {
        try (MockedConstruction<ConfigService> cfg = mockConstruction(ConfigService.class);
             MockedConstruction<DataService> dat = mockConstruction(DataService.class);
             MockedConstruction<WorldService> wld = mockConstruction(WorldService.class);
             MockedConstruction<ArenaManager> arn = mockConstruction(ArenaManager.class);
             MockedConstruction<GameManager> gam = mockConstruction(GameManager.class);
             MockedConstruction<MessageService> msg = mockConstruction(MessageService.class);
             MockedConstruction<NPCService> npc = mockConstruction(NPCService.class);
             MockedConstruction<HologramService> hol = mockConstruction(HologramService.class);
             MockedConstruction<PictureService> pic = mockConstruction(PictureService.class);
             MockedConstruction<MLService> mls = mockConstruction(MLService.class);
             MockedConstruction<RenderService> rnd = mockConstruction(RenderService.class);
             MockedConstruction<EvaluationService> evl = mockConstruction(EvaluationService.class);
             MockedConstruction<CommandService> cmd = mockConstruction(CommandService.class);
             MockedConstruction<ListenerService> lst = mockConstruction(ListenerService.class);
             MockedConstruction<ArenaCommand> arenaCmd = mockConstruction(ArenaCommand.class);
             MockedConstruction<MLTestCommand> mlCmd = mockConstruction(MLTestCommand.class);
             MockedConstruction<WorldTpCommand> wtpCmd = mockConstruction(WorldTpCommand.class);
             MockedConstruction<ArenaSetupListener> setupLst = mockConstruction(ArenaSetupListener.class);
             MockedConstruction<GameListener> gameLst = mockConstruction(GameListener.class);
             MockedConstruction<MLTestListener> mlLst = mockConstruction(MLTestListener.class)) {

            PluginContext ctx = new PluginContext(plugin);
            ctx.enable();

            CommandService cmdMock = cmd.constructed().get(0);
            ListenerService lstMock = lst.constructed().get(0);

            verify(cmdMock).register(any(ArenaCommand.class));
            verify(cmdMock).register(any(MLTestCommand.class));
            verify(cmdMock).register(any(WorldTpCommand.class));
            verify(cmdMock, times(3)).register(any());

            verify(lstMock).register(any(ArenaSetupListener.class));
            verify(lstMock).register(any(GameListener.class));
            verify(lstMock).register(any(MLTestListener.class));
            verify(lstMock, times(3)).register(any());
        }
    }

    // ── shutdown: reverse-order invocation ────────────────────────────────

    @Test
    void shutdownInvokesEveryServiceInReverseOrder() {
        try (MockedConstruction<ConfigService> cfg = mockConstruction(ConfigService.class);
             MockedConstruction<DataService> dat = mockConstruction(DataService.class);
             MockedConstruction<WorldService> wld = mockConstruction(WorldService.class);
             MockedConstruction<ArenaManager> arn = mockConstruction(ArenaManager.class);
             MockedConstruction<GameManager> gam = mockConstruction(GameManager.class);
             MockedConstruction<MessageService> msg = mockConstruction(MessageService.class);
             MockedConstruction<NPCService> npc = mockConstruction(NPCService.class);
             MockedConstruction<HologramService> hol = mockConstruction(HologramService.class);
             MockedConstruction<PictureService> pic = mockConstruction(PictureService.class);
             MockedConstruction<MLService> mls = mockConstruction(MLService.class);
             MockedConstruction<RenderService> rnd = mockConstruction(RenderService.class);
             MockedConstruction<EvaluationService> evl = mockConstruction(EvaluationService.class);
             MockedConstruction<CommandService> cmd = mockConstruction(CommandService.class);
             MockedConstruction<ListenerService> lst = mockConstruction(ListenerService.class)) {

            PluginContext ctx = new PluginContext(plugin);
            ctx.shutdown();

            // Reverse order: listener → command → picture → hologram → npc →
            // message → game → evaluation → render → ml → arena → world →
            // data → config.
            InOrder order = inOrder(
                    lst.constructed().get(0),
                    cmd.constructed().get(0),
                    pic.constructed().get(0),
                    hol.constructed().get(0),
                    npc.constructed().get(0),
                    msg.constructed().get(0),
                    gam.constructed().get(0),
                    evl.constructed().get(0),
                    rnd.constructed().get(0),
                    mls.constructed().get(0),
                    arn.constructed().get(0),
                    wld.constructed().get(0),
                    dat.constructed().get(0),
                    cfg.constructed().get(0));

            order.verify(lst.constructed().get(0)).shutdown();
            order.verify(cmd.constructed().get(0)).shutdown();
            order.verify(pic.constructed().get(0)).shutdown();
            order.verify(hol.constructed().get(0)).shutdown();
            order.verify(npc.constructed().get(0)).shutdown();
            order.verify(msg.constructed().get(0)).shutdown();
            order.verify(gam.constructed().get(0)).shutdown();
            order.verify(evl.constructed().get(0)).shutdown();
            order.verify(rnd.constructed().get(0)).shutdown();
            order.verify(mls.constructed().get(0)).shutdown();
            order.verify(arn.constructed().get(0)).shutdown();
            order.verify(wld.constructed().get(0)).shutdown();
            order.verify(dat.constructed().get(0)).shutdown();
            order.verify(cfg.constructed().get(0)).shutdown();
        }
    }

    // ── reload: shutdown then enable ───────────────────────────────────────

    @Test
    void reloadIsShutdownThenEnable() {
        try (MockedConstruction<ConfigService> cfg = mockConstruction(ConfigService.class);
             MockedConstruction<DataService> dat = mockConstruction(DataService.class);
             MockedConstruction<WorldService> wld = mockConstruction(WorldService.class);
             MockedConstruction<ArenaManager> arn = mockConstruction(ArenaManager.class);
             MockedConstruction<GameManager> gam = mockConstruction(GameManager.class);
             MockedConstruction<MessageService> msg = mockConstruction(MessageService.class);
             MockedConstruction<NPCService> npc = mockConstruction(NPCService.class);
             MockedConstruction<HologramService> hol = mockConstruction(HologramService.class);
             MockedConstruction<PictureService> pic = mockConstruction(PictureService.class);
             MockedConstruction<MLService> mls = mockConstruction(MLService.class);
             MockedConstruction<RenderService> rnd = mockConstruction(RenderService.class);
             MockedConstruction<EvaluationService> evl = mockConstruction(EvaluationService.class);
             MockedConstruction<CommandService> cmd = mockConstruction(CommandService.class);
             MockedConstruction<ListenerService> lst = mockConstruction(ListenerService.class);
             MockedConstruction<ArenaCommand> arenaCmd = mockConstruction(ArenaCommand.class);
             MockedConstruction<MLTestCommand> mlCmd = mockConstruction(MLTestCommand.class);
             MockedConstruction<ArenaSetupListener> setupLst = mockConstruction(ArenaSetupListener.class);
             MockedConstruction<GameListener> gameLst = mockConstruction(GameListener.class);
             MockedConstruction<MLTestListener> mlLst = mockConstruction(MLTestListener.class)) {

            PluginContext ctx = new PluginContext(plugin);
            ctx.reload();

            ConfigService configMock = cfg.constructed().get(0);
            ListenerService lstMock = lst.constructed().get(0);

            // Each service must have been both shut down and enabled exactly once.
            verify(configMock, times(1)).shutdown();
            verify(configMock, times(1)).enable();
            verify(lstMock, times(1)).shutdown();
            verify(lstMock, times(1)).enable();

            // For one representative pair, verify the ordering: shutdown
            // strictly precedes the matching enable.
            InOrder order = inOrder(configMock);
            order.verify(configMock).shutdown();
            order.verify(configMock).enable();
        }
    }

    // ── getters expose constructed services ───────────────────────────────

    @Test
    void allServicesAreExposedAfterConstruction() {
        try (MockedConstruction<ConfigService> cfg = mockConstruction(ConfigService.class);
             MockedConstruction<DataService> dat = mockConstruction(DataService.class);
             MockedConstruction<WorldService> wld = mockConstruction(WorldService.class);
             MockedConstruction<ArenaManager> arn = mockConstruction(ArenaManager.class);
             MockedConstruction<GameManager> gam = mockConstruction(GameManager.class);
             MockedConstruction<MessageService> msg = mockConstruction(MessageService.class);
             MockedConstruction<NPCService> npc = mockConstruction(NPCService.class);
             MockedConstruction<HologramService> hol = mockConstruction(HologramService.class);
             MockedConstruction<PictureService> pic = mockConstruction(PictureService.class);
             MockedConstruction<MLService> mls = mockConstruction(MLService.class);
             MockedConstruction<RenderService> rnd = mockConstruction(RenderService.class);
             MockedConstruction<EvaluationService> evl = mockConstruction(EvaluationService.class);
             MockedConstruction<CommandService> cmd = mockConstruction(CommandService.class);
             MockedConstruction<ListenerService> lst = mockConstruction(ListenerService.class)) {

            PluginContext ctx = new PluginContext(plugin);

            assertNotNull(ctx.getConfigService());
            assertNotNull(ctx.getDataService());
            assertNotNull(ctx.getWorldService());
            assertNotNull(ctx.getArenaManager());
            assertNotNull(ctx.getGameManager());
            assertNotNull(ctx.getMessageService());
            assertNotNull(ctx.getNpcService());
            assertNotNull(ctx.getHologramService());
            assertNotNull(ctx.getPictureService());
            assertNotNull(ctx.getMlService());
            assertNotNull(ctx.getRenderService());
            assertNotNull(ctx.getEvaluationService());
            assertNotNull(ctx.getCommandService());
            assertNotNull(ctx.getListenerService());
        }
    }

    // ── enable() then shutdown(): every service touched twice ─────────────

    @Test
    void enableAndShutdownTouchEveryServiceExactlyOnceEach() {
        try (MockedConstruction<ConfigService> cfg = mockConstruction(ConfigService.class);
             MockedConstruction<DataService> dat = mockConstruction(DataService.class);
             MockedConstruction<WorldService> wld = mockConstruction(WorldService.class);
             MockedConstruction<ArenaManager> arn = mockConstruction(ArenaManager.class);
             MockedConstruction<GameManager> gam = mockConstruction(GameManager.class);
             MockedConstruction<MessageService> msg = mockConstruction(MessageService.class);
             MockedConstruction<NPCService> npc = mockConstruction(NPCService.class);
             MockedConstruction<HologramService> hol = mockConstruction(HologramService.class);
             MockedConstruction<PictureService> pic = mockConstruction(PictureService.class);
             MockedConstruction<MLService> mls = mockConstruction(MLService.class);
             MockedConstruction<RenderService> rnd = mockConstruction(RenderService.class);
             MockedConstruction<EvaluationService> evl = mockConstruction(EvaluationService.class);
             MockedConstruction<CommandService> cmd = mockConstruction(CommandService.class);
             MockedConstruction<ListenerService> lst = mockConstruction(ListenerService.class);
             MockedConstruction<ArenaCommand> arenaCmd = mockConstruction(ArenaCommand.class);
             MockedConstruction<MLTestCommand> mlCmd = mockConstruction(MLTestCommand.class);
             MockedConstruction<ArenaSetupListener> setupLst = mockConstruction(ArenaSetupListener.class);
             MockedConstruction<GameListener> gameLst = mockConstruction(GameListener.class);
             MockedConstruction<MLTestListener> mlLst = mockConstruction(MLTestListener.class)) {

            PluginContext ctx = new PluginContext(plugin);
            ctx.enable();
            ctx.shutdown();

            ConfigService configMock = cfg.constructed().get(0);
            verify(configMock, times(1)).enable();
            verify(configMock, times(1)).shutdown();

            ListenerService lstMock = lst.constructed().get(0);
            verify(lstMock, times(1)).enable();
            verify(lstMock, times(1)).shutdown();
        }
    }

    // ── construct without calling lifecycle: no enable/shutdown side-effects

    @Test
    void constructingContextDoesNotEnableServices() {
        try (MockedConstruction<ConfigService> cfg = mockConstruction(ConfigService.class);
             MockedConstruction<DataService> dat = mockConstruction(DataService.class);
             MockedConstruction<WorldService> wld = mockConstruction(WorldService.class);
             MockedConstruction<ArenaManager> arn = mockConstruction(ArenaManager.class);
             MockedConstruction<GameManager> gam = mockConstruction(GameManager.class);
             MockedConstruction<MessageService> msg = mockConstruction(MessageService.class);
             MockedConstruction<NPCService> npc = mockConstruction(NPCService.class);
             MockedConstruction<HologramService> hol = mockConstruction(HologramService.class);
             MockedConstruction<PictureService> pic = mockConstruction(PictureService.class);
             MockedConstruction<MLService> mls = mockConstruction(MLService.class);
             MockedConstruction<RenderService> rnd = mockConstruction(RenderService.class);
             MockedConstruction<EvaluationService> evl = mockConstruction(EvaluationService.class);
             MockedConstruction<CommandService> cmd = mockConstruction(CommandService.class);
             MockedConstruction<ListenerService> lst = mockConstruction(ListenerService.class)) {

            new PluginContext(plugin);

            // No service should have been enabled or shut down by the constructor alone.
            verify(cfg.constructed().get(0), never()).enable();
            verify(cfg.constructed().get(0), never()).shutdown();
            verify(lst.constructed().get(0), never()).enable();
            verify(lst.constructed().get(0), never()).shutdown();
        }
    }

    // ── null guard ────────────────────────────────────────────────────────

    @Test
    void nullPluginInConstructorIsRejected() {
        try {
            new PluginContext(null);
            // If no exception, fail explicitly.
            assertNull("Constructor should reject null", "non-null");
        } catch (NullPointerException expected) {
            // Lombok @NonNull annotation does this.
        }
    }
}
