package ru.ashesha.buildBattleAI.listeners;

import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.commands.MLTestCommand;
import ru.ashesha.buildBattleAI.message.micro.ChatMicroService;
import ru.ashesha.buildBattleAI.util.SoundPalette;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the WorldEdit-style selection for the {@code /bbaitest} diagnostic
 * pipeline. Watches for {@link PlayerInteractEvent} from players holding our
 * stamped wooden axe (see {@link MLTestCommand#WAND_NAME}) and records the
 * clicked block coordinates as corner 1 (left-click) or corner 2 (right-click).
 * <p>
 * Once both corners are set, the listener prompts the player with a clickable
 * chat message that fires {@code /bbaitest run} — the actual ML pipeline lives
 * in {@link MLTestCommand} so this class can stay narrow and event-only.
 * <p>
 * Selection state is per-player and stored statically: it must survive across
 * the chat round-trip ({@code interact} → chat link → {@code /bbaitest run})
 * without depending on the command listener constructing its own state holder.
 * The map is sized small (one entry per active tester) and is wiped when the
 * player quits or when the test runs.
 */
public class MLTestListener extends ListenerService.PluginListener {

    /**
     * Per-player selection state. {@code ConcurrentHashMap} because the
     * static {@link #getSelection(UUID)} accessor is invoked from
     * {@link MLTestCommand} which is processed on the main thread but may
     * be called from the async render task during shutdown races.
     */
    private static final Map<UUID, Selection> SELECTIONS = new ConcurrentHashMap<>();

    /**
     * Creates the listener.
     *
     * @param plugin the plugin instance
     */
    public MLTestListener(@NonNull BuildBattleAI plugin) {
        super(plugin);
    }

    // ── selection accessors ────────────────────────────────────────────────

    /**
     * Returns the current selection for the given player, or {@code null} if
     * the player has no in-progress selection.
     *
     * @param playerId the player UUID
     * @return the selection, or {@code null}
     */
    public static Selection getSelection(UUID playerId) {
        return SELECTIONS.get(playerId);
    }

    /**
     * Clears the selection for a player, e.g. after a successful test run.
     * Safe to call when no selection exists.
     *
     * @param playerId the player UUID
     */
    public static void clearSelection(UUID playerId) {
        SELECTIONS.remove(playerId);
    }

    /**
     * Identifies whether an ItemStack is one of our marked test wands. Used
     * both by the listener to gate selection clicks and by the command to
     * skip duplicate handouts.
     *
     * @param item the item to inspect
     * @return {@code true} if the item is a stamped wand
     */
    public static boolean isWand(ItemStack item) {
        if (item == null)
            return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName())
            return false;
        return MLTestCommand.WAND_NAME.equals(meta.getDisplayName());
    }

    // ── event handlers ─────────────────────────────────────────────────────

    /**
     * Records a corner click. Left-click on a block stores corner 1,
     * right-click stores corner 2. Air clicks are ignored. The listener
     * cancels the event when it matches so the player doesn't accidentally
     * break the block they're selecting.
     *
     * @param event the interact event
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isWand(event.getItem()))
            return;

        Action action = event.getAction();
        Block clicked = event.getClickedBlock();
        // Air-click yields no block — without a block we can't anchor the
        // selection coordinate, so quietly ignore.
        if (clicked == null)
            return;

        Selection selection = SELECTIONS.computeIfAbsent(player.getUniqueId(),
                k -> new Selection(player.getWorld().getName()));

        Location loc = clicked.getLocation();
        if (action == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            selection.setCorner1(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            announceCorner(player, 1, selection);
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            selection.setCorner2(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            announceCorner(player, 2, selection);
        }
    }

    /**
     * Drops any stale selection state when the player disconnects. Otherwise
     * the static map would leak entries for every test session.
     *
     * @param event the quit event
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        SELECTIONS.remove(event.getPlayer().getUniqueId());
    }

    // ── chat feedback ──────────────────────────────────────────────────────

    /**
     * Sends the player a chat update after a corner is recorded. When both
     * corners are set, appends a clickable "Run ML Test" button that fires
     * {@code /bbaitest run}.
     */
    private void announceCorner(Player player, int cornerIndex, Selection selection) {
        SoundPalette.CONFIRM.play(player);
        plugin.getContext().getMessageService().sendChat(player,
                "&aCorner " + cornerIndex + " set: " + describePoint(selection, cornerIndex));

        if (!selection.isComplete())
            return;

        // Both corners present — offer the clickable trigger so the player
        // doesn't have to type the run subcommand by hand.
        ChatMicroService.ChatMessage msg = new ChatMicroService.ChatMessage();
        msg.append("&7Region selected. ");
        msg.append("&a&l[Run ML Test]",
                ChatMicroService.ClickAction.RUN_COMMAND, "/bbaitest run",
                "&7Click to render this region from your current viewpoint and run the ML pipeline.");
        plugin.getContext().getMessageService().sendChat(player, msg);
    }

    /**
     * Renders the just-clicked corner of a selection as a coordinate string
     * for the chat feedback line.
     */
    private static String describePoint(Selection selection, int cornerIndex) {
        return cornerIndex == 1
                ? coords(selection.c1x, selection.c1y, selection.c1z)
                : coords(selection.c2x, selection.c2y, selection.c2z);
    }

    /**
     * Formats a block coordinate triple for inline chat output.
     */
    private static String coords(int x, int y, int z) {
        return "&f(" + x + ", " + y + ", " + z + ")";
    }

    // ── selection data holder ──────────────────────────────────────────────

    /**
     * Mutable holder for the two corners of an in-progress selection.
     * Fields are package-private so the enclosing listener can write them
     * directly; the outward-facing API (used by {@link MLTestCommand}) is
     * the read-only {@link #minX()}/{@link #maxX()} accessors that already
     * normalize the cuboid into min/max form.
     */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    public static class Selection {

        private final String worldName;
        private Integer c1x, c1y, c1z;
        private Integer c2x, c2y, c2z;

        void setCorner1(int x, int y, int z) {
            this.c1x = x;
            this.c1y = y;
            this.c1z = z;
        }

        void setCorner2(int x, int y, int z) {
            this.c2x = x;
            this.c2y = y;
            this.c2z = z;
        }

        /**
         * Returns {@code true} when both corners have been recorded so the
         * cuboid is fully specified.
         */
        public boolean isComplete() {
            return c1x != null && c2x != null;
        }

        /**
         * Resolves the Bukkit world the selection lives in, or {@code null}
         * if the world has been unloaded since the selection was made.
         */
        public World world() {
            return org.bukkit.Bukkit.getWorld(worldName);
        }

        public int minX() {
            return Math.min(c1x, c2x);
        }

        public int minY() {
            return Math.min(c1y, c2y);
        }

        public int minZ() {
            return Math.min(c1z, c2z);
        }

        public int maxX() {
            return Math.max(c1x, c2x);
        }

        public int maxY() {
            return Math.max(c1y, c2y);
        }

        public int maxZ() {
            return Math.max(c1z, c2z);
        }
    }

    /**
     * Intentionally unused — placeholder hook to satisfy Lombok's
     * {@code @NonNull} expectation on imported types without IDE-level
     * warnings. Keeps the import list non-noisy.
     */
    @SuppressWarnings("unused")
    private static void touch(List<?> ignored) {
    }
}
