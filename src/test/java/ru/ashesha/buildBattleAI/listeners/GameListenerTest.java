package ru.ashesha.buildBattleAI.listeners;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.ArenaManager;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.game.ArenaState;
import ru.ashesha.buildBattleAI.game.GameManager;

import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * Tests for {@link GameListener}.
 * <p>
 * Covers event cancellation for players in game sessions, disconnect cleanup,
 * and explosion suppression in arena worlds. Block place/break tests are
 * omitted because they require deep arena/plot mocking that is better
 * validated on a live server.
 */
class GameListenerTest {

    private BuildBattleAI plugin;
    private GameManager gameManager;
    private ArenaManager arenaManager;
    private GameListener listener;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        PluginLogger logger = mock(PluginLogger.class);
        PluginContext context = mock(PluginContext.class);
        // Mock the concrete GameManager so the listener's (GameManager) cast succeeds.
        gameManager = mock(GameManager.class);
        arenaManager = mock(ArenaManager.class);

        when(plugin.getPluginLogger()).thenReturn(logger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getGameManager()).thenReturn(gameManager);
        when(context.getArenaManager()).thenReturn(arenaManager);

        listener = new GameListener(plugin);
    }

    // -- entity damage -----------------------------------------------------

    @Test
    void entityDamageIsCancelledForPlayerInGame() {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(gameManager.isInGame(uuid)).thenReturn(true);

        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(player);

        listener.onEntityDamage(event);

        verify(event).setCancelled(true);
    }

    @Test
    void entityDamageIsNotCancelledForPlayerNotInGame() {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(gameManager.isInGame(uuid)).thenReturn(false);

        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(player);

        listener.onEntityDamage(event);

        verify(event, never()).setCancelled(anyBoolean());
    }

    // -- food level change -------------------------------------------------

    @Test
    void foodLevelChangeIsCancelledForPlayerInGame() {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(gameManager.isInGame(uuid)).thenReturn(true);

        FoodLevelChangeEvent event = mock(FoodLevelChangeEvent.class);
        when(event.getEntity()).thenReturn(player);

        listener.onFoodLevelChange(event);

        verify(event).setCancelled(true);
    }

    // -- item drop ---------------------------------------------------------

    @Test
    void itemDropIsCancelledForPlayerInGame() {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(gameManager.isInGame(uuid)).thenReturn(true);

        PlayerDropItemEvent event = mock(PlayerDropItemEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerDropItem(event);

        verify(event).setCancelled(true);
    }

    // -- player quit -------------------------------------------------------

    @Test
    void playerQuitCallsLeaveArena() {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(gameManager.isInGame(uuid)).thenReturn(true);

        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerQuit(event);

        verify(gameManager).leaveArena(player);
    }

    // -- block explode -----------------------------------------------------

    @Test
    void blockExplodeIsCancelledInArenaWorld() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("bbai_arena1");

        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);

        BlockExplodeEvent event = mock(BlockExplodeEvent.class);
        when(event.getBlock()).thenReturn(block);

        listener.onBlockExplode(event);

        verify(event).setCancelled(true);
    }

    @Test
    void blockExplodeIsNotCancelledInNormalWorld() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("survival_world");

        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);

        BlockExplodeEvent event = mock(BlockExplodeEvent.class);
        when(event.getBlock()).thenReturn(block);

        listener.onBlockExplode(event);

        verify(event, never()).setCancelled(anyBoolean());
    }

    // -- entity explode ----------------------------------------------------

    @Test
    void entityExplodeIsCancelledInArenaWorld() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("bbai_arena2");

        Location location = new Location(world, 0, 64, 0);

        EntityExplodeEvent event = mock(EntityExplodeEvent.class);
        when(event.getLocation()).thenReturn(location);

        listener.onEntityExplode(event);

        verify(event).setCancelled(true);
    }

    // -- block place / break mirror wiring ---------------------------------

    @Test
    void onBlockPlaceUpdatesMirrorForSingleBlock() {
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(gameManager.isInGame(uuid)).thenReturn(true);
        when(gameManager.getPlayerArena(uuid)).thenReturn("test");
        when(gameManager.getArenaState("test")).thenReturn(ArenaState.PLAYING);
        when(gameManager.getPlayerPlotIndex(uuid, "test")).thenReturn(0);

        Arena arena = stubArenaWithSinglePlot();
        when(arenaManager.getArena("test")).thenReturn(arena);

        Block placed = mock(Block.class);
        when(placed.getX()).thenReturn(1);
        when(placed.getY()).thenReturn(64);
        when(placed.getZ()).thenReturn(1);
        Location loc = new Location(null, 1, 64, 1);
        when(placed.getLocation()).thenReturn(loc);

        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(placed);
        when(event.getBlockPlaced()).thenReturn(placed);

        listener.onBlockPlace(event);

        verify(gameManager).applyMirrorPlace(uuid, "test", placed);
        verify(event, never()).setCancelled(true);
    }

    @Test
    void onBlockPlaceIteratesMultiPlaceReplacedStates() {
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(gameManager.isInGame(uuid)).thenReturn(true);
        when(gameManager.getPlayerArena(uuid)).thenReturn("test");
        when(gameManager.getArenaState("test")).thenReturn(ArenaState.PLAYING);
        when(gameManager.getPlayerPlotIndex(uuid, "test")).thenReturn(0);

        Arena arena = stubArenaWithSinglePlot();
        when(arenaManager.getArena("test")).thenReturn(arena);

        // The "click target" block — used for the zone check.
        Block clickBlock = mock(Block.class);
        when(clickBlock.getX()).thenReturn(1);
        when(clickBlock.getY()).thenReturn(64);
        when(clickBlock.getZ()).thenReturn(1);
        when(clickBlock.getLocation()).thenReturn(new Location(null, 1, 64, 1));

        // Two replaced block states — door halves.
        Block bottomHalf = mock(Block.class);
        Block topHalf = mock(Block.class);
        BlockState bottomState = mock(BlockState.class);
        BlockState topState = mock(BlockState.class);
        when(bottomState.getBlock()).thenReturn(bottomHalf);
        when(topState.getBlock()).thenReturn(topHalf);

        BlockMultiPlaceEvent event = mock(BlockMultiPlaceEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(clickBlock);
        when(event.getBlockPlaced()).thenReturn(clickBlock);
        when(event.getReplacedBlockStates())
                .thenReturn(java.util.Arrays.asList(bottomState, topState));

        listener.onBlockPlace(event);

        verify(gameManager).applyMirrorPlace(uuid, "test", bottomHalf);
        verify(gameManager).applyMirrorPlace(uuid, "test", topHalf);
        // Single-block path NOT used.
        verify(gameManager, never()).applyMirrorPlace(uuid, "test", clickBlock);
    }

    @Test
    void onBlockBreakUpdatesMirror() {
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(gameManager.isInGame(uuid)).thenReturn(true);
        when(gameManager.getPlayerArena(uuid)).thenReturn("test");
        when(gameManager.getArenaState("test")).thenReturn(ArenaState.PLAYING);
        when(gameManager.getPlayerPlotIndex(uuid, "test")).thenReturn(0);

        Arena arena = stubArenaWithSinglePlot();
        when(arenaManager.getArena("test")).thenReturn(arena);

        Block broken = mock(Block.class);
        when(broken.getX()).thenReturn(2);
        when(broken.getY()).thenReturn(65);
        when(broken.getZ()).thenReturn(2);
        when(broken.getLocation()).thenReturn(new Location(null, 2, 65, 2));

        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(broken);

        listener.onBlockBreak(event);

        verify(gameManager).applyMirrorBreak(uuid, "test", broken);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Builds a minimal arena with a single plot whose zone covers
     * {@code (0,60,0)–(7,67,7)} so test block coordinates fall inside it.
     */
    private Arena stubArenaWithSinglePlot() {
        Arena.Position spawn = new Arena.Position(0.5, 64.5, 0.5, 0f, 0f);
        Arena.Position cam = new Arena.Position(0.5, 70.5, 0.5, 0f, 0f);
        Arena.PictureRegion picture = new Arena.PictureRegion(
                0, 64, 0, 0, 64, 0, BlockFace.NORTH);
        Arena.PlotData plot = new Arena.PlotData(spawn,
                0, 60, 0, 7, 67, 7,
                java.util.Arrays.asList(cam, cam, cam),
                picture);
        return new Arena("test", "bbai_test", 2, true,
                spawn, null, 2, 60, 120, 5,
                java.util.Collections.singletonList(plot));
    }
}
