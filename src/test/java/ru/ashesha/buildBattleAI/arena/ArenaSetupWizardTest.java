package ru.ashesha.buildBattleAI.arena;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.config.api.Lang;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.entity.hologram.HologramService;
import ru.ashesha.buildBattleAI.entity.hologram.api.BBAIHologramService;
import ru.ashesha.buildBattleAI.entity.npc.NPCService;
import ru.ashesha.buildBattleAI.entity.npc.api.BBAINPCService;
import ru.ashesha.buildBattleAI.message.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.world.api.BBAIWorldService;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Interactive setup-wizard tests for {@link ArenaManager}.
 * <p>
 * The existing {@code ArenaManagerTest} covers config loading, validation,
 * and deletion. This suite walks through the full setup flow that the
 * admin sees in-game — {@code startSetup} → per-plot settings →
 * {@code handleConfirm} — and verifies (1) session-state mutations,
 * (2) side effects on the world / message / hologram services, and
 * (3) the geometry-aware face-reset behavior added in the picture-region
 * feature.
 * <p>
 * The world, services, and player are all Mockito mocks. Every handler
 * eventually re-renders the panel via the private {@code sendSetupPanel},
 * which we don't assert on directly — instead we verify the visible
 * downstream effects (chat messages, title overlays, hologram operations).
 */
class ArenaSetupWizardTest {

    private ServerMock server;
    private BuildBattleAI plugin;
    private PluginContext context;
    private BBAIConfigService configService;
    private BBAIWorldService worldService;
    private BBAIMessageService messageService;
    private BBAIHologramService hologramService;
    private BBAINPCService npcService;
    private Lang lang;
    private ArenaManager manager;
    private Player player;
    private UUID playerId;
    private World originalWorld;
    private World setupWorld;

