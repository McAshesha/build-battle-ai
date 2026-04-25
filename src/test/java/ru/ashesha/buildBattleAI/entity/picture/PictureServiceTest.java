package ru.ashesha.buildBattleAI.entity.picture;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link PictureService} internals and the {@link PictureService.Picture}
 * data class.
 * <p>
 * These tests exercise the pure, non-Bukkit parts of the service: picture creation,
 * grid layout, direction helpers, and image column mirroring logic. Packet-level
 * behavior (spawn, despawn, update) requires a live server and PacketEvents —
 * covered by manual testing via {@code /picture}.
 */
class PictureServiceTest {

    // ── Picture data class tests ───────────────────────────────────────────

    @Test
    void pictureStoresDimensions() {
        PictureService.Picture picture = new PictureService.Picture(
                new int[6], new int[6], 3, 2
        );
        assertEquals(3, picture.getWidth());
        assertEquals(2, picture.getHeight());
        assertEquals(6, picture.getTileCount());
    }

    @Test
    void pictureEntityIdAccessColumnMajor() {
        // Column-major layout: index = col * height + row
        int[] entityIds = {10, 11, 20, 21, 30, 31};
        PictureService.Picture picture = new PictureService.Picture(
                entityIds, new int[6], 3, 2
        );

        // col=0, row=0 → index 0
        assertEquals(10, picture.getEntityId(0, 0));
        // col=0, row=1 → index 1
        assertEquals(11, picture.getEntityId(0, 1));
        // col=1, row=0 → index 2
        assertEquals(20, picture.getEntityId(1, 0));
        // col=1, row=1 → index 3
        assertEquals(21, picture.getEntityId(1, 1));
        // col=2, row=0 → index 4
        assertEquals(30, picture.getEntityId(2, 0));
        // col=2, row=1 → index 5
        assertEquals(31, picture.getEntityId(2, 1));
    }

    @Test
    void pictureMapIdAccess() {
        int[] mapIds = {100, 101, 200, 201};
        PictureService.Picture picture = new PictureService.Picture(
                new int[4], mapIds, 2, 2
        );

        assertEquals(100, picture.getMapId(0, 0));
        assertEquals(101, picture.getMapId(0, 1));
        assertEquals(200, picture.getMapId(1, 0));
        assertEquals(201, picture.getMapId(1, 1));
    }

    @Test
    void singleTilePicture() {
        PictureService.Picture picture = new PictureService.Picture(
                new int[]{42}, new int[]{100}, 1, 1
        );
        assertEquals(1, picture.getWidth());
        assertEquals(1, picture.getHeight());
        assertEquals(1, picture.getTileCount());
        assertEquals(42, picture.getEntityId(0, 0));
        assertEquals(100, picture.getMapId(0, 0));
    }

    @Test
    void entityIdOutOfBoundsThrows() {
        PictureService.Picture picture = new PictureService.Picture(
                new int[4], new int[4], 2, 2
        );
        // col=2 on width=2 → index = 2*2+0 = 4 → out of bounds
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> picture.getEntityId(2, 0));
        // col=1, row=2 → index = 1*2+2 = 4 → out of bounds
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> picture.getEntityId(1, 2));
    }

    // ── deltaX / deltaZ tests ──────────────────────────────────────────────

    @Test
    void deltaXForNorthSouth() {
        // NORTH and SOUTH extend along the X axis
        assertEquals(1, PictureService.deltaX(BlockFace.NORTH));
        assertEquals(1, PictureService.deltaX(BlockFace.SOUTH));
    }

    @Test
    void deltaXForEastWest() {
        // EAST and WEST do not extend along X
        assertEquals(0, PictureService.deltaX(BlockFace.EAST));
        assertEquals(0, PictureService.deltaX(BlockFace.WEST));
    }

    @Test
    void deltaZForEastWest() {
        // EAST and WEST extend along the Z axis
        assertEquals(1, PictureService.deltaZ(BlockFace.EAST));
        assertEquals(1, PictureService.deltaZ(BlockFace.WEST));
    }

    @Test
    void deltaZForNorthSouth() {
        // NORTH and SOUTH do not extend along Z
        assertEquals(0, PictureService.deltaZ(BlockFace.NORTH));
        assertEquals(0, PictureService.deltaZ(BlockFace.SOUTH));
    }

    // ── resolveImageCol tests ──────────────────────────────────────────────

    @Test
    void imageColNotMirroredForSouth() {
        // SOUTH-facing frames align map-left with grid column order
        assertEquals(0, PictureService.resolveImageCol(0, 3, BlockFace.SOUTH));
        assertEquals(1, PictureService.resolveImageCol(1, 3, BlockFace.SOUTH));
        assertEquals(2, PictureService.resolveImageCol(2, 3, BlockFace.SOUTH));
    }

    @Test
    void imageColNotMirroredForWest() {
        // WEST-facing frames align map-left with grid column order
        assertEquals(0, PictureService.resolveImageCol(0, 4, BlockFace.WEST));
        assertEquals(3, PictureService.resolveImageCol(3, 4, BlockFace.WEST));
    }

    @Test
    void imageColMirroredForNorth() {
        // NORTH-facing frames need mirrored image columns
        assertEquals(2, PictureService.resolveImageCol(0, 3, BlockFace.NORTH));
        assertEquals(1, PictureService.resolveImageCol(1, 3, BlockFace.NORTH));
        assertEquals(0, PictureService.resolveImageCol(2, 3, BlockFace.NORTH));
    }

    @Test
    void imageColMirroredForEast() {
        // EAST-facing frames need mirrored image columns
        assertEquals(3, PictureService.resolveImageCol(0, 4, BlockFace.EAST));
        assertEquals(0, PictureService.resolveImageCol(3, 4, BlockFace.EAST));
    }

    @Test
    void singleColumnMirroringIsIdentity() {
        // With width=1, mirroring has no effect (width-1-0 = 0)
        assertEquals(0, PictureService.resolveImageCol(0, 1, BlockFace.SOUTH));
        assertEquals(0, PictureService.resolveImageCol(0, 1, BlockFace.NORTH));
    }
}
