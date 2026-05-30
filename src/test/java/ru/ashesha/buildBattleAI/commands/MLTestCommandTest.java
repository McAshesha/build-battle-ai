package ru.ashesha.buildBattleAI.commands;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.listeners.MLTestListener;
import ru.ashesha.buildBattleAI.message.api.BBAIMessageService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link MLTestCommand}.
 * <p>
 * Uses MockBukkit because the command path needs:
 * <ul>
 *   <li>A working {@code Bukkit.getItemFactory()} so
 *       {@code XMaterial.WOODEN_AXE.parseItem()} resolves;</li>
 *   <li>A real {@link org.bukkit.inventory.PlayerInventory} so
 *       {@code player.getInventory().addItem(wand)} can be asserted;</li>
 *   <li>A real {@code player.playSound} no-op so {@code SoundPalette.WELCOME.play}
 *       does not blow up.</li>
 * </ul>
 * The plugin / context / services are still Mockito mocks because the
 * production code accesses them via {@code plugin.getContext()} and the
 * services themselves are not under test here — only the command's
 * argument-parsing, gating, and inventory-manipulation behaviour is.
 */
class MLTestCommandTest {

    private ServerMock server;
    private BuildBattleAI plugin;
    private BBAIMessageService messageService;
    private MLTestCommand command;

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

