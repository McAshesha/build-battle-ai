package ru.ashesha.buildBattleAI.commands;

import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.arena.api.BBAIArenaManager;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.config.api.Lang;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.game.ArenaState;
import ru.ashesha.buildBattleAI.game.api.BBAIGameManager;
import ru.ashesha.buildBattleAI.message.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.message.micro.ChatMicroService;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ArenaCommand}.
 * <p>
 * Every public and internal subcommand is exercised through a direct call to
 * {@code execute(sender, args)} (invoked reflectively because the method is
 * protected). The plugin context is wired with Mockito stubs so the command
 * dispatcher's behavior — argument parsing, validation, error reporting, and
 * delegation to {@link BBAIArenaManager} / {@link BBAIGameManager} — can be
 * verified without standing up a live server.
 * <p>
 * Tab completion is covered separately because it short-circuits on
 * {@link Arena} lookups and is independent of the dispatch path.
 */
class ArenaCommandTest {

    private BuildBattleAI plugin;
    private BBAIArenaManager arenaManager;
    private BBAIGameManager gameManager;
    private BBAIMessageService messageService;
    private BBAIConfigService configService;
    private Lang lang;
    private ArenaCommand command;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        PluginLogger logger = mock(PluginLogger.class);
        PluginContext context = mock(PluginContext.class);
        arenaManager = mock(BBAIArenaManager.class);
        gameManager = mock(BBAIGameManager.class);
        messageService = mock(BBAIMessageService.class);
        configService = mock(BBAIConfigService.class);
        lang = mock(Lang.class);

