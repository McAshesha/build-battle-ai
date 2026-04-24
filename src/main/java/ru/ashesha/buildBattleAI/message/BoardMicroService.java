package ru.ashesha.buildBattleAI.message;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import lombok.Getter;
import lombok.NonNull;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.util.MessageUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sub-service responsible for creating and managing packet-based scoreboards (sidebars).
 * <p>
 * Scoreboards are displayed on the right side of the player's screen using Minecraft's
 * sidebar objective system. Each player can have at most one active board at a time.
 * The board supports up to {@value #MAX_LINES} lines of text, each controlled by a
 * team packet whose prefix (and suffix on pre-1.13) determines the displayed content.
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

    /** Maximum number of lines a scoreboard can display (Minecraft protocol limit). */
    public static final int MAX_LINES = 15;

    /** Sidebar display position in the {@link WrapperPlayServerDisplayScoreboard} packet. */
    private static final int SIDEBAR_POSITION = 1;

    /** Objective name shared by all boards created by this service. */
    private static final String OBJECTIVE_NAME = "_bbai";

    /** Prefix for team names — each line uses {@code _bb_0} through {@code _bb_14}. */
    private static final String TEAM_NAME_PREFIX = "_bb_";

    /**
     * Unique invisible entry names for each scoreboard line (0–14).
     * Each entry is a section-sign color code followed by a reset ({@code §X§r}),
     * which renders as invisible text in the sidebar while remaining distinct
     * as a score entry name. The array is indexed by line number.
     */
    private static final String[] ENTRY_NAMES = {
            "§0§r", "§1§r", "§2§r", "§3§r",
            "§4§r", "§5§r", "§6§r", "§7§r",
            "§8§r", "§9§r", "§a§r", "§b§r",
            "§c§r", "§d§r", "§e§r"
    };

    /**
     * Serializer for converting {@code §}-prefixed color codes into Adventure
     * {@link Component}s. Used on pre-1.13 servers where the legacy text protocol
     * requires section-sign formatting.
     */
    private static final LegacyComponentSerializer SECTION_SERIALIZER =
            LegacyComponentSerializer.legacySection();

    /** The plugin instance, used for sending packets via the context. */
    private final BuildBattleAI plugin;

    /** Whether the server is pre-1.13 (legacy text protocol with 16-char limits). */
    private final boolean legacy;

    /** Active boards keyed by player UUID. */
    private final Map<UUID, Board> boards = new HashMap<>();

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
     * Creates a new scoreboard for the given player and displays it immediately
     * on the sidebar. If the player already has an active board, the old one is
     * removed first.
     *
     * @param player the target player
     * @param title  the scoreboard title (supports {@code &} color codes)
     * @return the newly created board
     */
    public Board createBoard(@NonNull Player player, @NonNull String title) {
        Board existing = boards.remove(player.getUniqueId());
        if (existing != null)
            existing.remove();

        Board board = new Board(player, title);
        boards.put(player.getUniqueId(), board);
        return board;
    }

    /**
     * Retrieves the active board for the given player.
     *
     * @param player the target player
     * @return the player's active board, or {@code null} if none exists
     */
    public Board getBoard(@NonNull Player player) {
        return boards.get(player.getUniqueId());
    }

    /**
     * Removes the active board for the given player, sending all necessary
     * cleanup packets. Does nothing if the player has no active board.
     *
     * @param player the target player
     */
    public void removeBoard(@NonNull Player player) {
        Board board = boards.remove(player.getUniqueId());
        if (board != null)
            board.remove();
    }

    /**
     * Removes all active boards and clears the internal tracking map.
     * Called during plugin shutdown to release resources cleanly.
     */
    void shutdown() {
        for (Board board : new ArrayList<>(boards.values()))
            board.remove();
        boards.clear();
    }

    // ── legacy text splitting ───────────────────────────────────────────

    /**
     * Splits a section-sign-coded string into prefix and suffix parts,
     * each at most 16 characters, for use on pre-1.13 servers. Carries the
     * last active color and formatting codes from the prefix into the suffix
     * so that visual formatting is preserved across the split boundary.
     *
     * @param text the translated text with {@code §} color codes
     * @return a two-element array: {@code [prefix, suffix]}
     */
    static String[] splitLegacyLine(String text) {
        if (text.length() <= 16)
            return new String[]{text, ""};

        // Avoid splitting in the middle of a color code sequence (§X)
        int splitAt = 16;
        if (text.charAt(splitAt - 1) == '§')
            splitAt--;

        String prefix = text.substring(0, splitAt);
        String carry = getLastColors(prefix);
        String suffix = carry + text.substring(splitAt);

        // Truncate suffix to fit the 16-char protocol limit
        if (suffix.length() > 16)
            suffix = suffix.substring(0, 16);

        return new String[]{prefix, suffix};
    }

    /**
     * Extracts the last active color and formatting codes from a legacy text string.
     * A color code ({@code §0}–{@code §f}) resets all prior formatting; {@code §r}
     * resets everything. Format codes ({@code §k}–{@code §o}) are cumulative until
     * the next color code or reset.
     *
     * @param text the text to scan for color codes
     * @return the cumulative color/format codes active at the end of the text
     */
    static String getLastColors(String text) {
        StringBuilder color = new StringBuilder();
        StringBuilder format = new StringBuilder();
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) != '§')
                continue;
            char code = Character.toLowerCase(text.charAt(i + 1));
            if (code == 'r') {
                color.setLength(0);
                format.setLength(0);
            } else if ("0123456789abcdef".indexOf(code) >= 0) {
                color.setLength(0);
                color.append('§').append(text.charAt(i + 1));
                format.setLength(0); // a color code resets formatting
            } else if ("klmno".indexOf(code) >= 0) 
                format.append('§').append(text.charAt(i + 1));
            i++; // skip the code character
        }
        return color.toString() + format;
    }

    // ── Board inner class ───────────────────────────────────────────────

    /**
     * Represents a single player's sidebar scoreboard.
     * <p>
     * Supports up to {@value #MAX_LINES} lines indexed from 0 (bottom of sidebar)
     * to 14 (top of sidebar). Each line is backed by a team packet whose prefix
     * (and suffix on pre-1.13) controls the displayed text, and an invisible score
     * entry name ensures uniqueness.
     * <p>
     * Changes are sent immediately as packets — there is no batching or deferred
     * flush. Call {@link #remove()} to tear down the board and release all
     * protocol-level resources (objective, teams, score entries).
     */
    public class Board {

        /** The player this board belongs to.
         * -- GETTER --
         *  Returns the player this board belongs to.
         */
        @Getter
        private final Player player;

        /** Current title of the board (raw text with {@code &} color codes).
         * -- GETTER --
         *  Returns the current title.
         */
        @Getter
        private String title;

        /** Tracks which line indices are currently active (have content). */
        private final boolean[] activeLines = new boolean[MAX_LINES];

        /** Current text for each line (raw text with {@code &} codes), or {@code null}. */
        private final String[] lineTexts = new String[MAX_LINES];

        /** Whether this board has already been removed. */
        private boolean removed;

        /**
         * Creates the board, sending the objective creation and sidebar display packets.
         *
         * @param player the owner player
         * @param title  the board title (supports {@code &} color codes)
         */
        private Board(@NonNull Player player, @NonNull String title) {
            this.player = player;
            this.title = title;
            sendObjective(WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE, title);
            sendDisplayObjective();
        }

        /**
         * Updates the board title. Does nothing if the title hasn't changed
         * or the board has been removed.
         *
         * @param title the new title (supports {@code &} color codes)
         */
        public void setTitle(@NonNull String title) {
            if (removed || this.title.equals(title))
                return;
            this.title = title;
            sendObjective(WrapperPlayServerScoreboardObjective.ObjectiveMode.UPDATE, title);
        }

        /**
         * Sets the text for a specific line. Creates the line if it does not exist,
         * or updates it if the text has changed. Does nothing if the board has been
         * removed or the text is identical to the current value.
         *
         * @param line the line index (0 = bottom, 14 = top)
         * @param text the line text (supports {@code &} color codes)
         * @throws IllegalArgumentException if line is outside [0, {@value #MAX_LINES})
         */
        public void setLine(int line, @NonNull String text) {
            validateLine(line);
            if (removed)
                return;

            // Skip update if text hasn't changed
            if (activeLines[line] && text.equals(lineTexts[line]))
                return;

            lineTexts[line] = text;

            if (activeLines[line]) 
                // Line exists — update team info only
                sendTeamUpdate(line, text);
            else {
                // New line — create team, add entry, set score
                activeLines[line] = true;
                sendTeamCreate(line, text);
                sendScore(line);
            }
        }

        /**
         * Bulk-sets multiple lines starting from index 0. Lines beyond the list
         * size are removed. List index 0 maps to score 0 (bottom of sidebar).
         *
         * @param lines the line texts (supports {@code &} color codes)
         */
        public void setLines(@NonNull List<String> lines) {
            int count = Math.min(lines.size(), MAX_LINES);
            for (int i = 0; i < count; i++)
                setLine(i, lines.get(i));
            for (int i = count; i < MAX_LINES; i++)
                removeLine(i);
        }

        /**
         * Removes a specific line from the board. Does nothing if the line
         * is not active or the board has been removed.
         *
         * @param line the line index (0–14)
         * @throws IllegalArgumentException if line is outside [0, {@value #MAX_LINES})
         */
        public void removeLine(int line) {
            validateLine(line);
            if (removed || !activeLines[line])
                return;

            activeLines[line] = false;
            lineTexts[line] = null;
            sendScoreRemove(line);
            sendTeamRemove(line);
        }

        /**
         * Returns the current text of a line, or {@code null} if the line is not set.
         *
         * @param line the line index (0–14)
         * @return the line text, or {@code null}
         * @throws IllegalArgumentException if line is outside [0, {@value #MAX_LINES})
         */
        public String getLine(int line) {
            validateLine(line);
            return lineTexts[line];
        }

        /**
         * Removes the board from the player's screen by sending objective removal
         * and team cleanup packets. Also removes the board from the service's
         * internal tracking map. Safe to call multiple times (idempotent).
         * <p>
         * If the player is no longer online, only the internal state is cleaned
         * up — no packets are sent (the client-side scoreboard is already gone).
         */
        public void remove() {
            if (removed)
                return;
            removed = true;

            // Only send cleanup packets if the player is still connected;
            // otherwise the PacketEvents user channel is already gone and
            // sending would just spam warnings in the console.
            if (player.isOnline()) {
                for (int i = 0; i < MAX_LINES; i++)
                    if (activeLines[i]) {
                        sendScoreRemove(i);
                        sendTeamRemove(i);
                    }
                sendObjective(WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, null);
            }

            boards.remove(player.getUniqueId());
        }

        // ── packet helpers ──────────────────────────────────────────────

        /**
         * Sends a scoreboard objective packet (create, update, or remove).
         *
         * @param mode  the objective mode
         * @param title the display title, or {@code null} for removal
         */
        private void sendObjective(WrapperPlayServerScoreboardObjective.ObjectiveMode mode,
                                   String title) {
            Component displayName = title != null
                    ? MessageUtils.toComponent(title) : Component.empty();
            plugin.getContext().sendPacket(player,
                    new WrapperPlayServerScoreboardObjective(
                            OBJECTIVE_NAME, mode, displayName,
                            WrapperPlayServerScoreboardObjective.RenderType.INTEGER));
        }

        /**
         * Sends the display objective packet to show the scoreboard on the sidebar.
         */
        private void sendDisplayObjective() {
            plugin.getContext().sendPacket(player,
                    new WrapperPlayServerDisplayScoreboard(SIDEBAR_POSITION, OBJECTIVE_NAME));
        }

        /**
         * Creates a new team for the given line with the display text and adds
         * the line's invisible entry as a team member.
         *
         * @param line the line index
         * @param text the display text (supports {@code &} color codes)
         */
        private void sendTeamCreate(int line, String text) {
            WrapperPlayServerTeams.ScoreBoardTeamInfo info = buildTeamInfo(text);
            plugin.getContext().sendPacket(player, new WrapperPlayServerTeams(
                    TEAM_NAME_PREFIX + line,
                    WrapperPlayServerTeams.TeamMode.CREATE,
                    info,
                    Collections.singletonList(ENTRY_NAMES[line])));
        }

        /**
         * Updates the team info (prefix/suffix) for an existing line without
         * changing team membership.
         *
         * @param line the line index
         * @param text the new display text (supports {@code &} color codes)
         */
        private void sendTeamUpdate(int line, String text) {
            WrapperPlayServerTeams.ScoreBoardTeamInfo info = buildTeamInfo(text);
            plugin.getContext().sendPacket(player, new WrapperPlayServerTeams(
                    TEAM_NAME_PREFIX + line,
                    WrapperPlayServerTeams.TeamMode.UPDATE,
                    info,
                    Collections.emptyList()));
        }

        /**
         * Removes the team for the given line.
         *
         * @param line the line index
         */
        private void sendTeamRemove(int line) {
            plugin.getContext().sendPacket(player, new WrapperPlayServerTeams(
                    TEAM_NAME_PREFIX + line,
                    WrapperPlayServerTeams.TeamMode.REMOVE,
                    (WrapperPlayServerTeams.ScoreBoardTeamInfo) null,
                    Collections.emptyList()));
        }

        /**
         * Sends a score entry for the given line (makes it appear on the sidebar).
         * The score value equals the line index, so higher indices appear higher.
         *
         * @param line the line index (also used as the score value)
         */
        private void sendScore(int line) {
            plugin.getContext().sendPacket(player,
                    new WrapperPlayServerUpdateScore(
                            ENTRY_NAMES[line],
                            WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
                            OBJECTIVE_NAME,
                            Optional.of(line)));
        }

        /**
         * Removes the score entry for the given line from the sidebar.
         *
         * @param line the line index
         */
        private void sendScoreRemove(int line) {
            plugin.getContext().sendPacket(player,
                    new WrapperPlayServerUpdateScore(
                            ENTRY_NAMES[line],
                            WrapperPlayServerUpdateScore.Action.REMOVE_ITEM,
                            OBJECTIVE_NAME,
                            Optional.empty()));
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
                String translated = text.replace('&', '§');
                String[] parts = splitLegacyLine(translated);
                prefix = SECTION_SERIALIZER.deserialize(parts[0]);
                suffix = SECTION_SERIALIZER.deserialize(parts[1]);
            } else {
                // 1.13+: full text in prefix as a rich Component
                prefix = MessageUtils.toComponent(text);
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
