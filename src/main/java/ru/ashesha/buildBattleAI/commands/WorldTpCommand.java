package ru.ashesha.buildBattleAI.commands;

import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dev-only command {@code /worldtp <world>} for jumping between worlds the
 * user has dropped into the server folder. Loads the world via
 * {@link WorldCreator} on demand if it isn't already loaded.
 */
public class WorldTpCommand extends CommandService.PluginCommand {

    public WorldTpCommand(@NonNull BuildBattleAI plugin) {
        super(plugin, "worldtp", "Teleport to another world", "<world>");
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players.");
            return;
        }
        Player player = (Player) sender;

        if (args.length == 0) {
            plugin.getContext().getMessageService().sendChat(player,
                    "&7Available worlds: &f" + String.join(", ", availableWorlds()));
            plugin.getContext().getMessageService().sendChat(player,
                    "&7Usage: &f/worldtp <world>");
            return;
        }

        String name = args[0];
        World world = Bukkit.getWorld(name);
        if (world == null) {
            // Try to load from disk if a folder with that name exists in the
            // server root.
            File folder = new File(Bukkit.getWorldContainer(), name);
            if (!folder.isDirectory() || !new File(folder, "level.dat").isFile()) {
                plugin.getContext().getMessageService().sendChat(player,
                        "&cWorld '" + name + "' not found.");
                return;
            }
            world = new WorldCreator(name).createWorld();
            if (world == null) {
                plugin.getContext().getMessageService().sendChat(player,
                        "&cFailed to load world '" + name + "'.");
                return;
            }
        }

        Location target = world.getSpawnLocation();
        player.teleport(target);
        plugin.getContext().getMessageService().sendChat(player,
                "&aTeleported to &f" + world.getName());
    }

    @Override
    protected List<String> suggest(CommandSender sender, String[] args) {
        if (args.length != 1)
            return Collections.emptyList();
        String prefix = args[0].toLowerCase();
        List<String> out = new ArrayList<String>();
        for (String name : availableWorlds())
            if (name.toLowerCase().startsWith(prefix))
                out.add(name);
        return out;
    }

    /**
     * Union of currently-loaded worlds and on-disk world folders in the
     * server root (anything with a {@code level.dat} file).
     */
    private Set<String> availableWorlds() {
        Set<String> names = new LinkedHashSet<String>();
        for (World w : Bukkit.getWorlds())
            names.add(w.getName());
        File container = Bukkit.getWorldContainer();
        File[] entries = container.listFiles();
        if (entries != null) {
            for (File f : entries)
                if (f.isDirectory() && new File(f, "level.dat").isFile())
                    names.add(f.getName());
        }
        return names;
    }
}
