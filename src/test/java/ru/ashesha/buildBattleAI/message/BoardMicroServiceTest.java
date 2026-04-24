package ru.ashesha.buildBattleAI.message;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginContext;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link BoardMicroService} — sidebar scoreboard creation and management
 * via PacketEvents wrappers.
 * <p>
 * Verifies packet sequences for board creation, line operations, title updates,
 * board removal, and the legacy text splitting logic for pre-1.13 servers.
 */
class BoardMicroServiceTest {

    private BuildBattleAI plugin;
    private PluginContext context;
    private Player player;
    private MockedStatic<PacketEvents> packetEventsMock;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        context = mock(PluginContext.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(plugin.getContext()).thenReturn(context);

        packetEventsMock = mockStatic(PacketEvents.class);
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
        ServerManager serverManager = mock(ServerManager.class);
        packetEventsMock.when(PacketEvents::getAPI).thenReturn(api);
        when(api.getServerManager()).thenReturn(serverManager);
        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_21);

        PacketEventsSettings settings = new PacketEventsSettings();
        settings.customResourceProvider(
                name -> BoardMicroServiceTest.class.getClassLoader().getResourceAsStream(name));
        when(api.getSettings()).thenReturn(settings);
    }

    @AfterEach
    void tearDown() {
        packetEventsMock.close();
    }

    /**
     * Creates a {@link BoardMicroService} bound to the given server version.
     */
    private BoardMicroService serviceFor(ServerVersion version) {
        when(context.getServerVersion()).thenReturn(version);
        return new BoardMicroService(plugin);
    }

    // ===== Board creation =====

    @Test
    void createBoardSendsObjectiveAndDisplayPackets() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        service.createBoard(player, "&aTest Title");

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, times(2)).sendPacket(eq(player), captor.capture());

        List<PacketWrapper> packets = captor.getAllValues();
        assertTrue(packets.get(0) instanceof WrapperPlayServerScoreboardObjective,
                "First packet should be objective creation");
        assertTrue(packets.get(1) instanceof WrapperPlayServerDisplayScoreboard,
                "Second packet should be display objective");
    }

    @Test
    void createBoardReturnsNonNullBoard() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        assertNotNull(board);
        assertEquals("Title", board.getTitle());
        assertSame(player, board.getPlayer());
    }

    @Test
    void createBoardReplacesExistingBoard() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board first = service.createBoard(player, "First");
        BoardMicroService.Board second = service.createBoard(player, "Second");

        // Old board should have been removed, new board should be active
        assertNotSame(first, second);
        assertSame(second, service.getBoard(player));
    }

    // ===== Board retrieval =====

    @Test
    void getBoardReturnsNullWhenNoBoardExists() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        assertNull(service.getBoard(player));
    }

    @Test
    void getBoardReturnsActiveBoard() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        assertSame(board, service.getBoard(player));
    }

    // ===== Board removal =====

    @Test
    void removeBoardSendsObjectiveRemovePacket() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        service.createBoard(player, "Title");
        reset(context); // clear creation packets

        service.removeBoard(player);

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, atLeastOnce()).sendPacket(eq(player), captor.capture());

        boolean hasObjectiveRemove = false;
        for (PacketWrapper<?> pkt : captor.getAllValues())
            if (pkt instanceof WrapperPlayServerScoreboardObjective)
                hasObjectiveRemove = true;
        assertTrue(hasObjectiveRemove, "Should send objective REMOVE packet");
    }

    @Test
    void removeBoardClearsFromMap() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        service.createBoard(player, "Title");
        service.removeBoard(player);
        assertNull(service.getBoard(player));
    }

    @Test
    void removeBoardSkipsPacketsWhenPlayerOffline() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.setLine(0, "Content");
        reset(context);

        // Player disconnected before shutdown
        when(player.isOnline()).thenReturn(false);
        service.removeBoard(player);

        // Internal state cleaned up, but no packets sent
        assertNull(service.getBoard(player));
        verify(context, never()).sendPacket(any(Player.class), any(PacketWrapper.class));
    }

    @Test
    void removeBoardDoesNothingWhenNoBoardExists() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        // Should not throw
        service.removeBoard(player);
        verify(context, never()).sendPacket(any(Player.class), any(PacketWrapper.class));
    }

    // ===== Line operations =====

    @Test
    void setLineSendsTeamCreateAndScorePackets() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        reset(context); // clear creation packets

        board.setLine(5, "&cHello World");

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, times(2)).sendPacket(eq(player), captor.capture());

        List<PacketWrapper> packets = captor.getAllValues();
        assertTrue(packets.get(0) instanceof WrapperPlayServerTeams,
                "First packet should be team CREATE");
        assertTrue(packets.get(1) instanceof WrapperPlayServerUpdateScore,
                "Second packet should be score CREATE_OR_UPDATE");
    }

    @Test
    void setLineUpdatesTeamWhenLineAlreadyExists() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.setLine(3, "Initial");
        reset(context);

        board.setLine(3, "Updated");

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, times(1)).sendPacket(eq(player), captor.capture());
        assertTrue(captor.getValue() instanceof WrapperPlayServerTeams,
                "Updating existing line should send team UPDATE only");
    }

    @Test
    void setLineSkipsUpdateWhenTextUnchanged() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.setLine(7, "Same text");
        reset(context);

        board.setLine(7, "Same text");

        verify(context, never()).sendPacket(any(Player.class), any(PacketWrapper.class));
    }

    @Test
    void getLineReturnsSetText() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.setLine(10, "&bTest Line");
        assertEquals("&bTest Line", board.getLine(10));
    }

    @Test
    void getLineReturnsNullForUnsetLine() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        assertNull(board.getLine(0));
    }

    // ===== removeLine =====

    @Test
    void removeLineSendsScoreRemoveAndTeamRemovePackets() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.setLine(4, "To remove");
        reset(context);

        board.removeLine(4);

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, times(2)).sendPacket(eq(player), captor.capture());

        List<PacketWrapper> packets = captor.getAllValues();
        assertTrue(packets.get(0) instanceof WrapperPlayServerUpdateScore,
                "First packet should be score REMOVE");
        assertTrue(packets.get(1) instanceof WrapperPlayServerTeams,
                "Second packet should be team REMOVE");
    }

    @Test
    void removeLineDoesNothingWhenLineNotSet() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        reset(context);

        board.removeLine(0);

        verify(context, never()).sendPacket(any(Player.class), any(PacketWrapper.class));
    }

    @Test
    void removeLineClearsLineText() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.setLine(2, "Content");
        board.removeLine(2);
        assertNull(board.getLine(2));
    }

    // ===== setLines (bulk) =====

    @Test
    void setLinesBulkSetsMultipleLines() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.setLines(Arrays.asList("Line0", "Line1", "Line2"));

        assertEquals("Line0", board.getLine(0));
        assertEquals("Line1", board.getLine(1));
        assertEquals("Line2", board.getLine(2));
        assertNull(board.getLine(3));
    }

    @Test
    void setLinesRemovesExtraLines() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.setLine(5, "Old line");

        board.setLines(Arrays.asList("Only one"));

        assertEquals("Only one", board.getLine(0));
        assertNull(board.getLine(5), "Lines beyond list size should be removed");
    }

    // ===== Title updates =====

    @Test
    void setTitleSendsObjectiveUpdatePacket() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Old Title");
        reset(context);

        board.setTitle("New Title");

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, times(1)).sendPacket(eq(player), captor.capture());
        assertTrue(captor.getValue() instanceof WrapperPlayServerScoreboardObjective);
    }

    @Test
    void setTitleSkipsWhenUnchanged() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Same");
        reset(context);

        board.setTitle("Same");

        verify(context, never()).sendPacket(any(Player.class), any(PacketWrapper.class));
    }

    @Test
    void getTitleReturnsUpdatedValue() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Initial");
        board.setTitle("Changed");
        assertEquals("Changed", board.getTitle());
    }

    // ===== Board.remove() idempotence =====

    @Test
    void boardRemoveIsIdempotent() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.setLine(0, "Content");
        reset(context);

        board.remove();
        int firstCallCount = mockingDetails(context).getInvocations().size();

        board.remove(); // second call should be no-op
        int secondCallCount = mockingDetails(context).getInvocations().size();

        assertEquals(firstCallCount, secondCallCount,
                "Second remove() should not send additional packets");
    }

    @Test
    void boardRemoveCleansUpFromServiceMap() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.remove();
        assertNull(service.getBoard(player));
    }

    // ===== Operations on removed board are no-ops =====

    @Test
    void setLineOnRemovedBoardDoesNothing() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.remove();
        reset(context);

        board.setLine(0, "Should not send");

        verify(context, never()).sendPacket(any(Player.class), any(PacketWrapper.class));
    }

    @Test
    void setTitleOnRemovedBoardDoesNothing() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.remove();
        reset(context);

        board.setTitle("Should not send");

        verify(context, never()).sendPacket(any(Player.class), any(PacketWrapper.class));
    }

    // ===== Line validation =====

    @Test
    void setLineThrowsOnNegativeIndex() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        assertThrows(IllegalArgumentException.class, () -> board.setLine(-1, "Bad"));
    }

    @Test
    void setLineThrowsOnIndexTooHigh() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        assertThrows(IllegalArgumentException.class, () -> board.setLine(15, "Bad"));
    }

    @Test
    void removeLineThrowsOnInvalidIndex() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        assertThrows(IllegalArgumentException.class, () -> board.removeLine(15));
    }

    @Test
    void getLineThrowsOnInvalidIndex() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        assertThrows(IllegalArgumentException.class, () -> board.getLine(-1));
    }

    // ===== shutdown =====

    @Test
    void shutdownRemovesAllBoards() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        Player player2 = mock(Player.class);
        when(player2.getUniqueId()).thenReturn(UUID.randomUUID());

        service.createBoard(player, "Board 1");
        service.createBoard(player2, "Board 2");

        service.shutdown();

        assertNull(service.getBoard(player));
        assertNull(service.getBoard(player2));
    }

    // ===== Board removal with active lines =====

    @Test
    void boardRemovalCleansUpActiveLines() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.setLine(0, "Line A");
        board.setLine(5, "Line B");
        board.setLine(14, "Line C");
        reset(context);

        board.remove();

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, atLeast(1)).sendPacket(eq(player), captor.capture());

        // Should have score removes + team removes for 3 active lines + objective remove
        int scoreRemoves = 0;
        int teamRemoves = 0;
        int objectiveRemoves = 0;
        for (PacketWrapper<?> pkt : captor.getAllValues()) {
            if (pkt instanceof WrapperPlayServerUpdateScore)
                scoreRemoves++;
            else if (pkt instanceof WrapperPlayServerTeams)
                teamRemoves++;
            else if (pkt instanceof WrapperPlayServerScoreboardObjective)
                objectiveRemoves++;
        }

        assertEquals(3, scoreRemoves, "Should remove 3 score entries");
        assertEquals(3, teamRemoves, "Should remove 3 teams");
        assertEquals(1, objectiveRemoves, "Should remove 1 objective");
    }

    // ===== Legacy text splitting (static utility) =====

    @Test
    void splitLegacyLineShortTextGoesToPrefix() {
        String[] parts = BoardMicroService.splitLegacyLine("Hello");
        assertEquals("Hello", parts[0]);
        assertEquals("", parts[1]);
    }

    @Test
    void splitLegacyLineExactly16CharsGoesToPrefix() {
        String text = "1234567890123456"; // 16 chars
        String[] parts = BoardMicroService.splitLegacyLine(text);
        assertEquals(text, parts[0]);
        assertEquals("", parts[1]);
    }

    @Test
    void splitLegacyLineLongTextSplitsAtBoundary() {
        String text = "12345678901234567890"; // 20 chars
        String[] parts = BoardMicroService.splitLegacyLine(text);
        assertEquals("1234567890123456", parts[0]); // first 16
        assertEquals("7890", parts[1]); // remaining 4
    }

    @Test
    void splitLegacyLineDoesNotSplitInsideColorCode() {
        // Put § at position 15 (the 16th char, index 15) — splitting at 16
        // would leave the § without its code character
        String text = "012345678901234\u00a7c_rest";
        String[] parts = BoardMicroService.splitLegacyLine(text);
        // Should split at 15 instead of 16 to avoid breaking §c
        assertEquals("012345678901234", parts[0]);
        assertTrue(parts[1].contains("\u00a7c"));
    }

    @Test
    void splitLegacyLineCarriesColorToSuffix() {
        // \u00a7c = §c (red), text beyond 16 chars
        String text = "\u00a7cRed text that is quite long";
        String[] parts = BoardMicroService.splitLegacyLine(text);
        // Suffix should start with the carried §c color code
        assertTrue(parts[1].startsWith("\u00a7c"),
                "Suffix should inherit the red color from prefix");
    }

    @Test
    void splitLegacyLineSuffixTruncatedTo16() {
        // Very long text: prefix 16 + a lot more
        String text = "1234567890123456" + "abcdefghijklmnopqrstuvwxyz"; // 42 chars
        String[] parts = BoardMicroService.splitLegacyLine(text);
        assertTrue(parts[1].length() <= 16,
                "Suffix must not exceed 16 characters, was: " + parts[1].length());
    }

    // ===== getLastColors =====

    @Test
    void getLastColorsReturnsEmptyForNoColors() {
        assertEquals("", BoardMicroService.getLastColors("plain text"));
    }

    @Test
    void getLastColorsReturnsLastColor() {
        assertEquals("\u00a7c", BoardMicroService.getLastColors("\u00a7aGreen \u00a7cRed"));
    }

    @Test
    void getLastColorsResetClearsAll() {
        assertEquals("", BoardMicroService.getLastColors("\u00a7cRed \u00a7rReset"));
    }

    @Test
    void getLastColorsIncludesFormatAfterColor() {
        assertEquals("\u00a7c\u00a7l",
                BoardMicroService.getLastColors("\u00a7c\u00a7lBold Red"));
    }

    @Test
    void getLastColorsColorResetsFormat() {
        // §l (bold) then §a (green) — the bold should be reset by the color
        assertEquals("\u00a7a",
                BoardMicroService.getLastColors("\u00a7lBold \u00a7aGreen"));
    }

    // ===== Version branching =====

    @Test
    void legacyServerDetected() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_8);
        // Just verify it can create a board without errors on legacy
        BoardMicroService.Board board = service.createBoard(player, "Legacy Board");
        assertNotNull(board);
    }

    @Test
    void modernServerDetected() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_13);
        BoardMicroService.Board board = service.createBoard(player, "Modern Board");
        assertNotNull(board);
    }

    @Test
    void setLineOnLegacyServerSendsPackets() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_8);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        reset(context);

        board.setLine(0, "&cLegacy line with color");

        // Should still send team + score packets
        verify(context, atLeast(2)).sendPacket(eq(player), any(PacketWrapper.class));
    }
}
