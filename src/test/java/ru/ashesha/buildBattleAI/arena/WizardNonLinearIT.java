package ru.ashesha.buildBattleAI.arena;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.arena.api.Arena;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for risk <b>ARENA-03</b>: the non-linear setup wizard.
 * <p>
 * Invariant: {@link ArenaSetupSession#isComplete()} must return the correct
 * result regardless of the order in which the admin fills wizard tabs. The
 * panel exposes ALL settings at once (lobby, player count, per-plot fields)
 * and the admin can click them in any sequence. These tests prove that
 * {@code isComplete()} tracks truth across every out-of-order combination
 * relevant to real usage.
 * <p>
 * The class is Bukkit-free (pure POJO arithmetic), so no MockBukkit is
 * required. Integration tier is used because the risk ARENA-03 spans the
 * interplay between the session-level {@code isComplete()} and the
 * plot-level {@code isComplete()}, which involves two cooperating objects —
 * the cheapest tier that catches a regression in either direction.
 */
@Tag("integration")
@DisplayName("ARENA-03 — Non-linear wizard completeness")
class WizardNonLinearIT {

    // ── shared factory ─────────────────────────────────────────────────────

    /**
     * Creates a fresh {@link ArenaSetupSession} with no settings filled.
     * Identical to the helper used by the unit-level {@code ArenaSetupSessionTest}.
     */
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

    /**
     * Fills a plot with a valid 2×2 picture region in the XY-plane
     * (NORTH face — correct for that plane).
     */
    private static void fillPictureValid(ArenaSetupSession.PlotSetupData plot) {
        plot.pictureCorner1(new int[]{10, 80, 20});
        plot.pictureCorner2(new int[]{11, 81, 20});
        plot.pictureFace(BlockFace.NORTH);
    }

    /** Fully configures a single plot (basics + picture). */
    private static void fillPlotComplete(ArenaSetupSession.PlotSetupData plot) {
        fillPlotBasics(plot);
        fillPictureValid(plot);
    }

    // ── scenario 1 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Scenario 1 — plots filled in reverse / scrambled order")
    class OutOfOrderPlotFill {

        /**
         * With maxPlayers=4, the admin fills plots 3, 1, 4, 2 in that order.
         * isComplete() must be false until the last plot is finished.
         */
        @Test
        @DisplayName("outOfOrderTabsCompleteCorrectly")
        void outOfOrderTabsCompleteCorrectly() {
            ArenaSetupSession session = newSession();
            session.maxPlayers(4);
            session.lobby(new Arena.Position(0.5, 65, 0.5, 0f, 0f));

            // Start: nothing filled — incomplete.
            assertFalse(session.isComplete(), "must be incomplete before any plot is filled");

            // Fill plot 3 first.
            fillPlotComplete(session.getOrCreatePlot(3));
            assertFalse(session.isComplete(), "still incomplete with only plot 3 done");

            // Fill plot 1 second.
            fillPlotComplete(session.getOrCreatePlot(1));
            assertFalse(session.isComplete(), "still incomplete with plots 1,3 done");

            // Fill plot 4 third.
            fillPlotComplete(session.getOrCreatePlot(4));
            assertFalse(session.isComplete(), "still incomplete with plots 1,3,4 done");

            // Fill the last remaining plot (2) → now complete.
            fillPlotComplete(session.getOrCreatePlot(2));
            assertTrue(session.isComplete(), "must be complete after all 4 plots filled out-of-order");
        }
    }

    // ── scenario 2 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Scenario 2 — picture corners set before basics within a plot")
    class OutOfOrderFieldFillWithinPlot {

        /**
         * Within plot 1, picture corners/face are set BEFORE spawn/corner/cameras.
         * The result must still be complete once all fields are eventually provided.
         */
        @Test
        @DisplayName("pictureBeforeBasicsStillCompletes")
        void pictureBeforeBasicsStillCompletes() {
            ArenaSetupSession session = newSession();
            session.maxPlayers(1);
            session.lobby(new Arena.Position(0.5, 65, 0.5, 0f, 0f));

            ArenaSetupSession.PlotSetupData plot = session.getOrCreatePlot(1);

            // Set picture fields first (opposite of the natural order).
            fillPictureValid(plot);
            assertFalse(plot.isComplete(), "plot must be incomplete without basics");
            assertFalse(session.isComplete(), "session must be incomplete while plot is incomplete");

            // Now add the basics — nothing lost from the earlier picture fill.
            fillPlotBasics(plot);
            assertTrue(plot.isComplete(), "plot must be complete after basics added post-picture");
            assertTrue(session.isComplete(), "session must be complete once the single plot is complete");
        }
    }

    // ── scenario 3 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Scenario 3 — lobby set last, after plots and maxPlayers")
    class LobbySetLast {

        /**
         * All plots and maxPlayers are filled first; lobby is set last.
         * isComplete() must be false until the lobby is provided, then true.
         */
        @Test
        @DisplayName("lobbySetAfterPlotsCompletesCorrectly")
        void lobbySetAfterPlotsCompletesCorrectly() {
            ArenaSetupSession session = newSession();

            // Fill plots first — maxPlayers not yet set, so isComplete is trivially false.
            for (int i = 1; i <= 2; i++)
                fillPlotComplete(session.getOrCreatePlot(i));

            // Set maxPlayers without lobby.
            session.maxPlayers(2);
            assertFalse(session.isComplete(), "incomplete: lobby not set yet");

            // Now set the lobby — all requirements met.
            session.lobby(new Arena.Position(0.5, 65, 0.5, 0f, 0f));
            assertTrue(session.isComplete(), "must be complete after lobby set last");
        }
    }

    // ── scenario 4 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Scenario 4 — overwriting a field does not break completeness")
    class FieldOverwrite {

        /**
         * A fully-complete session has one of its fields overwritten with a new
         * (still-valid) value. isComplete() must remain true.
         */
        @Test
        @DisplayName("overwritingFieldPreservesCompleteness")
        void overwritingFieldPreservesCompleteness() {
            ArenaSetupSession session = newSession();
            session.maxPlayers(2);
            session.lobby(new Arena.Position(0.5, 65, 0.5, 0f, 0f));
            for (int i = 1; i <= 2; i++)
                fillPlotComplete(session.getOrCreatePlot(i));
            assertTrue(session.isComplete(), "precondition: session must be complete");

            // Overwrite the lobby with a different valid position.
            session.lobby(new Arena.Position(1.5, 65, 1.5, 90f, 0f));
            assertTrue(session.isComplete(), "must remain complete after lobby overwrite");

            // Overwrite the spawn of plot 1 with a different valid position.
            session.getOrCreatePlot(1).spawn(new Arena.Position(2.5, 66, 2.5, 0f, 0f));
            assertTrue(session.isComplete(), "must remain complete after plot spawn overwrite");

            // Overwrite plot 2's picture face with a still-valid face (NORTH → SOUTH,
            // same XY plane so both are allowed).
            session.getOrCreatePlot(2).pictureFace(BlockFace.SOUTH);
            assertTrue(session.isComplete(), "must remain complete after picture face overwrite");
        }
    }

    // ── scenario 5 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Scenario 5 — trimPlotsAbove reflects reduced maxPlayers in isComplete()")
    class TrimPlotsAbove {

        /**
         * Four plots are filled, then maxPlayers is reduced to 2 and
         * trimPlotsAbove(2) is called. isComplete() must reflect only plots 1..2.
         */
        @Test
        @DisplayName("isCompleteReflectsReducedPlotCount")
        void isCompleteReflectsReducedPlotCount() {
            ArenaSetupSession session = newSession();
            session.maxPlayers(4);
            session.lobby(new Arena.Position(0.5, 65, 0.5, 0f, 0f));
            for (int i = 1; i <= 4; i++)
                fillPlotComplete(session.getOrCreatePlot(i));
            assertTrue(session.isComplete(), "precondition: 4-player session must be complete");

            // Reduce to 2 players and trim the excess plots.
            session.maxPlayers(2);
            session.trimPlotsAbove(2);
            assertTrue(session.isComplete(), "must be complete for the trimmed 2-player session");
        }

        /**
         * Plots 1..4 are filled, then maxPlayers is reduced to 2 and trimPlotsAbove(2)
         * called, but plot 1 is then made incomplete by removing its spawn. Verifies
         * that the trimmed session correctly reports incomplete.
         */
        @Test
        @DisplayName("trimmedSessionDetectsIncompletePlot")
        void trimmedSessionDetectsIncompletePlot() {
            ArenaSetupSession session = newSession();
            session.maxPlayers(4);
            session.lobby(new Arena.Position(0.5, 65, 0.5, 0f, 0f));
            for (int i = 1; i <= 4; i++)
                fillPlotComplete(session.getOrCreatePlot(i));

            // Trim to 2 then break plot 1.
            session.maxPlayers(2);
            session.trimPlotsAbove(2);
            session.getOrCreatePlot(1).spawn(null);
            assertFalse(session.isComplete(), "must be incomplete when a retained plot loses its spawn");
        }
    }

    // ── scenario 6 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Scenario 6 — each plot evaluated independently")
    class IndependentPlotCompleteness {

        /**
         * Two plots: plot 1 has all fields; plot 2 is missing the picture tab.
         * Only plot 2 should report isComplete() == false; the session is also false.
         */
        @Test
        @DisplayName("skippedPictureTabMakesOnlyThatPlotIncomplete")
        void skippedPictureTabMakesOnlyThatPlotIncomplete() {
            ArenaSetupSession session = newSession();
            session.maxPlayers(2);
            session.lobby(new Arena.Position(0.5, 65, 0.5, 0f, 0f));

            // Plot 1: fully complete.
            fillPlotComplete(session.getOrCreatePlot(1));
            assertTrue(session.getOrCreatePlot(1).isComplete(), "plot 1 must be complete");

            // Plot 2: basics only, picture tab skipped.
            fillPlotBasics(session.getOrCreatePlot(2));
            assertFalse(session.getOrCreatePlot(2).isComplete(), "plot 2 must be incomplete without picture");
            assertFalse(session.isComplete(), "session must be incomplete when plot 2 is missing picture");

            // Now fill the picture for plot 2.
            fillPictureValid(session.getOrCreatePlot(2));
            assertTrue(session.getOrCreatePlot(2).isComplete(), "plot 2 must be complete after picture added");
            assertTrue(session.isComplete(), "session must be complete once both plots are done");
        }

        /**
         * Five plots: basics set for all, picture filled for plots 2, 4 only.
         * isComplete() is false; adding the remaining pictures brings it to true.
         */
        @Test
        @DisplayName("partialPictureFillsLeaveSessionIncomplete")
        void partialPictureFillsLeaveSessionIncomplete() {
            ArenaSetupSession session = newSession();
            session.maxPlayers(5);
            session.lobby(new Arena.Position(0.5, 65, 0.5, 0f, 0f));

            // Basics for all 5 plots.
            for (int i = 1; i <= 5; i++)
                fillPlotBasics(session.getOrCreatePlot(i));

            // Picture only for plots 2 and 4.
            fillPictureValid(session.getOrCreatePlot(2));
            fillPictureValid(session.getOrCreatePlot(4));
            assertFalse(session.isComplete(), "must be incomplete with only some pictures filled");

            // Fill remaining picture tabs (1, 3, 5).
            fillPictureValid(session.getOrCreatePlot(1));
            fillPictureValid(session.getOrCreatePlot(3));
            fillPictureValid(session.getOrCreatePlot(5));
            assertTrue(session.isComplete(), "must be complete after all picture tabs filled");
        }
    }
}
