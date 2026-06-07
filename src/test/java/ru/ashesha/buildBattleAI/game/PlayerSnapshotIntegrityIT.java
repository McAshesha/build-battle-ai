package ru.ashesha.buildBattleAI.game;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests covering GAME-04: {@link PlayerSnapshot} deep-clone integrity.
 * <p>
 * Verifies that:
 * <ol>
 *     <li>The snapshot's {@code inventoryContents} array is a distinct copy from
 *         the original — mutating the snapshot must not affect the live player.</li>
 *     <li>The off-hand field is {@code null} for 1.8 servers and non-null for
 *         1.9+ servers when the player has an off-hand item.</li>
 * </ol>
 *
 * <p>Registry boot strategy follows {@link PlayerSnapshotTest}: MockBukkit is
 * started first to satisfy the paper-api RegistryAccess SPI, then a JDK proxy
 * {@link Server} is installed into {@code Bukkit.server} so the remaining test
 * code stays lightweight (no full Bukkit server wired up).
 */
class PlayerSnapshotIntegrityIT {

    private static Object originalServer;

    /** Registry proxy cache, shared with the proxy's {@code getOrThrow} handler. */
    private static final ConcurrentHashMap<NamespacedKey, PotionEffectType> TYPE_CACHE
            = new ConcurrentHashMap<>();

    private Player player;
    private PlayerInventory inventory;
    /** Kept as a field to prevent GC — {@link Location} holds a weak world reference. */
    private World world;

    // -------------------------------------------------------------------------
    // Bootstrap / teardown
    // -------------------------------------------------------------------------

    /**
     * Boots MockBukkit to initialise the paper-api RegistryAccess SPI, then
     * replaces {@code Bukkit.server} with a JDK proxy that handles
     * {@code getRegistry()} without triggering Mockito class-loading cycles.
     */
    @BeforeAll
    static void initBukkitServer() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        originalServer = serverField.get(null);

        // Boot MockBukkit so the RegistryAccess SPI provider (in MockBukkit's
        // META-INF/services) is wired up before any Registry static init.
        MockBukkit.mock();

