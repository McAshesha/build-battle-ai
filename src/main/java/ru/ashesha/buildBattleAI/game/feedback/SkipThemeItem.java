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
 * Identity is anchored on the combination "FEATHER with non-empty lore".
 * That is enough in practice: players are sandboxed in CREATIVE inside the
 * arena world but have no operator permissions, so vanilla {@code /give} and
 * the creative inventory cannot produce a feather with custom lore. Anything
 * a player can spawn (chicken drops, creative-tab feathers) has no lore and
 * fails the check.
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
     * Builds a fresh skip-theme feather with name and lore. A new
     * {@code ItemStack} is returned on every call — safe to give to multiple
     * players.
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

        List<String> userLore = lang.getList("game.ai.skip-item.lore");
        List<String> lore = new ArrayList<>(userLore.size());
        for (String line : userLore)
            lore.add(MessageUtils.translateColors(line));
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Returns {@code true} if the given item is one of our skip feathers.
     * Matches a FEATHER carrying any non-empty lore — see class Javadoc for
     * the threat-model rationale.
     * <p>
     * Safe to call with {@code null} or any random item.
     *
     * @param item the candidate item (may be {@code null})
     */
    public static boolean isSkipItem(ItemStack item) {
        if (item == null)
            return false;
        if (XMaterial.matchXMaterial(item.getType()) != XMaterial.FEATHER)
            return false;
        if (!item.hasItemMeta())
            return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore())
            return false;
        List<String> lore = meta.getLore();
        return lore != null && !lore.isEmpty();
    }
}
