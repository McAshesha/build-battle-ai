package ru.ashesha.buildBattleAI.commands.base;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.ashesha.buildBattleAI.BuildBattleAI;

import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginCommandBaseTest {

    private BuildBattleAI plugin;
    private Logger logger;
    private TestCommand command;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        command = new TestCommand(plugin, "test");
    }

    @Test
    void registerBindsExecutorAndTabCompleter() {
        org.bukkit.command.PluginCommand bukkitCmd = mock(org.bukkit.command.PluginCommand.class);
        when(plugin.getCommand("test")).thenReturn(bukkitCmd);

        command.register();

        verify(bukkitCmd).setExecutor(command);
        verify(bukkitCmd).setTabCompleter(command);
    }

    @Test
    void registerLogsWarningWhenCommandNotInPluginYml() {
        when(plugin.getCommand("test")).thenReturn(null);

        command.register();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(logger).warning(captor.capture());
        assertTrue(captor.getValue().contains("test"));
        assertTrue(captor.getValue().contains("not registered in plugin.yml"));
    }

    @Test
    void registerDoesNotBindWhenCommandNotInPluginYml() {
        when(plugin.getCommand("test")).thenReturn(null);

        command.register();

        // No PluginCommand mock was created, so nothing should be bound
        assertFalse(command.executed);
    }

    @Test
    void onCommandDelegatesToExecuteAndReturnsTrue() {
        boolean result = command.onCommand(mock(CommandSender.class), mock(Command.class), "test", new String[]{"a", "b"});
        assertTrue(result);
        assertTrue(command.executed);
    }

    @Test
    void onCommandAlwaysReturnsTrueRegardlessOfExecution() {
        assertTrue(command.onCommand(mock(CommandSender.class), mock(Command.class), "test", new String[0]));
    }

    @Test
    void onTabCompleteDelegatesToSuggest() {
        List<String> result = command.onTabComplete(mock(CommandSender.class), mock(Command.class), "test", new String[]{"x"});
        assertTrue(result.isEmpty());
        assertTrue(command.suggested);
    }

    @Test
    void executeReceivesCorrectArgs() {
        CommandSender sender = mock(CommandSender.class);
        command.onCommand(sender, mock(Command.class), "test", new String[]{"arg1", "arg2"});
        assertArrayEquals(new String[]{"arg1", "arg2"}, command.lastArgs);
    }

    /**
     * Minimal concrete subclass for testing the abstract PluginCommand.
     */
    static class TestCommand extends PluginCommand {

        boolean executed;
        boolean suggested;
        String[] lastArgs;

        TestCommand(BuildBattleAI plugin, String name) {
            super(plugin, name);
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
