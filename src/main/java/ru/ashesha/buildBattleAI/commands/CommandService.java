package ru.ashesha.buildBattleAI.commands;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.util.ReflectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Manages registration and unregistration of plugin commands via the server's
 * {@link CommandMap}. Accesses the map through reflection, which works on all
 * CraftBukkit/Spigot/Paper versions from 1.8 to 1.21+.
 * <p>
 * Commands are represented by the nested {@link PluginCommand} abstract class.
 * Subclass it to define command logic, then pass instances to
 * {@link #register(PluginCommand)} and {@link #unregister(PluginCommand)}.
 * <p>
 * Implements {@link PluginService} so the command service participates in the
 * uniform plugin lifecycle. {@link #enable()} resolves the server's
 * {@link CommandMap} and its internal {@code knownCommands} map reflectively,
 * so a reload produces a fresh binding even if the server swapped its command
 * map between cycles; {@link #shutdown()} bulk-unregisters every command this
 * service registered so no entries leak into Bukkit's command map after the
 * plugin is disabled.
 */
@RequiredArgsConstructor
public class CommandService implements PluginService {

    /**
     * The plugin instance, used as the command prefix namespace.
     */
    @NonNull
    private final BuildBattleAI plugin;

    /**
     * All commands registered through this service, for bulk unregistration on shutdown.
     */
    private final List<PluginCommand> registeredCommands = new ArrayList<>();

    /**
     * The server's command map, resolved reflectively in {@link #enable()}.
     * {@code null} before the first enable — {@link #register(PluginCommand)}
     * must not be called in that state.
     */
    private CommandMap commandMap;

    /**
     * The live map of registered command names inside the {@link CommandMap},
     * resolved reflectively in {@link #enable()}. Used to bulk-strip prefixed
     * aliases (e.g. {@code "pluginname:command"}) during unregistration.
     */
    private Map<String, Command> knownCommands;

    /**
     * Resolves the server's {@link CommandMap} and its internal
     * {@code knownCommands} map via reflection.
     * <p>
     * Reflection is deferred from the constructor to this method so the
     * service participates in the standard {@link PluginService} lifecycle —
     * on reload, {@code enable()} re-runs and rebinds both references, which
     * keeps the service correct even if something about the server's command
     * plumbing changed between cycles.
     *
     * @throws RuntimeException if the reflective lookup fails
     */
    @Override
    public void enable() {
        this.commandMap = ReflectionUtils.getFieldValue(Bukkit.getServer(), "commandMap");
        this.knownCommands = ReflectionUtils.getFieldValue(commandMap, "knownCommands");
    }

    /**
     * Registers a command with the server's {@link CommandMap}.
     * The command becomes available immediately without a server restart
     * and without needing a {@code plugin.yml} declaration.
     *
     * @param command the command to register
     */
    public void register(@NonNull PluginCommand command) {
        commandMap.register(plugin.getName().toLowerCase(), command);
        registeredCommands.add(command);
    }

    /**
     * Unregisters a command from the server's {@link CommandMap}.
     * Removes all entries in {@code knownCommands} that reference this command
     * (including prefixed aliases like {@code "pluginname:command"})
     * and marks the command as unregistered.
     *
     * @param command the command to unregister
     */
    public void unregister(@NonNull PluginCommand command) {
        knownCommands.values().removeIf(c -> c == command);
        command.unregister(commandMap);
        registeredCommands.remove(command);
    }

    /**
     * Unregisters all commands that were registered through this service.
     * Called during plugin shutdown. Touches only entries that this service
     * registered, so commands owned by other plugins remain intact.
     */
    @Override
    public void shutdown() {
        for (PluginCommand command : registeredCommands) {
            knownCommands.values().removeIf(c -> c == command);
            command.unregister(commandMap);
        }
        registeredCommands.clear();
    }

    /**
     * Base class for all plugin commands. Extends Bukkit's {@link Command}
     * and is registered dynamically via {@link CommandService#register(PluginCommand)},
     * eliminating the need to declare commands in {@code plugin.yml}.
     * <p>
     * Subclasses implement {@link #execute(CommandSender, String[])} for command logic
     * and {@link #suggest(CommandSender, String[])} for tab completion.
     */
    public static abstract class PluginCommand extends Command {

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
        public final boolean execute(@NonNull CommandSender sender, @NonNull String label, String[] args) {
            execute(sender, args);
            return true;
        }

        /**
         * Delegates to {@link #suggest(CommandSender, String[])}.
         */
        @Override
        public final @NonNull List<String> tabComplete(@NonNull CommandSender sender, @NonNull String alias, String[] args) {
            List<String> suggestions = suggest(sender, args);
            return suggestions == null ? Collections.emptyList() : suggestions;
        }
    }
}
