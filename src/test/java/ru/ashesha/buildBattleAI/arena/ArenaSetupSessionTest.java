package ru.ashesha.buildBattleAI.arena;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.arena.api.Arena;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ArenaSetupSession} and its inner
 * {@link ArenaSetupSession.PlotSetupData}.
 * <p>
 * Covers the tab-state lifecycle ({@code activePlotTab}) and the
 * picture-region geometry / face validation that drives both
 * {@link ArenaSetupSession.PlotSetupData#isComplete()} and the panel UI.
 * The session itself is a Bukkit-free POJO so no mocking is required.
 */
class ArenaSetupSessionTest {

    private static ArenaSetupSession newSession() {
        return new ArenaSetupSession(
                UUID.randomUUID(),
                "test",
                "bbai_test",
                "world",
                0.0, 64.0, 0.0,
                0f, 0f,
                false);
    }

    /** Fills a plot with all non-picture required fields. */
    private static void fillPlotBasics(ArenaSetupSession.PlotSetupData plot) {
        plot.spawn(new Arena.Position(0.5, 65, 0.5, 0f, 0f));
        plot.corner1(new int[]{0, 64, 0});
        plot.corner2(new int[]{10, 80, 10});
        plot.camera1(new Arena.Position(5, 80, -5, 0f, 0f));
        plot.camera2(new Arena.Position(5, 80, -10, 0f, 0f));
        plot.camera3(new Arena.Position(5, 80, -15, 0f, 0f));
    }

    /** Fills a plot with a valid 2×2 picture region in XY-plane (NORTH face). */
    private static void fillPictureValid(ArenaSetupSession.PlotSetupData plot) {
        plot.pictureCorner1(new int[]{10, 80, 20});
        plot.pictureCorner2(new int[]{11, 81, 20});
        plot.pictureFace(BlockFace.NORTH);
    }

    // ── activePlotTab ───────────────────────────────────────────────────

    @Test
    void activeTabStartsNull() {
        ArenaSetupSession session = newSession();
        assertNull(session.activePlotTab());
    }

    @Test
    void activeTabIsSettable() {
        ArenaSetupSession session = newSession();
        session.activePlotTab(3);
        assertEquals(3, session.activePlotTab());
    }

    // ── isComplete ──────────────────────────────────────────────────────

    @Test
    void isCompleteRequiresMaxPlayersAndLobby() {
        ArenaSetupSession session = newSession();
        assertFalse(session.isComplete());

        session.maxPlayers(2);
        assertFalse(session.isComplete());

        session.lobby(new Arena.Position(0.5, 65, 0.5, 0f, 0f));
        // Still incomplete — plots are empty.
        assertFalse(session.isComplete());
    }

    @Test
    void isCompleteRequiresPicture() {
        ArenaSetupSession session = newSession();
        session.maxPlayers(2);
        session.lobby(new Arena.Position(0.5, 65, 0.5, 0f, 0f));
        for (int i = 1; i <= 2; i++)
            fillPlotBasics(session.getOrCreatePlot(i));
        // Plots have all non-picture fields but no picture data — still incomplete.
        assertFalse(session.isComplete());

        for (int i = 1; i <= 2; i++)
            fillPictureValid(session.getOrCreatePlot(i));
        assertTrue(session.isComplete());
    }

    // ── picture geometry status ─────────────────────────────────────────

    @Test
    void geometryMissingWhenCornersUnset() {
        ArenaSetupSession.PlotSetupData plot = new ArenaSetupSession.PlotSetupData();
        assertEquals(ArenaSetupSession.PictureGeometry.MISSING,
                plot.pictureGeometryStatus());
    }

    @Test
    void geometryValidForOneByOne() {
        ArenaSetupSession.PlotSetupData plot = new ArenaSetupSession.PlotSetupData();
        plot.pictureCorner1(new int[]{5, 64, 10});
        plot.pictureCorner2(new int[]{5, 64, 10});
        assertEquals(ArenaSetupSession.PictureGeometry.VALID,
                plot.pictureGeometryStatus());
        assertTrue(plot.isPictureOneByOne());
        assertFalse(plot.isPictureXYPlane());
        assertFalse(plot.isPictureYZPlane());
    }

    @Test
    void geometryValidForTwoByTwoXYPlane() {
        ArenaSetupSession.PlotSetupData plot = new ArenaSetupSession.PlotSetupData();
        plot.pictureCorner1(new int[]{5, 64, 10});
        plot.pictureCorner2(new int[]{6, 65, 10});
        assertEquals(ArenaSetupSession.PictureGeometry.VALID,
                plot.pictureGeometryStatus());
        assertTrue(plot.isPictureXYPlane());
        assertFalse(plot.isPictureYZPlane());
        assertFalse(plot.isPictureOneByOne());
    }

    @Test
    void geometryValidForTwoByTwoYZPlane() {
        ArenaSetupSession.PlotSetupData plot = new ArenaSetupSession.PlotSetupData();
        plot.pictureCorner1(new int[]{5, 64, 10});
        plot.pictureCorner2(new int[]{5, 65, 11});
        assertEquals(ArenaSetupSession.PictureGeometry.VALID,
                plot.pictureGeometryStatus());
        assertTrue(plot.isPictureYZPlane());
        assertFalse(plot.isPictureXYPlane());
    }

    @Test
    void geometryRejectsNonCoplanarCorners() {
        ArenaSetupSession.PlotSetupData plot = new ArenaSetupSession.PlotSetupData();
        plot.pictureCorner1(new int[]{5, 64, 10});
        plot.pictureCorner2(new int[]{6, 65, 11});
        assertEquals(ArenaSetupSession.PictureGeometry.NOT_COPLANAR,
                plot.pictureGeometryStatus());
    }

    @Test
    void geometryRejectsOversizedRegion() {
        ArenaSetupSession.PlotSetupData plot = new ArenaSetupSession.PlotSetupData();
        plot.pictureCorner1(new int[]{5, 64, 10});
        // 1×3 vertical strip in YZ-plane — coplanar but wrong size.
        plot.pictureCorner2(new int[]{5, 66, 10});
        assertEquals(ArenaSetupSession.PictureGeometry.INVALID_SIZE,
                plot.pictureGeometryStatus());
    }

    @Test
    void geometryRejectsTallTwoByThreeRegion() {
        ArenaSetupSession.PlotSetupData plot = new ArenaSetupSession.PlotSetupData();
        plot.pictureCorner1(new int[]{5, 64, 10});
        // 2 wide × 3 tall in XY-plane — coplanar but not 1×1/2×2.
        plot.pictureCorner2(new int[]{6, 66, 10});
        assertEquals(ArenaSetupSession.PictureGeometry.INVALID_SIZE,
                plot.pictureGeometryStatus());
    }

    // ── isFaceAllowed ───────────────────────────────────────────────────

    @Test
    void facesAllowedForOneByOne() {
        ArenaSetupSession.PlotSetupData plot = new ArenaSetupSession.PlotSetupData();
        plot.pictureCorner1(new int[]{5, 64, 10});
        plot.pictureCorner2(new int[]{5, 64, 10});
        assertTrue(plot.isFaceAllowed(BlockFace.NORTH));
        assertTrue(plot.isFaceAllowed(BlockFace.SOUTH));
        assertTrue(plot.isFaceAllowed(BlockFace.EAST));
        assertTrue(plot.isFaceAllowed(BlockFace.WEST));
        assertFalse(plot.isFaceAllowed(BlockFace.UP));
        assertFalse(plot.isFaceAllowed(null));
    }

    @Test
    void facesNarrowedForXYPlane() {
        ArenaSetupSession.PlotSetupData plot = new ArenaSetupSession.PlotSetupData();
        plot.pictureCorner1(new int[]{5, 64, 10});
        plot.pictureCorner2(new int[]{6, 65, 10});
        assertTrue(plot.isFaceAllowed(BlockFace.NORTH));
        assertTrue(plot.isFaceAllowed(BlockFace.SOUTH));
        assertFalse(plot.isFaceAllowed(BlockFace.EAST));
        assertFalse(plot.isFaceAllowed(BlockFace.WEST));
    }

    @Test
    void facesNarrowedForYZPlane() {
        ArenaSetupSession.PlotSetupData plot = new ArenaSetupSession.PlotSetupData();
        plot.pictureCorner1(new int[]{5, 64, 10});
        plot.pictureCorner2(new int[]{5, 65, 11});
        assertTrue(plot.isFaceAllowed(BlockFace.EAST));
        assertTrue(plot.isFaceAllowed(BlockFace.WEST));
        assertFalse(plot.isFaceAllowed(BlockFace.NORTH));
        assertFalse(plot.isFaceAllowed(BlockFace.SOUTH));
    }

    @Test
    void invalidGeometryDisallowsAllFaces() {
        ArenaSetupSession.PlotSetupData plot = new ArenaSetupSession.PlotSetupData();
        plot.pictureCorner1(new int[]{5, 64, 10});
        plot.pictureCorner2(new int[]{6, 65, 11}); // not coplanar
        assertFalse(plot.isFaceAllowed(BlockFace.NORTH));
        assertFalse(plot.isFaceAllowed(BlockFace.SOUTH));
        assertFalse(plot.isFaceAllowed(BlockFace.EAST));
        assertFalse(plot.isFaceAllowed(BlockFace.WEST));
    }

    // ── PlotData.isComplete with picture ───────────────────────────────

    @Test
    void plotCompleteOnlyWithValidPictureAndMatchingFace() {
        ArenaSetupSession.PlotSetupData plot = new ArenaSetupSession.PlotSetupData();
        fillPlotBasics(plot);

        // No picture yet → incomplete.
        assertFalse(plot.isComplete());

        // Geometry valid but face mismatched → still incomplete.
        plot.pictureCorner1(new int[]{10, 80, 20});
        plot.pictureCorner2(new int[]{11, 81, 20});
        plot.pictureFace(BlockFace.EAST); // wrong plane
        assertFalse(plot.isComplete());

        // Correct face → complete.
        plot.pictureFace(BlockFace.NORTH);
        assertTrue(plot.isComplete());
    }

    @Test
    void plotIncompleteWhenPictureSizeBroken() {
        ArenaSetupSession.PlotSetupData plot = new ArenaSetupSession.PlotSetupData();
        fillPlotBasics(plot);
        plot.pictureCorner1(new int[]{10, 80, 20});
        plot.pictureCorner2(new int[]{12, 81, 20}); // 3-wide
        plot.pictureFace(BlockFace.NORTH);
        assertFalse(plot.isComplete());
    }

    // ── trimPlotsAbove ──────────────────────────────────────────────────

    @Test
    void trimPlotsAboveDropsHigherIndices() {
        ArenaSetupSession session = newSession();
        session.maxPlayers(4);
        for (int i = 1; i <= 4; i++)
            session.getOrCreatePlot(i);
        assertEquals(4, session.plots().size());

        session.trimPlotsAbove(2);
        assertEquals(2, session.plots().size());
        assertNotNull(session.plots().get(1));
        assertNotNull(session.plots().get(2));
        assertNull(session.plots().get(3));
        assertNull(session.plots().get(4));
    }
}
