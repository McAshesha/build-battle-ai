package ru.ashesha.buildBattleAI.mockbukkit;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests confirming MockBukkit is wired up correctly in this project.
 * <p>
 * These exercise the four primitives we will rely on in future test work —
 * server bootstrap, player simulation, world creation, and chat capture —
 * without loading any project code. If this class regresses we know the
 * problem is at the framework/dependency layer, not in our own services.
 */
@Tag("smoke")
class MockBukkitSmokeTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void serverIsRegisteredAsBukkitInstance() {
        assertNotNull(server);
        assertTrue(MockBukkit.isMocked());
    }

    @Test
    void addPlayerCreatesUsablePlayerMock() {
        PlayerMock alice = server.addPlayer("alice");
        assertEquals("alice", alice.getName());
        assertEquals(1, server.getOnlinePlayers().size());
        assertSame(alice, server.getPlayer("alice"));
    }

    @Test
    void setPlayersCreatesIndexedPlayers() {
        server.setPlayers(4);
        assertEquals(4, server.getOnlinePlayers().size());
        Player p0 = server.getPlayer(0);
        Player p3 = server.getPlayer(3);
        assertNotNull(p0);
        assertNotNull(p3);
        assertNotSame(p0, p3);
    }

    @Test
    void chatMessagesAreCapturedAndAssertable() {
        PlayerMock bob = server.addPlayer("bob");
        bob.sendMessage("hello from the test");
        // assertSaid pops the next queued message and matches it
        bob.assertSaid("hello from the test");
        bob.assertNoMoreSaid();
    }

    @Test
    void simpleWorldCanBeAddedAndLookedUp() {
        WorldMock arena = server.addSimpleWorld("arena");
        assertNotNull(arena);
        assertEquals("arena", arena.getName());
        World looked = server.getWorld("arena");
        assertSame(arena, looked);
    }
}
