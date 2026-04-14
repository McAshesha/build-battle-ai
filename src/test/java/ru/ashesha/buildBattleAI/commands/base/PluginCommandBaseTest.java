package ru.ashesha.buildBattleAI.commands.base;

import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.BuildBattleAI;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginCommandBaseTest {

    private BuildBattleAI plugin;
    private CommandMap commandMap;
    private TestCommand command;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        when(plugin.getName()).thenReturn("BuildBattleAI");
        commandMap = mock(CommandMap.class);
        PluginCommand.commandMap = commandMap;
        command = new TestCommand(plugin, "test");
    }

    @AfterEach
    void tearDown() {
        PluginCommand.commandMap = null;
    }

    @Test
    void registerAddsCommandToCommandMap() {
        command.register();
        verify(commandMap).register("buildbattleai", command);
    }

    @Test
    void executeDelegatesToAbstractExecuteAndReturnsTrue() {
        boolean result = command.execute(mock(CommandSender.class), "test", new String[]{"a", "b"});
        assertTrue(result);
        assertTrue(command.executed);
    }

    @Test
    void executeAlwaysReturnsTrue() {
        assertTrue(command.execute(mock(CommandSender.class), "test", new String[0]));
    }

    @Test
    void tabCompleteDelegatesToSuggest() {
        List<String> result = command.tabComplete(mock(CommandSender.class), "test", new String[]{"x"});
        assertTrue(result.isEmpty());
        assertTrue(command.suggested);
    }

    @Test
    void executeReceivesCorrectArgs() {
        CommandSender sender = mock(CommandSender.class);
        command.execute(sender, "test", new String[]{"arg1", "arg2"});
        assertArrayEquals(new String[]{"arg1", "arg2"}, command.lastArgs);
    }

    @Test
    void commandHasCorrectDescription() {
        assertEquals("test description", command.getDescription());
    }

    @Test
    void commandHasCorrectUsage() {
        assertEquals("/test [args]", command.getUsage());
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
