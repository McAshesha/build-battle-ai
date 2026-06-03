package ru.ashesha.buildBattleAI.game.feedback;

import com.cryptomorin.xseries.XMaterial;
import lombok.NonNull;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.ashesha.buildBattleAI.config.api.Lang;
import ru.ashesha.buildBattleAI.util.MessageUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory + identity check for the "skip current theme" feather players hold
 * in slot 8 of their hotbar during PLAYING.
 * <p>
 * Identity is anchored on a magic line inserted into the item's lore — NOT
 * the display name. The display name is admin-editable through
 * {@code lang/<lang>.yml} so anchoring on it would break detection after a
 * translation edit. The marker line uses obfuscated color codes so it is
 * effectively invisible to the player but persists across pickup/drop.
 * <p>
 * <b>Cross-version note:</b> uses only Bukkit's pre-1.13 {@code ItemMeta}
 * APIs (no PersistentDataContainer) so it stays compatible with 1.8 servers.
 */
public final class SkipThemeItem {

    /**
     * The skip feather always lives in slot 8 (the last hotbar slot —
     * right of the action bar). Constant exposed so listeners and the
     * GameManager agree on where to read/place it.
     */
    public static final int HOTBAR_SLOT = 8;

    private SkipThemeItem() {
        // utility class
    }

    /**
     * Builds a fresh skip-theme feather with name, lore, and the hidden
     * identity marker. A new {@code ItemStack} is returned on every call —
     * safe to give to multiple players.
     *
     * @param lang the language to read display strings from
     * @return a fully-populated feather item; never {@code null}
     */
    public static ItemStack create(@NonNull Lang lang) {
        ItemStack item = XMaterial.FEATHER.parseItem();
        if (item == null)
            // Should never happen — FEATHER is a vanilla material on every
            // Minecraft version we support. Fall back so the player keeps
            // an empty slot instead of crashing the game start.
            return new ItemStack(org.bukkit.Material.AIR);

        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return item;

        meta.setDisplayName(MessageUtils.translateColors(lang.get("game.ai.skip-item.name")));

        // Lore = configured lines (user-visible) + the hidden marker as the
        // last line. The marker MUST be the final line so its translated
        // form is also at lore.size()-1 in isSkipItem().
        List<String> userLore = lang.getList("game.ai.skip-item.lore");
        List<String> lore = new ArrayList<>(userLore.size() + 1);
        for (String line : userLore)
            lore.add(MessageUtils.translateColors(line));
        lore.add(MessageUtils.translateColors(lang.get("game.ai.skip-item.marker")));
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Returns {@code true} if the given item is one of our skip feathers.
     * Matches by lore-tail marker, not by display name — see class Javadoc
     * for rationale.
     * <p>
     * Safe to call with {@code null} or any random item — returns false
     * for non-feathers, items without meta, or items whose lore tail does
     * not match the configured marker line.
     *
     * @param item the candidate item (may be {@code null})
     * @param lang the language used to render the marker for comparison
     */
    public static boolean isSkipItem(ItemStack item, @NonNull Lang lang) {
        if (item == null)
            return false;
        // Cheap material check first so a hotbar swing on a regular feather
        // doesn't drag us through string comparisons we don't need.
        // Use the XMaterial matcher (1.8–1.21 safe) rather than the
        // deprecated XMaterial#parseMaterial.
        if (XMaterial.matchXMaterial(item.getType()) != XMaterial.FEATHER)
            return false;
        if (!item.hasItemMeta())
            return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore())
            return false;
        List<String> lore = meta.getLore();
        if (lore == null || lore.isEmpty())
            return false;
        String expected = MessageUtils.translateColors(lang.get("game.ai.skip-item.marker"));
        // Tail comparison — admins may append additional lore lines (e.g.
        // through a third-party plugin) but the marker we wrote stays at
        // the tail unless someone deliberately edits the item.
        return expected.equals(lore.get(lore.size() - 1));
    }
}
