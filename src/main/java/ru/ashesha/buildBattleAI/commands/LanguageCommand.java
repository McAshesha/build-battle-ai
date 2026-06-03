package ru.ashesha.buildBattleAI.commands;

import lombok.NonNull;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.config.api.Lang;
import ru.ashesha.buildBattleAI.data.api.BBAIDataService;
import ru.ashesha.buildBattleAI.data.api.PlayerData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Player command {@code /bbailang [code]} — switches the executing player's
 * UI language. Persists the choice via {@link BBAIDataService} so it survives
 * relogs and server restarts.
 * <p>
 * Without arguments, prints the current language + the list of available
 * languages so the player knows what to type next.
 */
public class LanguageCommand extends CommandService.PluginCommand {

    /**
     * Creates the command. The {@code "bbailang"} command name is registered
     * dynamically by the {@link CommandService} — no {@code plugin.yml} entry
     * is required.
     */
    public LanguageCommand(@NonNull BuildBattleAI plugin) {
        super(plugin, "bbailang", "Switch your in-game language", "[code]");
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players.");
            return;
        }
        Player player = (Player) sender;
        BBAIConfigService cfg = plugin.getContext().getConfigService();
        // Resolve the current per-player lang so the response is shown in
        // whatever language the player CURRENTLY uses — feels natural even
        // when they are about to switch away.
        Lang lang = cfg.getLangFor(player.getUniqueId());
        Set<String> available = cfg.getAvailableLangs();

        if (args.length == 0) {
            // List mode: show current + available codes.
            String current = currentLangNameFor(player);
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("lang.current", "%lang%", current));
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("lang.available",
                            "%list%", String.join(", ", available)));
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("lang.usage"));
            return;
        }

        String requested = args[0].toLowerCase();
        if (!available.contains(requested)) {
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("lang.unknown",
                            "%lang%", requested,
                            "%list%", String.join(", ", available)));
            return;
        }

        // Persist the choice. When DataService is disabled (data.enabled=false),
        // log a warning and skip persistence — the in-memory lookup will still
        // fall through to the default, so the player won't see broken text.
        BBAIDataService data = plugin.getContext().getDataService();
        if (!data.isEnabled()) {
            plugin.getContext().getMessageService().sendChat(player,
                    lang.get("lang.disabled"));
            return;
        }
        PlayerData pd = data.getOrCreatePlayer(player.getUniqueId(), player.getName());
        pd.language(requested);
        data.savePlayer(pd);

        // After saving, fetch the new lang to confirm in THE NEW language.
        Lang newLang = cfg.getLangFor(player.getUniqueId());
        plugin.getContext().getMessageService().sendChat(player,
                newLang.get("lang.switched", "%lang%", requested));
    }

    @Override
    protected List<String> suggest(CommandSender sender, String[] args) {
        if (args.length != 1)
            return Collections.emptyList();
        String prefix = args[0].toLowerCase();
        List<String> out = new ArrayList<String>();
        for (String name : plugin.getContext().getConfigService().getAvailableLangs())
            if (name.startsWith(prefix))
                out.add(name);
        return out;
    }

    /**
     * Returns the player's currently-stored language code, or the default
     * language name when the player has not set a preference.
     */
    private String currentLangNameFor(Player player) {
        BBAIDataService data = plugin.getContext().getDataService();
        if (data.isEnabled()) {
            PlayerData pd = data.getPlayer(player.getUniqueId());
            if (pd != null && pd.language() != null && !pd.language().isEmpty())
                return pd.language();
        }
        Lang def = plugin.getContext().getConfigService().getDefaultLang();
        return def != null ? def.name() : "?";
    }
}