        command = new MLTestCommand(plugin);
        clearSelectionState();
    }

    @AfterEach
    void tearDown() {
        clearSelectionState();
        MockBukkit.unmock();
    }

    // ── sender gating ─────────────────────────────────────────────────────

    @Test
    void consoleSenderIsRejectedOnNoArgs() throws Exception {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        invokeExecute(console, new String[]{});
        verify(console).sendMessage(eq("This command can only be used by players."));
        verifyNoInteractions(messageService);
    }

    @Test
    void consoleSenderIsRejectedOnRun() throws Exception {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        invokeExecute(console, new String[]{"run"});
        verify(console).sendMessage(eq("This command can only be used by players."));
        verifyNoInteractions(messageService);
    }

    // ── /bbaitest (no args) — wand handout ────────────────────────────────

    @Test
    void noArgsGivesWandToPlayer() throws Exception {
        SilentPlayerMock player = addSilentPlayer("alice");

        invokeExecute(player, new String[]{});

        // The wand should be in the player's inventory and isWand should pick it up.
        boolean foundWand = false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (MLTestListener.isWand(item)) {
                foundWand = true;
                break;
            }
        }
        assertTrue(foundWand, "Wand must land in the player's inventory after /bbaitest");
        verify(messageService).sendChat(eq(player), contains("Test wand granted"));
    }

    @Test
    void duplicateHandoutIsRefused() throws Exception {
        SilentPlayerMock player = addSilentPlayer("alice");
        // Pre-seed an existing wand in slot 0.
        player.getInventory().setItem(0, makeWand());

        invokeExecute(player, new String[]{});

        // Second handout should not add another wand — count remains 1.
        int wandCount = 0;
        for (ItemStack item : player.getInventory().getContents())
            if (MLTestListener.isWand(item))
                wandCount++;
        assertEquals(1, wandCount, "A second wand must not be issued");
        verify(messageService).sendChat(eq(player), contains("already have"));
    }

    @Test
    void wandHasExpectedDisplayNameAndLore() throws Exception {
        SilentPlayerMock player = addSilentPlayer("bob");
        invokeExecute(player, new String[]{});

        ItemStack wand = null;
        for (ItemStack item : player.getInventory().getContents()) {
            if (MLTestListener.isWand(item)) {
                wand = item;
                break;
            }
        }
        assertNotNull(wand);
        ItemMeta meta = wand.getItemMeta();
        assertNotNull(meta);
        assertEquals(MLTestCommand.WAND_NAME, meta.getDisplayName());
        assertNotNull(meta.getLore());
        assertEquals(1, meta.getLore().size());
        assertEquals(MLTestCommand.WAND_LORE, meta.getLore().get(0));
    }

    // ── /bbaitest run — selection gating ──────────────────────────────────

    @Test
    void runWithoutSelectionShowsError() throws Exception {
        SilentPlayerMock player = addSilentPlayer("alice");

        invokeExecute(player, new String[]{"run"});

        verify(messageService).sendChat(eq(player), contains("Both corners must be selected first"));
    }

    @Test
    void runWithIncompleteSelectionShowsError() throws Exception {
        SilentPlayerMock player = addSilentPlayer("alice");
        // Insert a one-corner selection via reflection — easiest way to get a
        // partial selection without exercising the listener.
        seedPartialSelection(player.getUniqueId(), "world");

        invokeExecute(player, new String[]{"run"});

        verify(messageService).sendChat(eq(player), contains("Both corners must be selected first"));
    }

    // ── unknown args ──────────────────────────────────────────────────────

    @Test
    void unknownSubcommandShowsUsage() throws Exception {
        SilentPlayerMock player = addSilentPlayer("alice");
        invokeExecute(player, new String[]{"banana"});
        verify(messageService).sendChat(eq(player), contains("/bbaitest"));
    }

    // ── tab completion ────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void tabCompletionIsEmpty() throws Exception {
        // "run" is intentionally hidden — verify that suggest() returns an
        // empty list regardless of what the player has typed.
        SilentPlayerMock player = addSilentPlayer("alice");
        java.util.List<String> r1 = (java.util.List<String>) invokeSuggest(player, new String[]{""});
        java.util.List<String> r2 = (java.util.List<String>) invokeSuggest(player, new String[]{"r"});
        java.util.List<String> r3 = (java.util.List<String>) invokeSuggest(player, new String[]{"run", "extra"});
        assertTrue(r1.isEmpty());
        assertTrue(r2.isEmpty());
        assertTrue(r3.isEmpty());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Builds a stamped wand identical to what {@code giveWand} produces, used
     * to pre-seed inventories in duplicate-handout tests.
     */
    private static ItemStack makeWand() {
        ItemStack wand = new ItemStack(Material.WOODEN_AXE);
        ItemMeta meta = wand.getItemMeta();
        meta.setDisplayName(MLTestCommand.WAND_NAME);
        meta.setLore(java.util.Collections.singletonList(MLTestCommand.WAND_LORE));
        wand.setItemMeta(meta);
        return wand;
    }

    /**
     * Reflectively inserts a one-corner (incomplete) selection into the
     * {@link MLTestListener#SELECTIONS} map. Lets us validate the
     * {@code !selection.isComplete()} branch of {@link MLTestCommand#runMlTest}
     * without driving the listener through PlayerInteractEvent here.
     */
    @SuppressWarnings("unchecked")
    private static void seedPartialSelection(UUID playerId, String worldName) {
        try {
            Field selectionsField = MLTestListener.class.getDeclaredField("SELECTIONS");
            selectionsField.setAccessible(true);
            Map<UUID, Object> map = (Map<UUID, Object>) selectionsField.get(null);

            Class<?> selClass = MLTestListener.Selection.class;
            java.lang.reflect.Constructor<?> ctor = selClass.getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            Object selection = ctor.newInstance(worldName);

            Method setCorner1 = selClass.getDeclaredMethod("setCorner1", int.class, int.class, int.class);
            setCorner1.setAccessible(true);
            setCorner1.invoke(selection, 0, 0, 0);
            // Corner 2 deliberately left unset.
            map.put(playerId, selection);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to seed partial selection", e);
        }
    }

    /** Wipes the static SELECTIONS map between tests (same as MLTestListenerTest). */
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

    /**
     * The PluginCommand base exposes a {@code public final boolean execute(sender,label,args)}
     * overload that wraps the protected {@code execute(sender,args)}. We use
     * the public one directly to avoid reflective access — Bukkit treats it
     * the same as the dispatched call from the command map.
     */
    private void invokeExecute(CommandSender sender, String[] args) {
        command.execute(sender, "bbaitest", args);
    }

    /** Reflectively reaches the protected {@code suggest()} method. */
    private Object invokeSuggest(CommandSender sender, String[] args) throws Exception {
        Method m = MLTestCommand.class.getDeclaredMethod("suggest", CommandSender.class, String[].class);
        m.setAccessible(true);
        return m.invoke(command, sender, args);
    }

    /**
     * Adds a {@link SilentPlayerMock} to the running server and returns it.
     * Plain {@code server.addPlayer(name)} produces a {@link PlayerMock} whose
     * {@code playSound} throws {@code UnimplementedOperationException}, which
     * marks tests as skipped — we override those signatures to keep the
     * SoundPalette calls inside {@code MLTestCommand.giveWand} silent.
     */
    private SilentPlayerMock addSilentPlayer(String name) {
        SilentPlayerMock p = new SilentPlayerMock(server, name);
        server.addPlayer(p);
        return p;
    }

    /**
     * {@link PlayerMock} that swallows all {@code playSound} overloads.
     * The production command sends a feedback sound after a successful wand
     * handout, but MockBukkit 4.50's PlayerMock has no implementation for
     * those signatures — without this override the tests would be skipped
     * with {@code UnimplementedOperationException}.
     */
    private static final class SilentPlayerMock extends PlayerMock {
        SilentPlayerMock(ServerMock server, String name) {
            super(server, name);
        }

        @Override
        public void playSound(Location loc, Sound sound, float volume, float pitch) {
            // intentionally silent
        }

        @Override
        public void playSound(Location loc, String sound, float volume, float pitch) {
            // intentionally silent
        }

        @Override
        public void playSound(Location loc, Sound sound, SoundCategory category, float volume, float pitch) {
            // intentionally silent
        }

        @Override
        public void playSound(Location loc, String sound, SoundCategory category, float volume, float pitch) {
            // intentionally silent
        }

        @Override
        public void playSound(Entity entity, Sound sound, float volume, float pitch) {
            // intentionally silent
        }

        @Override
        public void playSound(Entity entity, String sound, float volume, float pitch) {
            // intentionally silent
        }

        @Override
        public void playSound(Entity entity, Sound sound, SoundCategory category, float volume, float pitch) {
            // intentionally silent
        }

        @Override
        public void playSound(Entity entity, String sound, SoundCategory category, float volume, float pitch) {
            // intentionally silent
        }

        // Paper-api 1.19+ added a seed-bearing overload for each of the four
        // base signatures — XSound dispatches through whichever one is
        // present, so we have to silence them too.

        @Override
        public void playSound(Location loc, Sound sound, SoundCategory category, float volume, float pitch, long seed) {
            // intentionally silent
        }

        @Override
        public void playSound(Location loc, String sound, SoundCategory category, float volume, float pitch, long seed) {
            // intentionally silent
        }

        @Override
        public void playSound(Entity entity, Sound sound, SoundCategory category, float volume, float pitch, long seed) {
            // intentionally silent
        }

        @Override
        public void playSound(Entity entity, String sound, SoundCategory category, float volume, float pitch, long seed) {
            // intentionally silent
        }
    }
}
