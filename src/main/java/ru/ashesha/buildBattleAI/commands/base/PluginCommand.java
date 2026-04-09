package ru.ashesha.buildBattleAI.commands.base;

import lombok.RequiredArgsConstructor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import ru.ashesha.buildBattleAI.BuildBattleAI;

import java.util.List;

/**
 * Base class for all plugin commands. Wraps Bukkit's {@link CommandExecutor}
 * and {@link TabCompleter} into a single abstract class with simplified
 * method signatures and automatic registration via {@link #register()}.
 * <p>
 * Subclasses implement {@link #execute(CommandSender, String[])} for command logic
 * and {@link #suggest(CommandSender, String[])} for tab completion.
 * Commands must also be declared in {@code plugin.yml} for Bukkit registration to succeed.
 */
@RequiredArgsConstructor
public abstract class PluginCommand implements CommandExecutor, TabCompleter {

    /** Reference to the plugin instance for accessing managers and server API. */
    protected final BuildBattleAI plugin;

    /** The command name as declared in plugin.yml. */
    private final String name;

    /**
     * Executes the command logic.
     *
     * @param sender the entity or console that issued the command
     * @param args   the command arguments (excluding the command name itself)
     */
    protected abstract void execute(CommandSender sender, String[] args);

    /**
     * Provides tab-completion suggestions for the command.
     *
     * @param sender the entity or console requesting completions
     * @param args   the arguments typed so far
     * @return a list of suggested completions, or an empty list
     */
    protected abstract List<String> suggest(CommandSender sender, String[] args);

    /**
     * Delegates to {@link #execute(CommandSender, String[])} and always returns {@code true}
     * to suppress Bukkit's default usage message.
     */
    @Override
    public final boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        execute(sender, args);
        return true;
    }

    /** Delegates to {@link #suggest(CommandSender, String[])}. */
    @Override
    public final List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return suggest(sender, args);
    }

    /**
     * Registers this command with Bukkit by looking up the command name in {@code plugin.yml}
     * and binding this instance as both the executor and tab completer.
     * Logs a warning if the command is not declared in {@code plugin.yml}.
     */
    public final void register() {
        org.bukkit.command.PluginCommand bukkitCommand = plugin.getCommand(name);
        if (bukkitCommand == null) {
            plugin.getLogger().warning("Command '" + name + "' is not registered in plugin.yml!");
            return;
        }
        bukkitCommand.setExecutor(this);
        bukkitCommand.setTabCompleter(this);
    }
}
