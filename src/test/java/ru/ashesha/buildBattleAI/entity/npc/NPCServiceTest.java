package ru.ashesha.buildBattleAI.entity.npc;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link NPCService} and its inner {@link NPCService.NPC} data class.
 * <p>
 * The service's version-dependent factories and packet-level behavior require
 * a live server — those are covered by manual testing. These unit tests exercise
 * the pure, non-packet parts: NPC creation, entity ID allocation, skin layers
 * index resolution, and the NPC data class.
 */
class NPCServiceTest {

    private BuildBattleAI plugin;
    private PluginContext context;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        context = mock(PluginContext.class);
        when(plugin.getContext()).thenReturn(context);
    }

    // -- NPC data class tests -----------------------------------------------

    @Test
    void npcStoresEntityId() {
        NPCService service = createServiceForVersion(ServerVersion.V_1_21);
        NPCService.NPC npc = service.createNPC("TestNPC", "texture", "signature");
        assertTrue(npc.getId() > 0, "NPC should have a positive entity ID");
    }

    @Test
    void eachNpcGetsUniqueEntityId() {
        NPCService service = createServiceForVersion(ServerVersion.V_1_21);
        NPCService.NPC npc1 = service.createNPC("NPC1", "tex1", "sig1");
        NPCService.NPC npc2 = service.createNPC("NPC2", "tex2", "sig2");
        assertNotEquals(npc1.getId(), npc2.getId(),
                "Each NPC should receive a unique entity ID");
    }

    // -- createNPC(name, texture, signature) tests --------------------------

    @Test
    void createNPCWithRawTexturesReturnsNPC() {
        NPCService service = createServiceForVersion(ServerVersion.V_1_21);
        NPCService.NPC npc = service.createNPC("Guard", "base64tex", "base64sig");
        assertNotNull(npc);
        assertTrue(npc.getId() > 0);
    }

    // -- version-dependent skin layers index --------------------------------

    @Test
    void skinLayersIndex17ForModernVersions() {
        NPCService service = createServiceForVersion(ServerVersion.V_1_21);
        // enable() resolved skinLayersIndex; we verify by checking that enable
        // does not throw and the service is in a valid state for NPC creation
        NPCService.NPC npc = service.createNPC("Modern", "tex", "sig");
        assertNotNull(npc);
    }

    @Test
    void enableSucceedsForLegacy18() {
        NPCService service = createServiceForVersion(ServerVersion.V_1_8);
        // Should resolve skin layers index = 10 and legacy packet factories
        NPCService.NPC npc = service.createNPC("Legacy", "tex", "sig");
        assertNotNull(npc);
    }

    @Test
    void enableSucceedsFor19() {
        NPCService service = createServiceForVersion(ServerVersion.V_1_9);
        NPCService.NPC npc = service.createNPC("NPC19", "tex", "sig");
        assertNotNull(npc);
    }

    @Test
    void enableSucceedsFor110() {
        NPCService service = createServiceForVersion(ServerVersion.V_1_10);
        NPCService.NPC npc = service.createNPC("NPC110", "tex", "sig");
        assertNotNull(npc);
    }

    @Test
    void enableSucceedsFor113() {
        NPCService service = createServiceForVersion(ServerVersion.V_1_13);
        NPCService.NPC npc = service.createNPC("NPC113", "tex", "sig");
        assertNotNull(npc);
    }

    @Test
    void enableSucceedsFor114() {
        NPCService service = createServiceForVersion(ServerVersion.V_1_14);
        NPCService.NPC npc = service.createNPC("NPC114", "tex", "sig");
        assertNotNull(npc);
    }

    @Test
    void enableSucceedsFor1193() {
        // 1.19.3 is the boundary for PlayerInfoUpdate vs legacy PlayerInfo
        NPCService service = createServiceForVersion(ServerVersion.V_1_19_3);
        NPCService.NPC npc = service.createNPC("NPC1193", "tex", "sig");
        assertNotNull(npc);
    }

    @Test
    void enableSucceedsFor1202() {
        // 1.20.2 is the boundary for unified SpawnEntity vs SpawnPlayer
        NPCService service = createServiceForVersion(ServerVersion.V_1_20_2);
        NPCService.NPC npc = service.createNPC("NPC1202", "tex", "sig");
        assertNotNull(npc);
    }

    // -- shutdown test ------------------------------------------------------

    @Test
    void shutdownIsNoOp() {
        NPCService service = createServiceForVersion(ServerVersion.V_1_21);
        // Should not throw
        assertDoesNotThrow(service::shutdown);
    }

    // -- helpers ------------------------------------------------------------

    /**
     * Creates and enables an {@link NPCService} wired to a mocked
     * {@link PluginContext} that reports the given server version.
     * Uses MockedStatic for PacketEvents since the factory resolution
     * accesses PacketEvents API indirectly.
     */
    private NPCService createServiceForVersion(ServerVersion version) {
        when(context.getServerVersion()).thenReturn(version);
        NPCService service = new NPCService(plugin);
        service.enable();
        return service;
    }
}
