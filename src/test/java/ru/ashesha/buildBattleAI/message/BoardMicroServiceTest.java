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
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link BoardMicroService} — stateless sidebar scoreboard creation
 * and management via PacketEvents wrappers.
 * <p>
 * Verifies packet sequences for board creation, line operations, title updates,
 * board removal, collection overloads, and the legacy text splitting logic
 * for pre-1.13 servers.
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
        assertInstanceOf(WrapperPlayServerScoreboardObjective.class, packets.get(0), "First packet should be objective creation");
        assertInstanceOf(WrapperPlayServerDisplayScoreboard.class, packets.get(1), "Second packet should be display objective");
    }

    @Test
    void createBoardReturnsNonNullBoard() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        assertNotNull(board);
    }

    @Test
    void createBoardCollectionSendsToAllPlayers() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        Player player2 = mock(Player.class);

        service.createBoard(Arrays.asList(player, player2), "&aTitle");

        // Each player should receive objective + display = 2 packets each
        verify(context, times(2)).sendPacket(eq(player), any(PacketWrapper.class));
        verify(context, times(2)).sendPacket(eq(player2), any(PacketWrapper.class));
    }

    // ===== Line operations =====

    @Test
    void setLineSendsTeamCreateAndScorePackets() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        reset(context);

        board.setLine(player, 5, "&cHello World");

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, times(2)).sendPacket(eq(player), captor.capture());

        List<PacketWrapper> packets = captor.getAllValues();
        assertInstanceOf(WrapperPlayServerTeams.class, packets.get(0), "First packet should be team CREATE");
        assertInstanceOf(WrapperPlayServerUpdateScore.class, packets.get(1), "Second packet should be score CREATE_OR_UPDATE");
    }

    @Test
    void setLineUpdatesTeamWhenLineAlreadyExists() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.setLine(player, 3, "Initial");
        reset(context);

        board.setLine(player, 3, "Updated");

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, times(1)).sendPacket(eq(player), captor.capture());
        assertInstanceOf(WrapperPlayServerTeams.class, captor.getValue(), "Updating existing line should send team UPDATE only");
    }

    @Test
    void setLineSendsPacketEvenWhenTextIsTheSame() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.setLine(player, 7, "Same text");
        reset(context);

        // No equality check — packet is always sent, caller's responsibility
        board.setLine(player, 7, "Same text");

        verify(context, times(1)).sendPacket(eq(player), any(PacketWrapper.class));
    }

    @Test
    void setLineCollectionSendsToAllPlayers() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        Player player2 = mock(Player.class);
        List<Player> players = Arrays.asList(player, player2);
        BoardMicroService.Board board = service.createBoard(players, "Title");
        reset(context);

        board.setLine(players, 0, "&aTest");

        // New line: team CREATE + score = 2 packets per player
        verify(context, times(2)).sendPacket(eq(player), any(PacketWrapper.class));
        verify(context, times(2)).sendPacket(eq(player2), any(PacketWrapper.class));
    }

    @Test
    void setLineCollectionSendsUpdateWhenLineActive() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        Player player2 = mock(Player.class);
        List<Player> players = Arrays.asList(player, player2);
        BoardMicroService.Board board = service.createBoard(players, "Title");
        board.setLine(players, 0, "Initial");
        reset(context);

        board.setLine(players, 0, "Updated");

        // Existing line: team UPDATE only = 1 packet per player
        verify(context, times(1)).sendPacket(eq(player), any(PacketWrapper.class));
        verify(context, times(1)).sendPacket(eq(player2), any(PacketWrapper.class));
    }

    // ===== removeLine =====

    @Test
    void removeLineSendsScoreRemoveAndTeamRemovePackets() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.setLine(player, 4, "To remove");
        reset(context);

        board.removeLine(player, 4);

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, times(2)).sendPacket(eq(player), captor.capture());

        List<PacketWrapper> packets = captor.getAllValues();
        assertInstanceOf(WrapperPlayServerUpdateScore.class, packets.get(0), "First packet should be score REMOVE");
        assertInstanceOf(WrapperPlayServerTeams.class, packets.get(1), "Second packet should be team REMOVE");
    }

    @Test
    void removeLineDoesNothingWhenLineNotSet() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        reset(context);

        board.removeLine(player, 0);

        verify(context, never()).sendPacket(any(Player.class), any(PacketWrapper.class));
    }

    @Test
    void removeLineCollectionSendsToAllPlayers() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        Player player2 = mock(Player.class);
        List<Player> players = Arrays.asList(player, player2);
        BoardMicroService.Board board = service.createBoard(players, "Title");
        board.setLine(players, 2, "Content");
        reset(context);

        board.removeLine(players, 2);

        // scoreRemove + teamRemove = 2 packets per player
        verify(context, times(2)).sendPacket(eq(player), any(PacketWrapper.class));
        verify(context, times(2)).sendPacket(eq(player2), any(PacketWrapper.class));
    }

    // ===== setLines (bulk) =====

    @Test
    void setLinesBulkCreatesMultipleLines() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        reset(context);

        board.setLines(player, Arrays.asList("Line0", "Line1", "Line2"));

        // 3 new lines × (teamCreate + score) = 6 packets
        // 12 inactive lines × 0 = 0 packets (removeLine on inactive is no-op)
        verify(context, times(6)).sendPacket(eq(player), any(PacketWrapper.class));
    }

    @Test
    void setLinesBulkRemovesExtraLines() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.setLine(player, 5, "Old line");
        reset(context);

        board.setLines(player, Collections.singletonList("Only one"));

        // Line 0: new → teamCreate + score = 2 packets
        // Lines 1-4, 6-14: inactive → no packets
        // Line 5: active → scoreRemove + teamRemove = 2 packets
        // Total: 4 packets
        verify(context, times(4)).sendPacket(eq(player), any(PacketWrapper.class));
    }

    @Test
    void setLinesCollectionSendsToAllPlayers() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        Player player2 = mock(Player.class);
        List<Player> players = Arrays.asList(player, player2);
        BoardMicroService.Board board = service.createBoard(players, "Title");
        reset(context);

        board.setLines(players, Arrays.asList("A", "B"));

        // 2 new lines × (teamCreate + score) = 4 packets per player
        verify(context, times(4)).sendPacket(eq(player), any(PacketWrapper.class));
        verify(context, times(4)).sendPacket(eq(player2), any(PacketWrapper.class));
    }

    // ===== Title updates =====

    @Test
    void setTitleSendsObjectiveUpdatePacket() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Old Title");
        reset(context);

        board.setTitle(player, "New Title");

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, times(1)).sendPacket(eq(player), captor.capture());
        assertInstanceOf(WrapperPlayServerScoreboardObjective.class, captor.getValue());
    }

    @Test
    void setTitleSendsPacketEvenWhenUnchanged() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Same");
        reset(context);

        // No equality check — always sends
        board.setTitle(player, "Same");

        verify(context, times(1)).sendPacket(eq(player), any(PacketWrapper.class));
    }

    @Test
    void setTitleCollectionSendsToAllPlayers() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        Player player2 = mock(Player.class);
        List<Player> players = Arrays.asList(player, player2);
        BoardMicroService.Board board = service.createBoard(players, "Title");
        reset(context);

        board.setTitle(players, "New Title");

        verify(context, times(1)).sendPacket(eq(player), any(PacketWrapper.class));
        verify(context, times(1)).sendPacket(eq(player2), any(PacketWrapper.class));
    }

    // ===== Board removal =====

    @Test
    void boardRemovalCleansUpActiveLines() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.setLine(player, 0, "Line A");
        board.setLine(player, 5, "Line B");
        board.setLine(player, 14, "Line C");
        reset(context);

        board.remove(player);

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, atLeast(1)).sendPacket(eq(player), captor.capture());

        // Should have score removes + team removes for 3 active lines + objective remove
        int scoreRemoves = 0;
        int teamRemoves = 0;
        int objectiveRemoves = 0;
        for (PacketWrapper<?> pkt : captor.getAllValues())
            if (pkt instanceof WrapperPlayServerUpdateScore)
                scoreRemoves++;
            else if (pkt instanceof WrapperPlayServerTeams)
                teamRemoves++;
            else if (pkt instanceof WrapperPlayServerScoreboardObjective)
                objectiveRemoves++;

        assertEquals(3, scoreRemoves, "Should remove 3 score entries");
        assertEquals(3, teamRemoves, "Should remove 3 teams");
        assertEquals(1, objectiveRemoves, "Should remove 1 objective");
    }

    @Test
    void removeCollectionSendsToAllPlayers() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        Player player2 = mock(Player.class);
        List<Player> players = Arrays.asList(player, player2);
        BoardMicroService.Board board = service.createBoard(players, "Title");
        board.setLine(players, 0, "Content");
        reset(context);

        board.remove(players);

        // 1 active line: scoreRemove + teamRemove + objectiveRemove = 3 packets per player
        verify(context, times(3)).sendPacket(eq(player), any(PacketWrapper.class));
        verify(context, times(3)).sendPacket(eq(player2), any(PacketWrapper.class));
    }

    @Test
    void setLineAfterRemoveResetsActiveState() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        board.setLine(player, 3, "Before");
        board.remove(player);
        reset(context);

        // After remove, activeLines should be cleared — setLine sends CREATE, not UPDATE
        board.setLine(player, 3, "After");

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, times(2)).sendPacket(eq(player), captor.capture());

        List<PacketWrapper> packets = captor.getAllValues();
        assertInstanceOf(WrapperPlayServerTeams.class, packets.get(0), "Should send team CREATE (not UPDATE) after remove");
        assertInstanceOf(WrapperPlayServerUpdateScore.class, packets.get(1), "Should send score packet for new line");
    }

    @Test
    void removeWithNoActiveLinesStillSendsObjectiveRemove() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        reset(context);

        // No lines set — remove should still send objective REMOVE
        board.remove(player);

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, times(1)).sendPacket(eq(player), captor.capture());
        assertInstanceOf(WrapperPlayServerScoreboardObjective.class, captor.getValue());
    }

    // ===== Line validation =====

    @Test
    void setLineThrowsOnNegativeIndex() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        assertThrows(IllegalArgumentException.class, () -> board.setLine(player, -1, "Bad"));
    }

    @Test
    void setLineThrowsOnIndexTooHigh() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        assertThrows(IllegalArgumentException.class, () -> board.setLine(player, 15, "Bad"));
    }

    @Test
    void removeLineThrowsOnInvalidIndex() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_21);
        BoardMicroService.Board board = service.createBoard(player, "Title");
        assertThrows(IllegalArgumentException.class, () -> board.removeLine(player, 15));
    }

    // ===== Version branching =====

    @Test
    void legacyServerDetected() {
        BoardMicroService service = serviceFor(ServerVersion.V_1_8);
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

        board.setLine(player, 0, "&cLegacy line with color");

        // Should still send team + score packets
        verify(context, atLeast(2)).sendPacket(eq(player), any(PacketWrapper.class));
    }

}
