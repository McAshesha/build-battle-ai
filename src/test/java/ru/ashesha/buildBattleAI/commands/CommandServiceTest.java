package ru.ashesha.buildBattleAI.commands;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.commands.CommandService.PluginCommand;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.util.ReflectionUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CommandService}.
 * <p>
 * {@link CommandService} has only the Lombok-generated single-arg constructor;
 * its {@link CommandMap} and {@code knownCommands} references are resolved
 * reflectively inside {@link CommandService#enable()}. Tests therefore stub
 * {@link Bukkit#getServer()} and {@link ReflectionUtils#getFieldValue} with
 * {@code MockedStatic} so {@code enable()} can run without a live server.
 */
class CommandServiceTest {

    private BuildBattleAI plugin;
    private CommandMap commandMap;
    private Map<String, Command> knownCommands;
    private CommandService service;

    /**
     * Builds a {@link CommandService} wired to a mock command map and known-commands
     * map by stubbing the statics used inside {@link CommandService#enable()} just
     * long enough to run the method. Once {@code enable()} returns, the references
     * are captured on the service instance, so the statics can be torn down
     * immediately — tests then exercise the fully-configured service without needing
     * any further static mocking.
     */
    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getName()).thenReturn("BuildBattleAI");
        commandMap = mock(CommandMap.class);
        knownCommands = new HashMap<>();

        Server server = mock(Server.class);
        try (MockedStatic<Bukkit> bukkitStatic = mockStatic(Bukkit.class);
             MockedStatic<ReflectionUtils> reflectionStatic = mockStatic(ReflectionUtils.class)) {
            bukkitStatic.when(Bukkit::getServer).thenReturn(server);
            reflectionStatic.when(() -> ReflectionUtils.getFieldValue(server, "commandMap"))
                    .thenReturn(commandMap);
            reflectionStatic.when(() -> ReflectionUtils.getFieldValue(commandMap, "knownCommands"))
                    .thenReturn(knownCommands);

            service = new CommandService(plugin);
            service.enable();
        }
    }

    @Test
    void registerDelegatesToCommandMap() {
        TestCommand cmd = new TestCommand(plugin, "test");
        service.register(cmd);
        verify(commandMap).register("buildbattleai", cmd);
    }

    @Test
    void unregisterRemovesFromKnownCommands() {
        TestCommand cmd = new TestCommand(plugin, "test");
        knownCommands.put("test", cmd);
        knownCommands.put("buildbattleai:test", cmd);

        service.register(cmd);
        service.unregister(cmd);

        assertFalse(knownCommands.containsValue(cmd));
    }

    @Test
    void shutdownUnregistersAllCommands() {
        TestCommand cmd1 = new TestCommand(plugin, "one");
        TestCommand cmd2 = new TestCommand(plugin, "two");
        knownCommands.put("one", cmd1);
        knownCommands.put("two", cmd2);

        service.register(cmd1);
        service.register(cmd2);
        service.shutdown();

        assertFalse(knownCommands.containsValue(cmd1));
        assertFalse(knownCommands.containsValue(cmd2));
    }

    @Test
    void enableResolvesCommandMapAndKnownCommandsReflectively() {
        // Re-running enable on a fresh instance must re-bind both reflected
        // references — this is what makes a reload (shutdown+enable) produce
        // a correct service even if the server swapped its command map
        // between cycles.
        Server server = mock(Server.class);
        CommandMap newMap = mock(CommandMap.class);
        Map<String, Command> newKnown = new HashMap<>();

        try (MockedStatic<Bukkit> bukkitStatic = mockStatic(Bukkit.class);
             MockedStatic<ReflectionUtils> reflectionStatic = mockStatic(ReflectionUtils.class)) {
            bukkitStatic.when(Bukkit::getServer).thenReturn(server);
            reflectionStatic.when(() -> ReflectionUtils.getFieldValue(server, "commandMap"))
                    .thenReturn(newMap);
            reflectionStatic.when(() -> ReflectionUtils.getFieldValue(newMap, "knownCommands"))
                    .thenReturn(newKnown);

            CommandService fresh = new CommandService(plugin);
            fresh.enable();

            TestCommand cmd = new TestCommand(plugin, "rebind");
            fresh.register(cmd);
            // Register delegated to the newly-resolved map, not the old one.
            verify(newMap).register("buildbattleai", cmd);
        }
    }

    @Test
    void executeDelegatesToAbstractExecuteAndReturnsTrue() {
        TestCommand cmd = new TestCommand(plugin, "test");
        boolean result = cmd.execute(mock(CommandSender.class), "test", new String[]{"a", "b"});
        assertTrue(result);
        assertTrue(cmd.executed);
    }

    @Test
    void executeAlwaysReturnsTrue() {
        TestCommand cmd = new TestCommand(plugin, "test");
        assertTrue(cmd.execute(mock(CommandSender.class), "test", new String[0]));
    }

    @Test
    void tabCompleteDelegatesToSuggest() {
        TestCommand cmd = new TestCommand(plugin, "test");
        List<String> result = cmd.tabComplete(mock(CommandSender.class), "test", new String[]{"x"});
        assertTrue(result.isEmpty());
        assertTrue(cmd.suggested);
    }

    @Test
    void executeReceivesCorrectArgs() {
        TestCommand cmd = new TestCommand(plugin, "test");
        cmd.execute(mock(CommandSender.class), "test", new String[]{"arg1", "arg2"});
        assertArrayEquals(new String[]{"arg1", "arg2"}, cmd.lastArgs);
    }

    @Test
    void commandHasCorrectDescription() {
        TestCommand cmd = new TestCommand(plugin, "test");
        assertEquals("test description", cmd.getDescription());
    }

    @Test
    void commandHasCorrectUsage() {
        TestCommand cmd = new TestCommand(plugin, "test");
        assertEquals("/test [args]", cmd.getUsage());
    }

    @Test
    void commandWithEmptyUsageOmitsTrailingSpace() {
        TestCommand noUsage = new TestCommand(plugin, "cmd", "", "");
        assertEquals("/cmd", noUsage.getUsage());
    }

    /**
     * Minimal concrete subclass for testing the abstract PluginCommand.
     */
    static class TestCommand extends PluginCommand {

        boolean executed;
        boolean suggested;
        String[] lastArgs;

        TestCommand(BuildBattleAI plugin, String name) {
            super(plugin, name, "test description", "[args]");
        }

        TestCommand(BuildBattleAI plugin, String name, String description, String usage) {
            super(plugin, name, description, usage);
        }

        @Override
        protected void execute(CommandSender sender, String[] args) {
            executed = true;
            lastArgs = args;
        }

        @Override
        protected List<String> suggest(CommandSender sender, String[] args) {
            suggested = true;
            return Collections.emptyList();
        }
    }
}
