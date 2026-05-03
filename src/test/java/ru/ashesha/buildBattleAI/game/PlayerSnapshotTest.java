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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link PlayerSnapshot}.
 * <p>
 * Verifies that {@code capture()} correctly snapshots all player fields,
 * deep-clones mutable objects, and respects version-gated off-hand behavior.
 * <p>
 * A JDK dynamic proxy {@link Server} is injected into {@code Bukkit.server}
 * via reflection in {@code @BeforeAll}, <em>before</em> any Bukkit registry
 * class is loaded. This is necessary because {@link PotionEffectType}'s
 * static initializer calls {@code Registry.EFFECT.getOrThrow(key)}, which
 * in turn calls {@code Bukkit.getRegistry(PotionEffectType.class)}. Using
 * JDK proxies (instead of Mockito mocks) for both {@link Server} and
 * {@link Registry} avoids the chicken-and-egg problem where Mockito's
 * instrumentation would trigger class loading of classes that need
 * {@code Bukkit.server} to already be set.
 */
class PlayerSnapshotTest {

    private static Object originalServer;

    /**
     * Cache of mock {@link PotionEffectType} instances keyed by
     * {@link NamespacedKey}, shared between the registry proxy and tests.
     */
    private static final ConcurrentHashMap<NamespacedKey, PotionEffectType> TYPE_CACHE
            = new ConcurrentHashMap<>();

    private Player player;
    private PlayerInventory inventory;
    /** Held as a field to prevent GC — {@link Location} stores a {@link java.lang.ref.WeakReference}. */
    private World world;

    /**
     * Injects a JDK dynamic proxy {@link Server} into {@code Bukkit.server}
     * before any Bukkit registry class is loaded. The proxy's
     * {@code getRegistry()} returns another JDK proxy implementing
     * {@link Registry}, whose {@code getOrThrow()} lazily creates Mockito
     * mocks of {@link PotionEffectType} for each unique key. This allows
     * the {@code PotionEffectType} static initializer to succeed without
     * a live server.
     */
    @BeforeAll
    static void initBukkitServer() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        originalServer = serverField.get(null);

        // Build a JDK proxy for Server — must NOT use Mockito here because
        // at this point Registry.class has not loaded yet, and Mockito's
        // instrumentation of Server would trigger loading of its return types
        // (including Registry) before Bukkit.server is set.
        Object proxyServer = Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                new ServerProxyHandler()
        );

        // Install the proxy BEFORE any Bukkit class's static init runs
        serverField.set(null, proxyServer);
    }

    @AfterAll
    static void restoreBukkitServer() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, originalServer);
    }

    /**
     * Sets up a fully stubbed player with sensible defaults for every getter
     * that {@link PlayerSnapshot#capture} reads.
     */
    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Location loc = new Location(world, 10.0, 65.0, 20.0, 90f, 45f);
        when(player.getLocation()).thenReturn(loc);

        inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(new ItemStack[36]);
        when(inventory.getArmorContents()).thenReturn(new ItemStack[4]);

        when(player.getActivePotionEffects()).thenReturn(Collections.<PotionEffect>emptyList());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getMaxHealth()).thenReturn(20.0);
        when(player.getHealth()).thenReturn(20.0);
        when(player.getFoodLevel()).thenReturn(20);
        when(player.getSaturation()).thenReturn(5.0f);
        when(player.getLevel()).thenReturn(0);
        when(player.getExp()).thenReturn(0.0f);
        when(player.getAllowFlight()).thenReturn(false);
        when(player.isFlying()).thenReturn(false);
        when(player.getFireTicks()).thenReturn(0);
    }

    // -- location ----------------------------------------------------------

    @Test
    void captureSnapshotsLocation() {
        PlayerSnapshot snap = PlayerSnapshot.capture(player, ServerVersion.V_1_21);

        assertEquals("world", snap.worldName());
        assertEquals(10.0, snap.x(), 0.001);
        assertEquals(65.0, snap.y(), 0.001);
        assertEquals(20.0, snap.z(), 0.001);
        assertEquals(90f, snap.yaw(), 0.001f);
        assertEquals(45f, snap.pitch(), 0.001f);
    }

    // -- gamemode ----------------------------------------------------------

    @Test
    void captureSnapshotsGameMode() {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);

        PlayerSnapshot snap = PlayerSnapshot.capture(player, ServerVersion.V_1_21);

        assertEquals(GameMode.CREATIVE, snap.gameMode());
    }

    // -- inventory ---------------------------------------------------------

    @Test
    void captureSnapshotsInventory() {
        ItemStack item = mock(ItemStack.class);
        ItemStack cloned = mock(ItemStack.class);
        when(item.clone()).thenReturn(cloned);

        ItemStack[] contents = new ItemStack[36];
        contents[0] = item;
        when(inventory.getContents()).thenReturn(contents);

        PlayerSnapshot snap = PlayerSnapshot.capture(player, ServerVersion.V_1_21);

        // The captured array must be a different reference from the original
        assertNotSame(contents, snap.inventoryContents());
        // The item in slot 0 must be the clone, not the original
        assertSame(cloned, snap.inventoryContents()[0]);
        verify(item).clone();
    }

    // -- off-hand (1.9+) ---------------------------------------------------

    @Test
    void captureHandlesOffHandOn19() {
        ItemStack offHandItem = mock(ItemStack.class);
        ItemStack offHandClone = mock(ItemStack.class);
        when(offHandItem.clone()).thenReturn(offHandClone);
        when(inventory.getItemInOffHand()).thenReturn(offHandItem);

        PlayerSnapshot snap = PlayerSnapshot.capture(player, ServerVersion.V_1_21);

        assertSame(offHandClone, snap.offHand());
        verify(offHandItem).clone();
    }

    // -- off-hand (1.8) ----------------------------------------------------

    @Test
    void captureSkipsOffHandOn18() {
        PlayerSnapshot snap = PlayerSnapshot.capture(player, ServerVersion.V_1_8);

        assertNull(snap.offHand());
        verify(inventory, never()).getItemInOffHand();
    }

    // -- potion effects ----------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void captureClonesPotionEffects() {
        // PotionEffectType.SPEED was initialized via the registry proxy in @BeforeAll
        PotionEffectType speedType = PotionEffectType.SPEED;
        assertNotNull(speedType, "Mock registry should have provided a PotionEffectType for SPEED");

        PotionEffect speedEffect = new PotionEffect(speedType, 200, 1, false, true);
        Collection<PotionEffect> effects = Collections.singletonList(speedEffect);
        when(player.getActivePotionEffects()).thenReturn((Collection) effects);

        PlayerSnapshot snap = PlayerSnapshot.capture(player, ServerVersion.V_1_21);

        assertEquals(1, snap.potionEffects().size());
        PotionEffect captured = snap.potionEffects().get(0);
        // Must be a new object, not the same reference
        assertNotSame(speedEffect, captured);
        // But carries the same values
        assertSame(speedType, captured.getType());
        assertEquals(200, captured.getDuration());
        assertEquals(1, captured.getAmplifier());
    }

    // -- proxy helpers -----------------------------------------------------

    /**
     * JDK dynamic proxy handler for {@link Server}. Returns safe defaults
     * for all methods and a registry proxy for {@code getRegistry()}.
     */
    private static class ServerProxyHandler implements InvocationHandler {

        private final Logger logger = Logger.getLogger("TestServer");

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
     * {@code getOrThrow()}, keyed by {@link NamespacedKey}.
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
     * Returns a type-appropriate default: false for boolean, 0 for numerics,
     * null for reference types.
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