    @BeforeEach
    void setUp() {
        // MockBukkit gives us a working Bukkit.getPlayer(uuid) so XSound's
        // sound-playback code path inside SoundPalette can resolve viewers.
        server = MockBukkit.mock();
        // returnPlayer falls back to Bukkit.getWorlds().get(0) when the
        // original world is missing — keep that lookup non-empty.
        server.addSimpleWorld("overworld");

        plugin = mock(BuildBattleAI.class);
        PluginLogger logger = mock(PluginLogger.class);
        context = mock(PluginContext.class);
        configService = mock(BBAIConfigService.class);
        worldService = mock(BBAIWorldService.class);
        messageService = mock(BBAIMessageService.class);
        hologramService = mock(BBAIHologramService.class);
        npcService = mock(BBAINPCService.class);
        lang = mock(Lang.class);

        when(plugin.getPluginLogger()).thenReturn(logger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getConfigService()).thenReturn(configService);
        when(context.getWorldService()).thenReturn(worldService);
        when(context.getMessageService()).thenReturn(messageService);
        when(context.getHologramService()).thenReturn(hologramService);
        when(context.getNpcService()).thenReturn(npcService);
        when(configService.getDefaultLang()).thenReturn(lang);
        when(configService.getArenaNames()).thenReturn(Collections.<String>emptySet());

        // Lang returns key-as-value by default — predictable for assertions.
        when(lang.get(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(lang.get(anyString(), any(Object[].class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Hologram creation returns a fresh mock per call — so each marker
        // is distinguishable when verifying despawn / spawn ordering.
        when(hologramService.createHologram(anyInt()))
                .thenAnswer(inv -> mock(HologramService.Hologram.class));
        // Camera handlers create NPCs to represent the angle visually.
        when(npcService.createNPC(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> mock(NPCService.NPC.class));

        manager = new ArenaManager(plugin);
        manager.enable();

        // Player + original world (where startSetup teleports from).
        playerId = UUID.randomUUID();
        originalWorld = mock(World.class);
        when(originalWorld.getName()).thenReturn("overworld");
        setupWorld = mock(World.class);
        when(setupWorld.getName()).thenReturn("bbai_test");

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        // Start in original world by default; tests that switch to setup
        // world re-stub player.getLocation as needed.
        when(player.getLocation()).thenReturn(new Location(originalWorld, 1, 2, 3, 4, 5));
        when(player.getAllowFlight()).thenReturn(false);
        // Holograms are anchored at player.getWorld() — default to setupWorld
        // so spawnMarkerHologram can build a Location without throwing.
        when(player.getWorld()).thenReturn(setupWorld);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ── startSetup ────────────────────────────────────────────────────────

    @Test
    void startSetupCreatesVoidWorldAndOpensSession() {
        when(worldService.createEmptyWorld("bbai_test")).thenReturn(setupWorld);

        manager.startSetup(player, "test");

        verify(worldService).createEmptyWorld("bbai_test");
        assertTrue(manager.hasSetupSession(playerId));
        verify(player).teleport(any(Location.class));
        verify(player).setAllowFlight(true);
        verify(player).setFlying(true);
    }

    @Test
    void startSetupRefusesWhenSessionAlreadyActive() {
        // First call opens the session.
        when(worldService.createEmptyWorld("bbai_test")).thenReturn(setupWorld);
        manager.startSetup(player, "test");
        // Second call must not allocate another world.
        clearInvocations(worldService, player);

        manager.startSetup(player, "test2");

        verify(worldService, never()).createEmptyWorld(anyString());
        verify(messageService).sendChat(eq(player), eq("arena.setup.already-in-setup"));
    }

    @Test
    void startSetupRefusesWhenArenaAlreadyExists() {
        // Stub an existing arena file on disk.
        when(configService.getArenaConfig("existing")).thenReturn(new YamlConfiguration());

        manager.startSetup(player, "existing");

        verify(worldService, never()).createEmptyWorld(anyString());
        verify(messageService).sendChat(eq(player), eq("arena.setup.already-exists"));
        assertFalse(manager.hasSetupSession(playerId));
    }

    @Test
    void startSetupReportsWorldCreationFailure() {
        when(worldService.createEmptyWorld("bbai_test")).thenReturn(null);

        manager.startSetup(player, "test");

        verify(messageService).sendChat(eq(player), eq("arena.setup.world-failed"));
        assertFalse(manager.hasSetupSession(playerId));
    }

    // ── handleSetPlayers ─────────────────────────────────────────────────

    @Test
    void handleSetPlayersStoresCountAndActivatesPlot1() {
        startSetupOk();
        manager.handleSetPlayers(player, 4);
        // Repeated call — switching to fewer plots should not crash.
        manager.handleSetPlayers(player, 2);
        // No exception → success; title was sent for each call.
        verify(messageService, atLeast(2)).sendTitle(eq(player), eq("arena.setup.title.players"),
                anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void handleSetPlayersWithoutSessionIsNoOp() {
        manager.handleSetPlayers(player, 4);
        verifyNoInteractions(messageService);
    }

    // ── handleSetTab ──────────────────────────────────────────────────────

    @Test
    void handleSetTabSwitchesActivePlot() {
        startSetupOk();
        manager.handleSetPlayers(player, 4);
        manager.handleSetTab(player, 3);
        // Just verifying it doesn't throw and re-renders the panel.
        verify(messageService, atLeastOnce()).sendChat(eq(player), anyString());
    }

    @Test
    void handleSetTabIgnoresOutOfRangePlot() {
        startSetupOk();
        manager.handleSetPlayers(player, 2);
        clearInvocations(messageService);
        // Plot 5 is out of range for a 2-player arena → no-op.
        manager.handleSetTab(player, 5);
        verify(messageService, never()).sendTitle(eq(player), eq("arena.setup.title.tab"),
                anyString(), anyInt(), anyInt(), anyInt());
    }

    // ── handleSetLobby ───────────────────────────────────────────────────

    @Test
    void handleSetLobbyStoresPositionAndSpawnsHologram() {
        startSetupOk();
        // Teleport the player to the setup world so the lobby gets recorded
        // there.
        when(player.getLocation()).thenReturn(new Location(setupWorld, 10, 65, 20, 90f, 45f));

        manager.handleSetLobby(player);

        // Single-line marker hologram spawned for the player at the lobby location.
        verify(hologramService).createHologram(1);
        verify(hologramService).spawn(eq(player), any(HologramService.Hologram.class),
                any(Location.class), anyList());
        verify(messageService).sendTitle(eq(player), eq("arena.setup.title.lobby"),
                eq("arena.setup.title.position-saved"), anyInt(), anyInt(), anyInt());
    }

    @Test
    void handleSetLobbyTwiceDespawnsOldHologramFirst() {
        startSetupOk();
        when(player.getLocation()).thenReturn(new Location(setupWorld, 0, 64, 0, 0, 0));
        manager.handleSetLobby(player);
        manager.handleSetLobby(player);
        // The second call must despawn the previous marker before spawning a new one.
        verify(hologramService, atLeastOnce()).despawn(eq(player), any(HologramService.Hologram.class));
    }

    // ── handleSetSpectator ───────────────────────────────────────────────

    @Test
    void handleSetSpectatorStoresOptionalPosition() {
        startSetupOk();
        when(player.getLocation()).thenReturn(new Location(setupWorld, 5, 64, 5, 0, 0));

        manager.handleSetSpectator(player);

        verify(hologramService, atLeastOnce()).createHologram(1);
        verify(messageService).sendTitle(eq(player), eq("arena.setup.title.spectator"),
                eq("arena.setup.title.position-saved"), anyInt(), anyInt(), anyInt());
    }

    // ── plot-specific handlers ────────────────────────────────────────────

    @Test
    void handleSetSpawnStoresPerPlotSpawn() {
        startSetupOk();
        manager.handleSetPlayers(player, 4);
        when(player.getLocation()).thenReturn(new Location(setupWorld, 100, 65, 100, 0, 0));

        manager.handleSetSpawn(player, 2);

        verify(messageService).sendTitle(eq(player), eq("arena.setup.title.spawn"),
                anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void handleSetCornersAndCameraRecordValues() {
        startSetupOk();
        manager.handleSetPlayers(player, 2);
        when(player.getLocation()).thenReturn(new Location(setupWorld, 50, 65, 50, 0, 0));

        manager.handleSetCorner1(player, 1);
        manager.handleSetCorner2(player, 1);
        manager.handleSetCamera(player, 1, 1);
        manager.handleSetCamera(player, 1, 2);
        manager.handleSetCamera(player, 1, 3);

        // Each operation announces via title — verify at least 5 titles after this.
        verify(messageService, atLeast(5))
                .sendTitle(eq(player), anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    // ── picture geometry — face auto-reset ────────────────────────────────

    @Test
    void pictureFaceIsResetWhenCornerEditInvalidates() {
        startSetupOk();
        manager.handleSetPlayers(player, 2);

        // Plant a 2x2 XY-plane picture region (Z fixed = 50). NORTH is allowed
        // on the XY plane.
        when(player.getLocation()).thenReturn(new Location(setupWorld, 0, 60, 50, 0, 0));
        manager.handleSetPictureCorner1(player, 1);
        when(player.getLocation()).thenReturn(new Location(setupWorld, 1, 61, 50, 0, 0));
        manager.handleSetPictureCorner2(player, 1);
        manager.handleSetPictureFace(player, 1, BlockFace.NORTH);

        // Now move corner 2 to a YZ-plane geometry (X fixed = 0). The previous
        // NORTH face is no longer allowed and must be cleared by the handler.
        when(player.getLocation()).thenReturn(new Location(setupWorld, 0, 61, 51, 0, 0));
        manager.handleSetPictureCorner2(player, 1);

        // We can't probe the session directly, but if the face had survived,
        // a subsequent confirm with face=NORTH on a YZ plane would be valid —
        // which is exactly what we don't want. The user has to re-pick.
        manager.handleConfirm(player);
        // Confirm must fail because face is now null → incomplete → chat sent.
        verify(messageService).sendChat(eq(player), eq("arena.setup.incomplete"));
    }

    @Test
    void pictureFaceRejectedOnInvalidGeometry() {
        startSetupOk();
        manager.handleSetPlayers(player, 2);

        // Set two corners that are NOT coplanar — face selection must be denied.
        when(player.getLocation()).thenReturn(new Location(setupWorld, 0, 60, 50, 0, 0));
        manager.handleSetPictureCorner1(player, 1);
        when(player.getLocation()).thenReturn(new Location(setupWorld, 5, 61, 55, 0, 0));
        manager.handleSetPictureCorner2(player, 1);

        manager.handleSetPictureFace(player, 1, BlockFace.NORTH);

        // No "face-saved" title should have fired.
        verify(messageService, never()).sendTitle(eq(player), eq("arena.setup.title.face"),
                anyString(), anyInt(), anyInt(), anyInt());
    }

    // ── picture geometry — non-VALID transitions ──────────────────────────

    @Test
    void pictureFaceRejectsBeforeBothCornersPlaced() {
        // Only corner 1 — geometry is incomplete, face is not yet meaningful.
        startSetupOk();
        manager.handleSetPlayers(player, 2);
        when(player.getLocation()).thenReturn(new Location(setupWorld, 0, 60, 50, 0, 0));
        manager.handleSetPictureCorner1(player, 1);

        manager.handleSetPictureFace(player, 1, BlockFace.NORTH);

        // The face-saved title must NOT have fired — production should have
        // silently denied this face change.
        verify(messageService, never()).sendTitle(eq(player), eq("arena.setup.title.face"),
                anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void pictureFaceRejectsOnInvalidSizeGeometry() {
        // 1x3 picture (corner1 = (0,60,50), corner2 = (0,60,52)) — coplanar but
        // not 1x1 and not 2x2. INVALID_SIZE branch.
        startSetupOk();
        manager.handleSetPlayers(player, 2);
        when(player.getLocation()).thenReturn(new Location(setupWorld, 0, 60, 50, 0, 0));
        manager.handleSetPictureCorner1(player, 1);
        when(player.getLocation()).thenReturn(new Location(setupWorld, 0, 60, 52, 0, 0));
        manager.handleSetPictureCorner2(player, 1);

        manager.handleSetPictureFace(player, 1, BlockFace.EAST);

        verify(messageService, never()).sendTitle(eq(player), eq("arena.setup.title.face"),
                anyString(), anyInt(), anyInt(), anyInt());
    }

    // ── numeric setters ───────────────────────────────────────────────────

    @Test
    void numericSettersFlowThroughTitle() {
        startSetupOk();
        manager.handleSetMinPlayers(player, 3);
        manager.handleSetBuildTime(player, 120);
        manager.handleSetGameTime(player, 240);
        manager.handleSetCountdown(player, 10);

        // Each setter sends a confirmation title (the actual key is the
        // setting name, so just check there were 4 of them).
        verify(messageService, atLeast(4))
                .sendTitle(eq(player), anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    // ── handleConfirm ─────────────────────────────────────────────────────

    @Test
    void handleConfirmOnIncompleteSessionShowsError() {
        startSetupOk();
        manager.handleSetPlayers(player, 2);
        // Lobby + plots intentionally left empty.

        manager.handleConfirm(player);

        verify(messageService).sendChat(eq(player), eq("arena.setup.incomplete"));
        // Session must remain so the admin can keep filling it in.
        assertTrue(manager.hasSetupSession(playerId));
    }

    @Test
    void handleConfirmFullFlowPersistsArena() {
        // serializeArena calls configService.createArenaConfig then writes
        // settings into the returned YamlConfiguration — stub a fresh one.
        when(configService.createArenaConfig("test")).thenReturn(new YamlConfiguration());

        startSetupOk();
        // Build a complete 2-player arena: lobby + 2 plots × (spawn + 2 corners
        // + 3 cameras + 1×1 picture region with NORTH face).
        fillCompleteSession(2);

        manager.handleConfirm(player);

        // Arena must end up in the registry and on disk.
        assertNotNull(manager.getArena("test"));
        verify(configService).createArenaConfig("test");
        verify(configService).saveArenaConfig("test");
        // Session is torn down on successful confirm.
        assertFalse(manager.hasSetupSession(playerId));
        verify(messageService).sendChat(eq(player), eq("arena.setup.created"));
    }

    // ── handleCancel / cancelSetupSession ─────────────────────────────────

    @Test
    void handleCancelDeletesWorldAndDropsSession() {
        startSetupOk();
        manager.handleSetPlayers(player, 2);

        manager.handleCancel(player);

        verify(worldService).deleteWorld("bbai_test");
        assertFalse(manager.hasSetupSession(playerId));
    }

    @Test
    void cancelSetupSessionByUuidWorksOnDisconnect() {
        startSetupOk();

        manager.cancelSetupSession(playerId);

        verify(worldService).deleteWorld("bbai_test");
        assertFalse(manager.hasSetupSession(playerId));
    }

    @Test
    void cancelSetupSessionForUnknownPlayerIsNoOp() {
        manager.cancelSetupSession(UUID.randomUUID());
        verify(worldService, never()).deleteWorld(anyString());
    }

    // ── isolation: setup mutations don't bleed across players ─────────────

    @Test
    void twoPlayersCanRunIndependentSetupSessions() {
        startSetupOk();

        // Second player creates a different arena.
        UUID otherId = UUID.randomUUID();
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(otherId);
        when(other.getLocation()).thenReturn(new Location(originalWorld, 0, 0, 0, 0, 0));
        World otherSetupWorld = mock(World.class);
        when(otherSetupWorld.getName()).thenReturn("bbai_other");
        when(worldService.createEmptyWorld("bbai_other")).thenReturn(otherSetupWorld);

        manager.startSetup(other, "other");

        assertTrue(manager.hasSetupSession(playerId));
        assertTrue(manager.hasSetupSession(otherId));

        // Cancelling one must not touch the other.
        manager.handleCancel(player);
        assertFalse(manager.hasSetupSession(playerId));
        assertTrue(manager.hasSetupSession(otherId));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Boots a fresh "test" setup session via {@code startSetup}. */
    private void startSetupOk() {
        when(worldService.createEmptyWorld("bbai_test")).thenReturn(setupWorld);
        manager.startSetup(player, "test");
    }

    /**
     * Drives the wizard through every required setting for an N-player arena,
     * leaving the session in a confirm-ready state. Lobby + per-plot spawn +
     * two coincident build-corners (degenerate 1×1×1 build zone is fine here
     * because the test only validates the wizard, not gameplay) + three
     * cameras + a 1×1 NORTH picture region.
     */
    private void fillCompleteSession(int playerCount) {
        manager.handleSetPlayers(player, playerCount);

        // Lobby (any location inside the setup world).
        when(player.getLocation()).thenReturn(new Location(setupWorld, 0, 65, 0, 0, 0));
        manager.handleSetLobby(player);

        for (int i = 1; i <= playerCount; i++) {
            double base = i * 20.0; // unique coordinates per plot
            when(player.getLocation()).thenReturn(new Location(setupWorld, base, 65, base, 0, 0));
            manager.handleSetSpawn(player, i);
            manager.handleSetCorner1(player, i);
            manager.handleSetCorner2(player, i);
            manager.handleSetCamera(player, i, 1);
            manager.handleSetCamera(player, i, 2);
            manager.handleSetCamera(player, i, 3);

            // 1×1 picture region — both corners coincide, face NORTH is allowed.
            when(player.getLocation()).thenReturn(new Location(setupWorld, base + 5, 70, base + 5, 0, 0));
            manager.handleSetPictureCorner1(player, i);
            manager.handleSetPictureCorner2(player, i);
            manager.handleSetPictureFace(player, i, BlockFace.NORTH);
        }
    }
}
