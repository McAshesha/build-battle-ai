package ru.ashesha.buildBattleAI.message.micro;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import lombok.NonNull;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.util.MessageUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Sub-service responsible for creating packet-based scoreboards (sidebars).
 * <p>
 * Scoreboards are displayed on the right side of the player's screen using Minecraft's
 * sidebar objective system. Each {@link Board} supports up to {@value #MAX_LINES} lines
 * of text, each controlled by a team packet whose prefix (and suffix on pre-1.13)
 * determines the displayed content.
 * <p>
 * <b>Stateless service:</b> This service does not track active boards or player
 * associations. The caller is responsible for storing {@link Board} references,
 * passing the target player(s) to every operation, and calling {@link Board#remove}
 * when the board is no longer needed.
 * <p>
 * <b>Multi-version support:</b> Uses PacketEvents wrappers for all scoreboard packets.
 * On 1.13+ servers, line text is placed entirely in the team prefix as a rich
 * {@link Component} (effectively unlimited length). On pre-1.13 servers (legacy),
 * line text is split across the team prefix (16 chars) and suffix (16 chars) with
 * automatic color-code carry-over, yielding a maximum of 32 visible characters per line.
 * <p>
 * <b>Line ordering:</b> Lines are indexed by score value (0–14). Score 0 appears at
 * the bottom of the sidebar; score 14 appears at the top.
 * <p>
 * <b>Implementation:</b> Each line is backed by a unique invisible score entry name
 * ({@code §X§r} color-code sequences) and a team whose prefix/suffix holds the
 * visible text. This avoids collisions when multiple lines share the same display text.
 *
 * @see Board
 */
public class BoardMicroService {

    /**
     * Maximum number of lines a scoreboard can display (Minecraft protocol limit).
     */
    public static final int MAX_LINES = 15;

    /**
     * Sidebar display position in the {@link WrapperPlayServerDisplayScoreboard} packet.
     */
    private static final int SIDEBAR_POSITION = 1;

    /**
     * Objective name shared by all boards created by this service.
     */
    private static final String OBJECTIVE_NAME = "_bbai";

    /**
     * Prefix for team names — each line uses {@code _bb_0} through {@code _bb_14}.
     */
    private static final String TEAM_NAME_PREFIX = "_bb_";

    /**
     * Unique invisible entry names for each scoreboard line (0–14).
     * Each entry is a section-sign color code followed by a reset ({@code §X§r}),
     * which renders as invisible text in the sidebar while remaining distinct
     * as a score entry name. The array is indexed by line number.
     * Built via {@link MessageUtils#translateColors} to centralize all section-sign usage.
     */
    private static final String[] ENTRY_NAMES;

    static {
        String codes = "0123456789abcde";
        ENTRY_NAMES = new String[MAX_LINES];
        for (int i = 0; i < MAX_LINES; i++) {
            String colorEmpty = "&" + codes.charAt(i) + "&r";
            ENTRY_NAMES[i] = MessageUtils.translateColors(colorEmpty);
        }
    }

    /**
     * The plugin instance, used for sending packets via the context.
     */
    private final BuildBattleAI plugin;

    /**
     * Whether the server is pre-1.13 (legacy text protocol with 16-char limits).
     */
    private final boolean legacy;

    /**
     * Creates the board micro-service and resolves the server version for
     * legacy text detection.
     * <p>
     * Must only be invoked after the plugin context has been published —
     * i.e. from inside a {@code PluginService.enable()} call.
     *
     * @param plugin the plugin instance
     */
    public BoardMicroService(@NonNull BuildBattleAI plugin) {
        this.plugin = plugin;
        this.legacy = plugin.getContext().getServerVersion()
                .isOlderThan(ServerVersion.V_1_13);
    }

    // ── public API ──────────────────────────────────────────────────────

    /**
     * Creates a new scoreboard and displays it immediately on the sidebar
     * for the given player. The returned {@link Board} is not tracked by
     * this service — the caller must store and manage its lifecycle.
     *
     * @param player the target player
     * @param title  the scoreboard title (supports {@code &} color codes)
     * @return the newly created board
     */
    public Board createBoard(@NonNull Player player, @NonNull String title) {
        Board board = new Board();
        send(player, objectivePacket(
                WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE, title));
        send(player, displayPacket());
        return board;
    }

    /**
     * Creates a new scoreboard and displays it immediately on the sidebar
     * for all given players. The returned {@link Board} is not tracked by
     * this service — the caller must store and manage its lifecycle.
     *
     * @param players the target players
     * @param title   the scoreboard title (supports {@code &} color codes)
     * @return the newly created board
     */
    public Board createBoard(@NonNull Collection<? extends Player> players, @NonNull String title) {
        Board board = new Board();
        PacketWrapper<?> objective = objectivePacket(
                WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE, title);
        PacketWrapper<?> display = displayPacket();
        for (Player player : players) {
            send(player, objective);
            send(player, display);
        }
        return board;
    }

    // ── packet builders ─────────────────────────────────────────────────

    /**
     * Builds a scoreboard objective packet (create, update, or remove).
     *
     * @param mode  the objective mode
     * @param title the display title, or {@code null} for removal
     * @return the assembled packet wrapper
     */
    private PacketWrapper<?> objectivePacket(
            WrapperPlayServerScoreboardObjective.ObjectiveMode mode, String title) {
        Component displayName = MessageUtils.toColorComponent(title);
        return new WrapperPlayServerScoreboardObjective(
                OBJECTIVE_NAME, mode, displayName,
                WrapperPlayServerScoreboardObjective.RenderType.INTEGER);
    }

    /**
     * Builds the display objective packet to show the scoreboard on the sidebar.
     *
     * @return the assembled packet wrapper
     */
    private PacketWrapper<?> displayPacket() {
        return new WrapperPlayServerDisplayScoreboard(SIDEBAR_POSITION, OBJECTIVE_NAME);
    }

    /**
     * Builds a team creation packet for the given line with the display text
     * and the line's invisible entry as a member.
     *
     * @param line the line index
     * @param text the display text (supports {@code &} color codes)
     * @return the assembled packet wrapper
     */
    private PacketWrapper<?> teamCreatePacket(int line, String text) {
        return new WrapperPlayServerTeams(
                TEAM_NAME_PREFIX + line,
                WrapperPlayServerTeams.TeamMode.CREATE,
                buildTeamInfo(text),
                Collections.singletonList(ENTRY_NAMES[line]));
    }

    /**
     * Builds a team update packet for the given line with the new display text.
     *
     * @param line the line index
     * @param text the new display text (supports {@code &} color codes)
     * @return the assembled packet wrapper
     */
    private PacketWrapper<?> teamUpdatePacket(int line, String text) {
        return new WrapperPlayServerTeams(
                TEAM_NAME_PREFIX + line,
                WrapperPlayServerTeams.TeamMode.UPDATE,
                buildTeamInfo(text),
                Collections.emptyList());
    }

    /**
     * Builds a team removal packet for the given line.
     *
     * @param line the line index
     * @return the assembled packet wrapper
     */
    private PacketWrapper<?> teamRemovePacket(int line) {
        return new WrapperPlayServerTeams(
                TEAM_NAME_PREFIX + line,
                WrapperPlayServerTeams.TeamMode.REMOVE,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null,
                Collections.emptyList());
    }

    /**
     * Builds a score entry packet for the given line (makes it appear on the sidebar).
     * The score value equals the line index, so higher indices appear higher.
     *
     * @param line the line index (also used as the score value)
     * @return the assembled packet wrapper
     */
    private PacketWrapper<?> scorePacket(int line) {
        return new WrapperPlayServerUpdateScore(
                ENTRY_NAMES[line],
                WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
                OBJECTIVE_NAME,
                Optional.of(line));
    }

    /**
     * Builds a score removal packet for the given line.
     *
     * @param line the line index
     * @return the assembled packet wrapper
     */
    private PacketWrapper<?> scoreRemovePacket(int line) {
        return new WrapperPlayServerUpdateScore(
                ENTRY_NAMES[line],
                WrapperPlayServerUpdateScore.Action.REMOVE_ITEM,
                OBJECTIVE_NAME,
                Optional.empty());
    }

    /**
     * Builds the {@link WrapperPlayServerTeams.ScoreBoardTeamInfo} for a line
     * with the given display text.
     * <p>
     * On 1.13+ servers, the full text is placed in the team prefix as an
     * Adventure {@link Component}. On pre-1.13 servers, the text is translated
     * to section-sign codes and split between prefix (16 chars) and suffix
     * (16 chars) with color-code carry-over.
     *
     * @param text the display text (supports {@code &} color codes)
     * @return the assembled team info
     */
    private WrapperPlayServerTeams.ScoreBoardTeamInfo buildTeamInfo(String text) {
        Component prefix;
        Component suffix;

        if (legacy) {
            // Pre-1.13: translate & codes to § and split at the 16-char boundary
            String translated = MessageUtils.translateColors(text);
            String[] parts = MessageUtils.splitLegacyLine(translated);
            prefix = MessageUtils.toSectionColorComponent(parts[0]);
            suffix = MessageUtils.toSectionColorComponent(parts[1]);
        } else {
            // 1.13+: full text in prefix as a rich Component
            prefix = MessageUtils.toColorComponent(text);
            suffix = Component.empty();
        }

        return new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                Component.empty(),
                prefix,
                suffix,
                WrapperPlayServerTeams.NameTagVisibility.NEVER,
                WrapperPlayServerTeams.CollisionRule.NEVER,
                NamedTextColor.WHITE,
                WrapperPlayServerTeams.OptionData.NONE);
    }

    // ── send helpers ────────────────────────────────────────────────────

    /**
     * Sends a packet to a single player via the plugin context.
     *
     * @param player the target player
     * @param packet the packet to send
     */
    private void send(Player player, PacketWrapper<?> packet) {
        plugin.getContext().sendPacket(player, packet);
    }

    /**
     * Sends a packet to multiple players via the plugin context.
     * The packet object is built once and reused for all recipients.
     *
     * @param players the target players
     * @param packet  the packet to send
     */
    private void send(Collection<? extends Player> players, PacketWrapper<?> packet) {
        for (Player player : players)
            plugin.getContext().sendPacket(player, packet);
    }

    // ── Board inner class ───────────────────────────────────────────────

    /**
     * Represents a sidebar scoreboard whose packets can be sent to any player(s).
     * <p>
     * The board tracks only which line indices are currently active (have been
     * created on the client via team/score packets). It does not store player
     * references, titles, or line texts — the caller is responsible for passing
     * the target player(s) to every method and for managing the board's lifecycle.
     * <p>
     * Supports up to {@value #MAX_LINES} lines indexed from 0 (bottom of sidebar)
     * to 14 (top of sidebar). Each line is backed by a team packet whose prefix/suffix
     * controls the displayed text, and an invisible score entry ensures uniqueness.
     * <p>
     * All methods have single-player and collection overloads. The collection
     * variants build each packet once and reuse it across all recipients.
     */
    public class Board {

        /**
         * Tracks which line indices are currently active (have content on the client).
         */
        private final boolean[] activeLines = new boolean[MAX_LINES];

        /**
         * Creates an empty board. Initial objective and display packets are
         * sent by {@link BoardMicroService#createBoard}, not by this constructor.
         */
        private Board() {
        }

        // ── single-player methods ──────────────────────────────────────

        /**
         * Updates the board title for a single player.
         *
         * @param player the target player
         * @param title  the new title (supports {@code &} color codes)
         */
        public void setTitle(@NonNull Player player, @NonNull String title) {
            send(player, objectivePacket(
                    WrapperPlayServerScoreboardObjective.ObjectiveMode.UPDATE, title));
        }

        /**
         * Sets the text for a specific line for a single player. Creates the line
         * if it does not exist, or updates it if it is already active.
         *
         * @param player the target player
         * @param line   the line index (0 = bottom, 14 = top)
         * @param text   the line text (supports {@code &} color codes)
         * @throws IllegalArgumentException if line is outside [0, {@value #MAX_LINES})
         */
        public void setLine(@NonNull Player player, int line, @NonNull String text) {
            validateLine(line);
            if (activeLines[line])
                send(player, teamUpdatePacket(line, text));
            else {
                activeLines[line] = true;
                send(player, teamCreatePacket(line, text));
                send(player, scorePacket(line));
            }
        }

        /**
         * Bulk-sets multiple lines starting from index 0 for a single player.
         * Lines beyond the list size are removed.
         *
         * @param player the target player
         * @param lines  the line texts (supports {@code &} color codes)
         */
        public void setLines(@NonNull Player player, @NonNull List<String> lines) {
            int count = Math.min(lines.size(), MAX_LINES);
            for (int i = 0; i < count; i++)
                setLine(player, i, lines.get(i));
            for (int i = count; i < MAX_LINES; i++)
                removeLine(player, i);
        }

        /**
         * Removes a specific line for a single player. Does nothing if the line
         * is not active.
         *
         * @param player the target player
         * @param line   the line index (0–14)
         * @throws IllegalArgumentException if line is outside [0, {@value #MAX_LINES})
         */
        public void removeLine(@NonNull Player player, int line) {
            validateLine(line);
            if (!activeLines[line])
                return;
            activeLines[line] = false;
            send(player, scoreRemovePacket(line));
            send(player, teamRemovePacket(line));
        }

        /**
         * Removes the entire board for a single player by sending cleanup packets
         * for all active lines and the objective itself. Resets all internal line
         * state so the board can be safely discarded.
         *
         * @param player the target player
         */
        public void remove(@NonNull Player player) {
            for (int i = 0; i < MAX_LINES; i++)
                if (activeLines[i]) {
                    activeLines[i] = false;
                    send(player, scoreRemovePacket(i));
                    send(player, teamRemovePacket(i));
                }
            send(player, objectivePacket(
                    WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, null));
        }

        // ── collection methods ─────────────────────────────────────────

        /**
         * Updates the board title for multiple players. The packet is built once
         * and reused across all recipients.
         *
         * @param players the target players
         * @param title   the new title (supports {@code &} color codes)
         */
        public void setTitle(@NonNull Collection<? extends Player> players, @NonNull String title) {
            send(players, objectivePacket(
                    WrapperPlayServerScoreboardObjective.ObjectiveMode.UPDATE, title));
        }

        /**
         * Sets the text for a specific line for multiple players. Creates the line
         * if it does not exist, or updates it if it is already active. Packets are
         * built once and reused across all recipients.
         *
         * @param players the target players
         * @param line    the line index (0 = bottom, 14 = top)
         * @param text    the line text (supports {@code &} color codes)
         * @throws IllegalArgumentException if line is outside [0, {@value #MAX_LINES})
         */
        public void setLine(@NonNull Collection<? extends Player> players, int line,
                            @NonNull String text) {
            validateLine(line);
            if (activeLines[line])
                send(players, teamUpdatePacket(line, text));
            else {
                activeLines[line] = true;
                send(players, teamCreatePacket(line, text));
                send(players, scorePacket(line));
            }
        }

        /**
         * Bulk-sets multiple lines starting from index 0 for multiple players.
         * Lines beyond the list size are removed.
         *
         * @param players the target players
         * @param lines   the line texts (supports {@code &} color codes)
         */
        public void setLines(@NonNull Collection<? extends Player> players,
                             @NonNull List<String> lines) {
            int count = Math.min(lines.size(), MAX_LINES);
            for (int i = 0; i < count; i++)
                setLine(players, i, lines.get(i));
            for (int i = count; i < MAX_LINES; i++)
                removeLine(players, i);
        }

        /**
         * Removes a specific line for multiple players. Does nothing if the line
         * is not active. Packets are built once and reused across all recipients.
         *
         * @param players the target players
         * @param line    the line index (0–14)
         * @throws IllegalArgumentException if line is outside [0, {@value #MAX_LINES})
         */
        public void removeLine(@NonNull Collection<? extends Player> players, int line) {
            validateLine(line);
            if (!activeLines[line])
                return;
            activeLines[line] = false;
            send(players, scoreRemovePacket(line));
            send(players, teamRemovePacket(line));
        }

        /**
         * Removes the entire board for multiple players by sending cleanup packets
         * for all active lines and the objective itself. Packets for each line are
         * built once and reused across all recipients. Resets all internal line state.
         *
         * @param players the target players
         */
        public void remove(@NonNull Collection<? extends Player> players) {
            for (int i = 0; i < MAX_LINES; i++)
                if (activeLines[i]) {
                    activeLines[i] = false;
                    send(players, scoreRemovePacket(i));
                    send(players, teamRemovePacket(i));
                }
            send(players, objectivePacket(
                    WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, null));
        }

        // ── validation ─────────────────────────────────────────────────

        /**
         * Validates that a line index is within the allowed range [0, {@value #MAX_LINES}).
         *
         * @param line the line index to validate
         * @throws IllegalArgumentException if the index is out of range
         */
        private void validateLine(int line) {
            if (line < 0 || line >= MAX_LINES)
                throw new IllegalArgumentException(
                        "Line index must be 0-" + (MAX_LINES - 1) + ", got: " + line);
        }
    }
}
