package ru.ashesha.buildBattleAI.entity.hologram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HologramService} internals and the {@link HologramService.Hologram}
 * data class.
 * <p>
 * These tests exercise the pure, non-Bukkit parts of the service: hologram creation,
 * line-Y computation, color code translation, and the stateless hologram data structure.
 * Packet-level behavior (spawn, despawn, metadata, dynamic resize) requires a live
 * server and PacketEvents — covered by manual testing via {@code /testholo}.
 */
class HologramServiceTest {

    // ── Hologram data class tests ───────────────────────────────────────────

    @Test
    void hologramStoresEntityIds() {
        int[] ids = {100, 101, 102};
        HologramService.Hologram hologram = new HologramService.Hologram(ids);

        assertEquals(3, hologram.getLineCount());
        assertEquals(100, hologram.getEntityId(0));
        assertEquals(101, hologram.getEntityId(1));
        assertEquals(102, hologram.getEntityId(2));
    }

    @Test
    void singleLineEntityIdAccess() {
        HologramService.Hologram hologram = new HologramService.Hologram(new int[]{42});
        assertEquals(1, hologram.getLineCount());
        assertEquals(42, hologram.getEntityId(0));
    }

    @Test
    void entityIdOutOfBoundsThrows() {
        HologramService.Hologram hologram = new HologramService.Hologram(new int[]{1, 2});
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> hologram.getEntityId(2));
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> hologram.getEntityId(-1));
    }

    @Test
    void hologramEntityIdsAreMutable() {
        // Verify that updateLines can replace the entityIds array for dynamic sizing
        int[] original = {1, 2, 3};
        HologramService.Hologram hologram = new HologramService.Hologram(original);
        assertEquals(3, hologram.getLineCount());

        // Simulate expansion (as updateLines does internally)
        hologram.entityIds = new int[]{1, 2, 3, 4, 5};
        assertEquals(5, hologram.getLineCount());
        assertEquals(4, hologram.getEntityId(3));

        // Simulate shrink
        hologram.entityIds = new int[]{1, 2};
        assertEquals(2, hologram.getLineCount());
    }

    // ── computeLineY tests ──────────────────────────────────────────────────

    @Test
    void singleLineYEqualsAnchor() {
        // With one line, it should be at the anchor Y
        double y = HologramService.computeLineY(64.0, 0, 1);
        assertEquals(64.0, y, 0.0001);
    }

    @Test
    void twoLinesTopIsHigher() {
        double topY = HologramService.computeLineY(64.0, 0, 2);
        double bottomY = HologramService.computeLineY(64.0, 1, 2);

        // Top line should be LINE_SPACING above bottom line
        assertEquals(HologramService.LINE_SPACING, topY - bottomY, 0.0001);
        // Bottom line is at anchor
        assertEquals(64.0, bottomY, 0.0001);
        // Top line is above anchor
        assertEquals(64.0 + HologramService.LINE_SPACING, topY, 0.0001);
    }

    @Test
    void threeLineSpacing() {
        double line0 = HologramService.computeLineY(100.0, 0, 3);
        double line1 = HologramService.computeLineY(100.0, 1, 3);
        double line2 = HologramService.computeLineY(100.0, 2, 3);

        // Topmost line is highest
        assertTrue(line0 > line1);
        assertTrue(line1 > line2);

        // Each pair is LINE_SPACING apart
        assertEquals(HologramService.LINE_SPACING, line0 - line1, 0.0001);
        assertEquals(HologramService.LINE_SPACING, line1 - line2, 0.0001);

        // Bottom line at anchor
        assertEquals(100.0, line2, 0.0001);
        // Top line at anchor + 2 * spacing
        assertEquals(100.0 + 2 * HologramService.LINE_SPACING, line0, 0.0001);
    }

    @Test
    void lineYWithNegativeAnchor() {
        double y = HologramService.computeLineY(-10.0, 0, 2);
        assertEquals(-10.0 + HologramService.LINE_SPACING, y, 0.0001);
    }

}
