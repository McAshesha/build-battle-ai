package ru.ashesha.buildBattleAI.listeners;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.game.api.BBAIGameManager;

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
    private BBAIGameManager gameManager;
    private GameListener listener;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        PluginLogger logger = mock(PluginLogger.class);
        PluginContext context = mock(PluginContext.class);
        gameManager = mock(BBAIGameManager.class);

        when(plugin.getPluginLogger()).thenReturn(logger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getGameManager()).thenReturn(gameManager);

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
}