        when(plugin.getPluginLogger()).thenReturn(logger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getArenaManager()).thenReturn(arenaManager);
        when(context.getGameManager()).thenReturn(gameManager);
        when(context.getMessageService()).thenReturn(messageService);
        when(context.getConfigService()).thenReturn(configService);
        when(configService.getDefaultLang()).thenReturn(lang);
        // Default — any key returns the key itself (predictable for assertions).
        when(lang.get(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(lang.get(anyString(), any(Object[].class)))
                .thenAnswer(inv -> inv.getArgument(0));

        command = new ArenaCommand(plugin);
    }

    // ── usage / no-args ───────────────────────────────────────────────────

    @Test
    void noArgsSendsUsageToPlayer() throws Exception {
        Player player = playerMock();
        invokeExecute(player, new String[]{});
        verify(messageService).sendChat(eq(player), eq("arena.usage"));
    }

    @Test
    void noArgsSendsPlainUsageToConsole() throws Exception {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        invokeExecute(console, new String[]{});
        verify(console).sendMessage(startsWith("Usage:"));
    }

    @Test
    void unknownSubcommandSendsUsage() throws Exception {
        Player player = playerMock();
        invokeExecute(player, new String[]{"banana"});
        verify(messageService).sendChat(eq(player), eq("arena.usage"));
    }

    // ── /bbai create ──────────────────────────────────────────────────────

    @Test
    void createWithoutNameSendsValidationMessage() throws Exception {
        Player player = playerMock();
        invokeExecute(player, new String[]{"create"});
        verify(messageService).sendChat(eq(player), eq("arena.setup.name-required"));
        verify(arenaManager, never()).startSetup(any(), any());
    }

    @Test
    void createWithNameDelegatesToArenaManager() throws Exception {
        Player player = playerMock();
        invokeExecute(player, new String[]{"create", "lobby"});
        verify(arenaManager).startSetup(player, "lobby");
    }

    @Test
    void createFromConsoleIsRejected() throws Exception {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        invokeExecute(console, new String[]{"create", "lobby"});
        verify(console).sendMessage(eq("This command can only be used by players."));
        verify(arenaManager, never()).startSetup(any(), any());
    }

    // ── /bbai list ────────────────────────────────────────────────────────

    @Test
    void listShowsEmptyMessageWhenNoArenas() throws Exception {
        Player player = playerMock();
        when(arenaManager.getArenas()).thenReturn(Collections.emptyList());
        invokeExecute(player, new String[]{"list"});

        verify(messageService, atLeastOnce()).sendChat(eq(player), eq("arena.list.empty"));
    }

    @Test
    void listShowsClickableJoinForJoinableArena() throws Exception {
        Player player = playerMock();
        Arena arena = mock(Arena.class);
        when(arena.enabled()).thenReturn(true);
        when(arena.name()).thenReturn("lobby");
        when(arena.maxPlayers()).thenReturn(8);
        when(arenaManager.getArenas()).thenReturn(Collections.singletonList(arena));
        when(gameManager.getArenaState("lobby")).thenReturn(ArenaState.WAITING);
        when(gameManager.getPlayerCount("lobby")).thenReturn(3);

        invokeExecute(player, new String[]{"list"});

        verify(messageService, atLeastOnce()).sendChat(eq(player), any(ChatMicroService.ChatMessage.class));
    }

    @Test
    void listSkipsDisabledArenas() throws Exception {
        Player player = playerMock();
        Arena disabled = mock(Arena.class);
        when(disabled.enabled()).thenReturn(false);
        when(arenaManager.getArenas()).thenReturn(Collections.singletonList(disabled));

        invokeExecute(player, new String[]{"list"});

        // Only divider + header + divider are sent (3 plain string calls);
        // no ChatMessage with the disabled arena's join entry.
        verify(messageService, never()).sendChat(eq(player), any(ChatMicroService.ChatMessage.class));
    }

    @Test
    void listShowsInProgressForPlayingArena() throws Exception {
        Player player = playerMock();
        Arena arena = mock(Arena.class);
        when(arena.enabled()).thenReturn(true);
        when(arena.name()).thenReturn("a1");
        when(arena.maxPlayers()).thenReturn(8);
        when(arenaManager.getArenas()).thenReturn(Collections.singletonList(arena));
        when(gameManager.getArenaState("a1")).thenReturn(ArenaState.PLAYING);
        when(gameManager.getPlayerCount("a1")).thenReturn(4);

        invokeExecute(player, new String[]{"list"});

        // We can't easily introspect the ChatMessage components without a
        // capturing argument matcher — but we can at least verify that the
        // "playing" state key was queried (sanity check on the branch).
        verify(lang).get("arena.list.state-playing");
    }

    @Test
    void listShowsFullForJoinableArenaAtCapacity() throws Exception {
        Player player = playerMock();
        Arena arena = mock(Arena.class);
        when(arena.enabled()).thenReturn(true);
        when(arena.name()).thenReturn("a1");
        when(arena.maxPlayers()).thenReturn(2);
        when(arenaManager.getArenas()).thenReturn(Collections.singletonList(arena));
        when(gameManager.getArenaState("a1")).thenReturn(ArenaState.WAITING);
        when(gameManager.getPlayerCount("a1")).thenReturn(2);

        invokeExecute(player, new String[]{"list"});

        // Full button uses the "full-btn" key — verifying via direct lang call.
        verify(lang).get("arena.list.full-btn");
    }

    // ── /bbai join ────────────────────────────────────────────────────────

    @Test
    void joinWithoutArenaShowsUsage() throws Exception {
        Player player = playerMock();
        invokeExecute(player, new String[]{"join"});
        verify(messageService).sendChat(eq(player), eq("arena.usage"));
        verify(gameManager, never()).joinArena(any(), any());
    }

    @Test
    void joinDelegatesToGameManager() throws Exception {
        Player player = playerMock();
        invokeExecute(player, new String[]{"join", "lobby"});
        verify(gameManager).joinArena(player, "lobby");
    }

    @Test
    void joinFromConsoleIsRejected() throws Exception {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        invokeExecute(console, new String[]{"join", "lobby"});
        verify(console).sendMessage(eq("This command can only be used by players."));
        verify(gameManager, never()).joinArena(any(), any());
    }

    // ── /bbai leave ───────────────────────────────────────────────────────

    @Test
    void leaveNotInGameSendsMessage() throws Exception {
        Player player = playerMock();
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(gameManager.isInGame(uuid)).thenReturn(false);

        invokeExecute(player, new String[]{"leave"});

        verify(messageService).sendChat(eq(player), eq("game.leave.not-in-game"));
        verify(gameManager, never()).leaveArena(any());
    }

    @Test
    void leaveInGameDelegatesAndSendsSuccess() throws Exception {
        Player player = playerMock();
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(gameManager.isInGame(uuid)).thenReturn(true);

        invokeExecute(player, new String[]{"leave"});

        verify(gameManager).leaveArena(player);
        verify(messageService).sendChat(eq(player), eq("game.leave.success"));
    }

    // ── /bbai delete ──────────────────────────────────────────────────────

    @Test
    void deleteWithoutNameShowsUsage() throws Exception {
        Player player = playerMock();
        invokeExecute(player, new String[]{"delete"});
        verify(messageService).sendChat(eq(player), eq("arena.delete.usage"));
        verify(arenaManager, never()).deleteArena(any());
    }

    @Test
    void deleteNonexistentArenaShowsNotFound() throws Exception {
        Player player = playerMock();
        when(arenaManager.getArena("ghost")).thenReturn(null);

        invokeExecute(player, new String[]{"delete", "ghost"});

        verify(messageService).sendChat(eq(player), eq("arena.delete.not-found"));
        verify(arenaManager, never()).deleteArena(any());
    }

    @Test
    void deleteExistingArenaDelegatesAndConfirms() throws Exception {
        Player player = playerMock();
        Arena arena = mock(Arena.class);
        when(arenaManager.getArena("lobby")).thenReturn(arena);

        invokeExecute(player, new String[]{"delete", "lobby"});

        verify(arenaManager).deleteArena("lobby");
        verify(messageService).sendChat(eq(player), eq("arena.delete.success"));
    }

    // ── /bbai setup — session gating ──────────────────────────────────────

    @Test
    void setupWithoutSessionSendsNoSessionMessage() throws Exception {
        Player player = playerMock();
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(arenaManager.hasSetupSession(uuid)).thenReturn(false);

        invokeExecute(player, new String[]{"setup", "lobby"});

        verify(messageService).sendChat(eq(player), eq("arena.setup.no-session"));
        verify(arenaManager, never()).handleSetLobby(any());
    }

    @Test
    void setupFromConsoleIsSilentNoOp() throws Exception {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        invokeExecute(console, new String[]{"setup", "lobby"});
        verifyNoInteractions(arenaManager);
    }

    @Test
    void setupWithoutActionIsNoOp() throws Exception {
        Player player = playerMock();
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(arenaManager.hasSetupSession(uuid)).thenReturn(true);

        invokeExecute(player, new String[]{"setup"});

        verify(arenaManager, never()).handleSetLobby(any());
    }

    // ── /bbai setup <action> — per-action dispatch ─────────────────────────

    @Test
    void setupPlayersValidCountIsApplied() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "players", "4"});
        verify(arenaManager).handleSetPlayers(player, 4);
    }

    @Test
    void setupPlayersBelowMinimumIsRejected() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "players", "1"});
        verify(arenaManager, never()).handleSetPlayers(any(), anyInt());
    }

    @Test
    void setupPlayersAboveMaximumIsRejected() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "players", "9"});
        verify(arenaManager, never()).handleSetPlayers(any(), anyInt());
    }

    @Test
    void setupPlayersNonNumericIsRejected() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "players", "many"});
        verify(arenaManager, never()).handleSetPlayers(any(), anyInt());
    }

    @Test
    void setupPlayersMissingArgIsNoOp() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "players"});
        verify(arenaManager, never()).handleSetPlayers(any(), anyInt());
    }

    @Test
    void setupLobbyDelegates() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "lobby"});
        verify(arenaManager).handleSetLobby(player);
    }

    @Test
    void setupSpectatorDelegates() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "spectator"});
        verify(arenaManager).handleSetSpectator(player);
    }

    @Test
    void setupSpawnWithPlotIndex() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "spawn", "2"});
        verify(arenaManager).handleSetSpawn(player, 2);
    }

    @Test
    void setupSpawnZeroPlotIsRejected() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "spawn", "0"});
        verify(arenaManager, never()).handleSetSpawn(any(), anyInt());
    }

    @Test
    void setupCorner1Delegates() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "corner1", "3"});
        verify(arenaManager).handleSetCorner1(player, 3);
    }

    @Test
    void setupCorner2Delegates() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "corner2", "3"});
        verify(arenaManager).handleSetCorner2(player, 3);
    }

    @Test
    void setupCamera1Delegates() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "camera1", "2"});
        verify(arenaManager).handleSetCamera(player, 2, 1);
    }

    @Test
    void setupCamera2Delegates() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "camera2", "2"});
        verify(arenaManager).handleSetCamera(player, 2, 2);
    }

    @Test
    void setupCamera3Delegates() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "camera3", "2"});
        verify(arenaManager).handleSetCamera(player, 2, 3);
    }

    @Test
    void setupTabSwitchesActivePlot() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "tab", "2"});
        verify(arenaManager).handleSetTab(player, 2);
    }

    @Test
    void setupPicCorner1Delegates() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "pic-corner1", "1"});
        verify(arenaManager).handleSetPictureCorner1(player, 1);
    }

    @Test
    void setupPicCorner2Delegates() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "pic-corner2", "1"});
        verify(arenaManager).handleSetPictureCorner2(player, 1);
    }

    // ── pic-face — face parsing matrix ────────────────────────────────────

    @Test
    void setupPicFaceAcceptsNorth() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "pic-face", "1", "NORTH"});
        verify(arenaManager).handleSetPictureFace(player, 1, BlockFace.NORTH);
    }

    @Test
    void setupPicFaceAcceptsLowercase() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "pic-face", "1", "east"});
        verify(arenaManager).handleSetPictureFace(player, 1, BlockFace.EAST);
    }

    @Test
    void setupPicFaceRejectsUp() throws Exception {
        // UP is not a cardinal direction — must be rejected silently.
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "pic-face", "1", "UP"});
        verify(arenaManager, never()).handleSetPictureFace(any(), anyInt(), any());
    }

    @Test
    void setupPicFaceRejectsGarbage() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "pic-face", "1", "DIAGONAL"});
        verify(arenaManager, never()).handleSetPictureFace(any(), anyInt(), any());
    }

    @Test
    void setupPicFaceMissingFaceIsNoOp() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "pic-face", "1"});
        verify(arenaManager, never()).handleSetPictureFace(any(), anyInt(), any());
    }

    // ── numeric range validators (min/build/game/countdown) ───────────────

    @Test
    void setupMinPlayersValidIsApplied() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "minplayers", "4"});
        verify(arenaManager).handleSetMinPlayers(player, 4);
    }

    @Test
    void setupMinPlayersUsesDefaultCapEvenWhenMaxNotSet() throws Exception {
        // The command-layer validator falls back to a cap of 8 when maxPlayers
        // hasn't been set in the session yet. A value within that cap must be
        // accepted; a value above it must be rejected.
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "minplayers", "8"});
        verify(arenaManager).handleSetMinPlayers(player, 8);

        invokeExecute(player, new String[]{"setup", "minplayers", "9"});
        verify(arenaManager, never()).handleSetMinPlayers(any(), eq(9));
        verify(messageService).sendChat(eq(player), eq("arena.setup.minplayers.invalid"));
    }

    @Test
    void setupMinPlayersTooLowIsRejectedWithMessage() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "minplayers", "1"});
        verify(arenaManager, never()).handleSetMinPlayers(any(), anyInt());
        verify(messageService).sendChat(eq(player), eq("arena.setup.minplayers.invalid"));
    }

    @Test
    void setupBuildTimeValidIsAppliedInSeconds() throws Exception {
        // 2.5 minutes → 150 seconds.
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "buildtime", "2.5"});
        verify(arenaManager).handleSetBuildTime(player, 150);
    }

    @Test
    void setupBuildTimeBelowHalfMinuteIsRejected() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "buildtime", "0.4"});
        verify(arenaManager, never()).handleSetBuildTime(any(), anyInt());
    }

    @Test
    void setupBuildTimeAboveTenMinutesIsRejected() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "buildtime", "10.5"});
        verify(arenaManager, never()).handleSetBuildTime(any(), anyInt());
    }

    @Test
    void setupGameTimeValidIsAppliedInSeconds() throws Exception {
        // 5 minutes → 300 seconds.
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "gametime", "5"});
        verify(arenaManager).handleSetGameTime(player, 300);
    }

    @Test
    void setupGameTimeOutOfRangeIsRejected() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "gametime", "0.5"});
        verify(arenaManager, never()).handleSetGameTime(any(), anyInt());
        invokeExecute(player, new String[]{"setup", "gametime", "31"});
        verify(arenaManager, never()).handleSetGameTime(any(), anyInt());
    }

    @Test
    void setupCountdownValidIsApplied() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "countdown", "10"});
        verify(arenaManager).handleSetCountdown(player, 10);
    }

    @Test
    void setupCountdownOutOfRangeIsRejected() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "countdown", "2"});
        verify(arenaManager, never()).handleSetCountdown(any(), anyInt());
        invokeExecute(player, new String[]{"setup", "countdown", "61"});
        verify(arenaManager, never()).handleSetCountdown(any(), anyInt());
    }

    // ── confirm / cancel ───────────────────────────────────────────────────

    @Test
    void setupConfirmDelegates() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "confirm"});
        verify(arenaManager).handleConfirm(player);
    }

    @Test
    void setupCancelDelegates() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "cancel"});
        verify(arenaManager).handleCancel(player);
    }

    @Test
    void setupUnknownActionIsSilentNoOp() throws Exception {
        Player player = setupPlayer();
        invokeExecute(player, new String[]{"setup", "frobnicate", "42"});
        verifyNoInteractions(messageService);
    }

    // ── tab completion ────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void tabCompletionSuggestsPublicSubcommands() throws Exception {
        Player player = playerMock();
        List<String> suggestions = invokeSuggest(player, new String[]{""});
        assertTrue(suggestions.containsAll(Arrays.asList("create", "list", "delete", "join", "leave")));
        // Internal "setup" must not appear.
        assertFalse(suggestions.contains("setup"));
    }

    @Test
    void tabCompletionFiltersByPrefix() throws Exception {
        Player player = playerMock();
        List<String> suggestions = invokeSuggest(player, new String[]{"de"});
        assertEquals(Collections.singletonList("delete"), suggestions);
    }

    @Test
    void tabCompletionFilterIsCaseInsensitive() throws Exception {
        Player player = playerMock();
        List<String> suggestions = invokeSuggest(player, new String[]{"L"});
        assertTrue(suggestions.contains("list"));
        assertTrue(suggestions.contains("leave"));
    }

    @Test
    void tabCompletionDeleteSuggestsArenaNames() throws Exception {
        Player player = playerMock();
        when(arenaManager.getArenaNames()).thenReturn(new HashSet<>(Arrays.asList("lobby", "pvp", "lab")));

        List<String> suggestions = invokeSuggest(player, new String[]{"delete", "l"});

        assertTrue(suggestions.contains("lobby"));
        assertTrue(suggestions.contains("lab"));
        assertFalse(suggestions.contains("pvp"));
    }

    @Test
    void tabCompletionJoinSuggestsArenaNames() throws Exception {
        Player player = playerMock();
        when(arenaManager.getArenaNames()).thenReturn(new HashSet<>(Collections.singletonList("lobby")));

        List<String> suggestions = invokeSuggest(player, new String[]{"join", ""});

        assertTrue(suggestions.contains("lobby"));
    }

    @Test
    void tabCompletionOtherSubcommandsReturnEmpty() throws Exception {
        Player player = playerMock();
        List<String> suggestions = invokeSuggest(player, new String[]{"create", "x"});
        assertTrue(suggestions.isEmpty());
    }

    @Test
    void tabCompletionThirdArgReturnsEmpty() throws Exception {
        Player player = playerMock();
        List<String> suggestions = invokeSuggest(player, new String[]{"delete", "lobby", "extra"});
        assertTrue(suggestions.isEmpty());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Builds a basic player mock — no UUID required by default. */
    private static Player playerMock() {
        return mock(Player.class);
    }

    /**
     * Builds a player mock whose UUID is already registered as an active
     * setup session — every setup-subcommand test reuses this fixture.
     */
    private Player setupPlayer() {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(arenaManager.hasSetupSession(uuid)).thenReturn(true);
        return player;
    }

    /**
     * Reflectively invokes {@code ArenaCommand.execute(sender, args)}.
     * The method is {@code protected} on the {@link CommandService.PluginCommand}
     * base, so we can't call it directly from a different package.
     */
    private void invokeExecute(CommandSender sender, String[] args) throws Exception {
        Method m = findMethodWithName("execute");
        m.setAccessible(true);
        m.invoke(command, sender, args);
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeSuggest(CommandSender sender, String[] args) throws Exception {
        Method m = findMethodWithName("suggest");
        m.setAccessible(true);
        return (List<String>) m.invoke(command, sender, args);
    }

    private Method findMethodWithName(String name) {
        Class<?> c = command.getClass();
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name))
                    return m;
            }
            c = c.getSuperclass();
        }
        throw new IllegalStateException("Method " + name + " not found");
    }
}
