package ru.ashesha.buildBattleAI.commands.base;

import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.util.ReflectionUtils;

import java.util.Collections;
import java.util.List;

/**
 * Base class for all plugin commands. Extends Bukkit's {@link Command}
 * and registers dynamically via the server's {@link CommandMap},
 * eliminating the need to declare commands in {@code plugin.yml}.
 * <p>
 * Subclasses implement {@link #execute(CommandSender, String[])} for command logic
 * and {@link #suggest(CommandSender, String[])} for tab completion.
 * The command is registered at runtime through {@link #register()}, which
 * accesses the server's internal {@link CommandMap} via reflection.
 * This approach works on all CraftBukkit/Spigot/Paper versions from 1.8 to 1.21+.
 */
public abstract class PluginCommand extends Command {

    /**
     * Cached reference to the server's command map, obtained via reflection.
     * Package-private to allow test injection.
     */
    static CommandMap commandMap;

    /**
     * Reference to the plugin instance for accessing managers and server API.
     */
    @NonNull
    protected final BuildBattleAI plugin;

    /**
     * Creates a new command with the given name, description, and usage hint.
     *
     * @param plugin      the plugin instance
     * @param name        the command name (what players type after {@code /})
     * @param description a short description shown in {@code /help}
     * @param usage       usage hint (e.g. {@code "[args]"}); the {@code /name} prefix is added automatically
     */
    protected PluginCommand(@NonNull BuildBattleAI plugin, @NonNull String name,
                            @NonNull String description, @NonNull String usage) {
        super(name, description, "/" + name + (usage.isEmpty() ? "" : " " + usage),
                Collections.emptyList());
        this.plugin = plugin;
    }

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
    public final boolean execute(CommandSender sender, String label, String[] args) {
        execute(sender, args);
        return true;
    }

    /**
     * Delegates to {@link #suggest(CommandSender, String[])}.
     */
    @Override
    public final List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return suggest(sender, args);
    }

    /**
     * Registers this command with the server's {@link CommandMap}.
     * The command becomes available immediately without a server restart
     * and without needing a {@code plugin.yml} declaration.
     */
    public final void register() {
        getCommandMap().register(plugin.getName().toLowerCase(), this);
    }

    /**
     * Resolves the server's {@link CommandMap} via reflection, caching the result.
     * All CraftBukkit/Spigot/Paper implementations expose a {@code commandMap}
     * field on the server instance, making this reliable across 1.8–1.21+.
     *
     * @return the server command map
     * @throws RuntimeException if the field cannot be accessed
     */
    private static CommandMap getCommandMap() {
        if (commandMap == null)
            commandMap = ReflectionUtils.getFieldValue(Bukkit.getServer(), "commandMap");
        return commandMap;
    }
}