        Object proxyServer = Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                new ServerProxyHandler()
        );
        serverField.set(null, proxyServer);
    }

    /** Restores the original {@code Bukkit.server} and unmocks MockBukkit. */
    @AfterAll
    static void restoreBukkitServer() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, originalServer);
        MockBukkit.unmock();
    }

    /**
     * Provides a fully stubbed player with sensible defaults for every getter
     * read by {@link PlayerSnapshot#capture}.
     */
    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Location loc = new Location(world, 1.0, 64.0, 1.0, 0f, 0f);
        when(player.getLocation()).thenReturn(loc);

        inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(new ItemStack[36]);
        when(inventory.getArmorContents()).thenReturn(new ItemStack[4]);

        // Return empty collection to skip the PotionEffectType registry path.
        when(player.getActivePotionEffects()).thenReturn(Collections.emptyList());

        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getLevel()).thenReturn(0);
        when(player.getExp()).thenReturn(0.0f);
        when(player.getHealth()).thenReturn(20.0);
        when(player.getFoodLevel()).thenReturn(20);
        when(player.getSaturation()).thenReturn(5.0f);
        when(player.getAllowFlight()).thenReturn(false);
        when(player.isFlying()).thenReturn(false);
        when(player.getFireTicks()).thenReturn(0);
    }

    // -------------------------------------------------------------------------
    // GAME-04 test 1: inventory deep-clone
    // -------------------------------------------------------------------------

    /**
     * Verifies that {@link PlayerSnapshot#capture} returns an inventory array
     * that is a distinct copy of the original. Mutating a slot in the snapshot
     * must not alter the array that {@code PlayerInventory.getContents()} returned.
     */
    @Test
    void snapshotIsDeepClone() {
        // Build a 3-slot content array with stone, dirt, and a null gap.
        ItemStack stone = mock(ItemStack.class);
        ItemStack stoneClone = mock(ItemStack.class);
        when(stone.clone()).thenReturn(stoneClone);

        ItemStack dirt = mock(ItemStack.class);
        ItemStack dirtClone = mock(ItemStack.class);
        when(dirt.clone()).thenReturn(dirtClone);

        ItemStack[] original = new ItemStack[36];
        original[0] = stone;
        original[1] = dirt;
        original[2] = null; // intentional null gap

        when(inventory.getContents()).thenReturn(original);

        PlayerSnapshot snap = PlayerSnapshot.capture(player, ServerVersion.V_1_21);

        // The captured array must be a DIFFERENT array reference.
        assertNotSame(original, snap.inventoryContents(),
                "inventoryContents() must be a new array, not the original reference");

        // Mutating the snapshot must not bleed back into the original.
        snap.inventoryContents()[0] = null;
        assertSame(stone, original[0],
                "Mutating the snapshot array must not alter the original array");

        // Slot 1 must still be the cloned dirt item (our null-write only touched slot 0).
        assertSame(dirtClone, snap.inventoryContents()[1],
                "Slot 1 in snapshot must be the cloned dirt ItemStack");
        assertNull(snap.inventoryContents()[2],
                "Null slots must remain null in the snapshot");
    }

    // -------------------------------------------------------------------------
    // GAME-04 test 2: off-hand version gate
    // -------------------------------------------------------------------------

    /**
     * Verifies the off-hand version gate: on 1.8 {@code offHand()} must be
     * {@code null} (the API does not exist), and on any 1.9+ version it must
     * reflect the player's actual off-hand item.
     */
    @Test
    void offHandIsNullOnLegacyVersion() {
        ItemStack offHandItem = mock(ItemStack.class);
        ItemStack offHandClone = mock(ItemStack.class);
        when(offHandItem.clone()).thenReturn(offHandClone);
        when(inventory.getItemInOffHand()).thenReturn(offHandItem);

        // 1.8 — off-hand does not exist; field must be null regardless of what
        // getItemInOffHand() would return.
        PlayerSnapshot snap18 = PlayerSnapshot.capture(player, ServerVersion.V_1_8);
        assertNull(snap18.offHand(),
                "offHand() must be null on ServerVersion.V_1_8");
        verify(inventory, never()).getItemInOffHand();

        // 1.16.5 — off-hand exists and must be cloned.
        PlayerSnapshot snap116 = PlayerSnapshot.capture(player, ServerVersion.V_1_16_5);
        assertNotNull(snap116.offHand(),
                "offHand() must be non-null on ServerVersion.V_1_16_5 when player has an off-hand item");
        assertSame(offHandClone, snap116.offHand(),
                "offHand() must be the cloned instance returned by ItemStack.clone()");
    }

    // -------------------------------------------------------------------------
    // Proxy helpers (verbatim from PlayerSnapshotTest)
    // -------------------------------------------------------------------------

    /**
     * JDK dynamic proxy handler for {@link Server}. Returns safe defaults
     * for all methods and a registry proxy for {@code getRegistry()}.
     */
    private static class ServerProxyHandler implements InvocationHandler {

        private final Logger logger = Logger.getLogger("TestServer-IT");

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();

            if ("getRegistry".equals(name))
                return createRegistryProxy();

            if ("getLogger".equals(name))
                return logger;

            if ("getName".equals(name))
                return "TestServer";

            if ("getBukkitVersion".equals(name))
                return "1.21-TEST";

            if ("isPrimaryThread".equals(name))
                return true;

            return defaultValue(method.getReturnType());
        }
    }

    /**
     * Creates a JDK dynamic proxy for {@link Registry} that lazily produces
     * Mockito mocks of {@link PotionEffectType} from {@code get()} and
     * {@code getOrThrow()}, keyed by {@link NamespacedKey}. This allows the
     * {@code PotionEffectType} static initializer to run without a live server.
     */
    @SuppressWarnings("unchecked")
    private static Object createRegistryProxy() {
        return Proxy.newProxyInstance(
                Registry.class.getClassLoader(),
                new Class<?>[]{Registry.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();

                        if ("get".equals(name) || "getOrThrow".equals(name)) {
                            NamespacedKey key = (NamespacedKey) args[0];
                            return TYPE_CACHE.computeIfAbsent(key, k -> {
                                PotionEffectType t = mock(PotionEffectType.class);
                                when(t.getKey()).thenReturn(k);
                                return t;
                            });
                        }
                        if ("iterator".equals(name))
                            return Collections.emptyIterator();
                        if ("stream".equals(name))
                            return java.util.stream.Stream.empty();

                        return defaultValue(method.getReturnType());
                    }
                }
        );
    }

    /**
     * Returns a type-appropriate zero/false/null default for a given return type.
     */
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class)
            return false;
        if (type == int.class)
            return 0;
        if (type == long.class)
            return 0L;
        if (type == double.class)
            return 0.0;
        if (type == float.class)
            return 0.0f;
        if (type == byte.class)
            return (byte) 0;
        if (type == short.class)
            return (short) 0;
        if (type == char.class)
            return (char) 0;
        return null;
    }
}
