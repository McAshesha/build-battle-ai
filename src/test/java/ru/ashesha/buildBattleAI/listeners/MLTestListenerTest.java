package ru.ashesha.buildBattleAI.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.commands.MLTestCommand;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.message.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.message.micro.ChatMicroService;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link MLTestListener}.
 * <p>
 * Covers the wand-detection helper, the two-corner click pipeline, the
 * complete-selection chat prompt, and the disconnect-time cleanup of the
 * static {@code SELECTIONS} map.
 * <p>
 * Uses MockBukkit to obtain a working {@link Bukkit#getItemFactory()} —
 * paper-api 1.21.5 made {@code ItemStack.getItemMeta()} effectively
 * un-mockable, so we need a real Bukkit-flavored runtime for the item
 * factory call inside {@link MLTestListener#isWand}. Everything else
 * (Player, Block, PlayerInteractEvent) stays Mockito-based to keep the
 * tests focused and fast.
 */
class MLTestListenerTest {

    private ServerMock server;
    private BuildBattleAI plugin;
    private BBAIMessageService messageService;
    private MLTestListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();

        plugin = mock(BuildBattleAI.class);
        PluginLogger logger = mock(PluginLogger.class);
        PluginContext context = mock(PluginContext.class);
        messageService = mock(BBAIMessageService.class);

        when(plugin.getPluginLogger()).thenReturn(logger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getMessageService()).thenReturn(messageService);

        listener = new MLTestListener(plugin);
        clearSelectionState();
    }

    @AfterEach
    void tearDown() {
        clearSelectionState();
        MockBukkit.unmock();
    }

    // ── isWand ────────────────────────────────────────────────────────────

    @Test
    void isWandReturnsFalseForNull() {
        assertFalse(MLTestListener.isWand(null));
    }

    @Test
    void isWandReturnsFalseForPlainItem() {
        // Real item, no display name.
        ItemStack item = new ItemStack(Material.STICK);
        assertFalse(MLTestListener.isWand(item));
    }

    @Test
    void isWandReturnsFalseForItemWithWrongDisplayName() {
        ItemStack item = stampedItem("Some other name");
        assertFalse(MLTestListener.isWand(item));
    }

    @Test
    void isWandReturnsTrueForCorrectlyStampedItem() {
        ItemStack item = stampedItem(MLTestCommand.WAND_NAME);
        assertTrue(MLTestListener.isWand(item));
    }

    // ── onPlayerInteract: gating ─────────────────────────────────────────

    @Test
    void interactWithoutWandIsIgnored() {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getItem()).thenReturn(null);

        listener.onPlayerInteract(event);

        verify(event, never()).setCancelled(anyBoolean());
        verifyNoInteractions(messageService);
    }

    @Test
    void airClickWithWandIsIgnored() {
        // Wand in hand but the click was air → no block to anchor selection.
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getItem()).thenReturn(stampedItem(MLTestCommand.WAND_NAME));
        when(event.getClickedBlock()).thenReturn(null);

        listener.onPlayerInteract(event);

        verify(event, never()).setCancelled(anyBoolean());
    }

    // ── onPlayerInteract: corner 1 ───────────────────────────────────────

    @Test
    void leftClickStoresCorner1AndCancelsBlockBreak() {
        UUID uuid = UUID.randomUUID();
        Player player = stubPlayer(uuid, "world");
        PlayerInteractEvent event = buildClickEvent(player, Action.LEFT_CLICK_BLOCK, 10, 64, 20);

        listener.onPlayerInteract(event);

        verify(event).setCancelled(true);
        MLTestListener.Selection sel = MLTestListener.getSelection(uuid);
        assertNotNull(sel, "Selection should be created after left-click");
        assertFalse(sel.isComplete(), "Only one corner set — selection must be incomplete");
        verify(messageService).sendChat(eq(player), contains("Corner 1 set"));
    }

    // ── onPlayerInteract: corner 2 ───────────────────────────────────────

    @Test
    void rightClickStoresCorner2AndCancels() {
        UUID uuid = UUID.randomUUID();
        Player player = stubPlayer(uuid, "world");
        PlayerInteractEvent event = buildClickEvent(player, Action.RIGHT_CLICK_BLOCK, 30, 64, 40);

        listener.onPlayerInteract(event);

        verify(event).setCancelled(true);
        MLTestListener.Selection sel = MLTestListener.getSelection(uuid);
        assertNotNull(sel);
    }

    // ── onPlayerInteract: full selection → chat prompt ───────────────────

    @Test
    void bothCornersTriggerRunChatPrompt() {
        UUID uuid = UUID.randomUUID();
        Player player = stubPlayer(uuid, "world");

        listener.onPlayerInteract(buildClickEvent(player, Action.LEFT_CLICK_BLOCK, 0, 60, 0));
        listener.onPlayerInteract(buildClickEvent(player, Action.RIGHT_CLICK_BLOCK, 10, 70, 10));

        MLTestListener.Selection sel = MLTestListener.getSelection(uuid);
        assertNotNull(sel);
        assertTrue(sel.isComplete());
        assertEquals(0, sel.minX());
        assertEquals(60, sel.minY());
        assertEquals(0, sel.minZ());
        assertEquals(10, sel.maxX());
        assertEquals(70, sel.maxY());
        assertEquals(10, sel.maxZ());

        verify(messageService).sendChat(eq(player), any(ChatMicroService.ChatMessage.class));
    }

    // ── corner ordering / min-max normalization ──────────────────────────

    @Test
    void selectionNormalizesReversedCorners() {
        UUID uuid = UUID.randomUUID();
        Player player = stubPlayer(uuid, "world");

        // Corner 1 at (10,70,10), Corner 2 at (0,60,0) — min/max swapped.
        listener.onPlayerInteract(buildClickEvent(player, Action.LEFT_CLICK_BLOCK, 10, 70, 10));
        listener.onPlayerInteract(buildClickEvent(player, Action.RIGHT_CLICK_BLOCK, 0, 60, 0));

        MLTestListener.Selection sel = MLTestListener.getSelection(uuid);
        assertEquals(0, sel.minX());
        assertEquals(60, sel.minY());
        assertEquals(0, sel.minZ());
        assertEquals(10, sel.maxX());
        assertEquals(70, sel.maxY());
        assertEquals(10, sel.maxZ());
    }

    // ── onPlayerQuit ─────────────────────────────────────────────────────

    @Test
    void quitRemovesSelection() {
        UUID uuid = UUID.randomUUID();
        Player player = stubPlayer(uuid, "world");
        listener.onPlayerInteract(buildClickEvent(player, Action.LEFT_CLICK_BLOCK, 1, 1, 1));
        assertNotNull(MLTestListener.getSelection(uuid));

        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        listener.onPlayerQuit(quit);

        assertNull(MLTestListener.getSelection(uuid),
                "Quit must wipe the player's selection to prevent map leak");
    }

    @Test
    void quitWithoutSelectionIsNoOp() {
        Player player = stubPlayer(UUID.randomUUID(), "world");
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        listener.onPlayerQuit(quit);
    }

    // ── clearSelection (public API) ──────────────────────────────────────

    @Test
    void clearSelectionRemovesEntry() {
        UUID uuid = UUID.randomUUID();
        Player player = stubPlayer(uuid, "world");
        listener.onPlayerInteract(buildClickEvent(player, Action.LEFT_CLICK_BLOCK, 1, 1, 1));
        assertNotNull(MLTestListener.getSelection(uuid));

        MLTestListener.clearSelection(uuid);
        assertNull(MLTestListener.getSelection(uuid));
    }

    @Test
    void clearSelectionOnAbsentUuidIsNoOp() {
        MLTestListener.clearSelection(UUID.randomUUID());
    }

    // ── Selection world resolution ───────────────────────────────────────

    @Test
    void selectionWorldNameIsCapturedOnFirstClick() {
        UUID uuid = UUID.randomUUID();
        // MockBukkit-resolved world name so selection.world() can actually look it up.
        server.addSimpleWorld("arena_world");
        Player player = stubPlayer(uuid, "arena_world");

        listener.onPlayerInteract(buildClickEvent(player, Action.LEFT_CLICK_BLOCK, 0, 0, 0));

        MLTestListener.Selection sel = MLTestListener.getSelection(uuid);
        assertNotNull(sel);
        assertNotNull(sel.world(), "world() should resolve via Bukkit registry");
        assertEquals("arena_world", sel.world().getName());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Creates a real {@link ItemStack} (via MockBukkit's item factory) and
     * stamps it with the given display name. Returns an item that satisfies
     * {@link MLTestListener#isWand} when the name matches.
     */
    private static ItemStack stampedItem(String displayName) {
        ItemStack item = new ItemStack(Material.WOODEN_AXE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(displayName);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Stubs a {@link Player} with a known UUID and current world name.
     * The world stub is a Mockito mock (not MockBukkit's WorldMock) — the
     * listener never reads anything off the world beyond its name.
     */
    private static Player stubPlayer(UUID uuid, String worldName) {
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn(worldName);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getWorld()).thenReturn(world);
        return player;
    }

    /**
     * Fabricates a {@link PlayerInteractEvent} that wraps a stamped wand,
     * the requested {@link Action}, and a block at the given coordinates.
     */
    private static PlayerInteractEvent buildClickEvent(Player player, Action action,
                                                       int x, int y, int z) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getItem()).thenReturn(stampedItem(MLTestCommand.WAND_NAME));
        when(event.getAction()).thenReturn(action);

        Block block = mock(Block.class);
        Location loc = new Location(player.getWorld(), x, y, z);
        when(block.getLocation()).thenReturn(loc);
        when(event.getClickedBlock()).thenReturn(block);
        return event;
    }

    /**
     * Wipes the static {@code SELECTIONS} map between tests so one test
     * cannot observe state stored by another. Production has no public
     * reset hook (selections clear organically), so reflection is the only
     * test-safe path.
     */
    @SuppressWarnings("unchecked")
    private static void clearSelectionState() {
        try {
            Field field = MLTestListener.class.getDeclaredField("SELECTIONS");
            field.setAccessible(true);
            Map<UUID, ?> map = (Map<UUID, ?>) field.get(null);
            map.clear();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot reset MLTestListener.SELECTIONS", e);
        }
    }
}
