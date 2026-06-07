package ru.ashesha.buildBattleAI.commands;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.BuildBattleAI;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the delegation contract between {@link FlatSubcommand} and
 * {@link ArenaCommand}: the wrapper must forward execute/suggest calls to
 * the parent unchanged, prepending its own subcommand name so the parent's
 * argument shape matches a real {@code /bbai <sub> ...} invocation.
 */
class FlatSubcommandTest {

    private BuildBattleAI plugin;
    private ArenaCommand parent;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        parent = mock(ArenaCommand.class);
    }

    @Test
    void executePrependsSubcommandNameBeforeDelegating() {
        FlatSubcommand alias = new FlatSubcommand(plugin, parent, "join", "Join", "<arena>");
        CommandSender sender = mock(CommandSender.class);

        invokeExecute(alias, sender, new String[]{"arena1"});

        // Parent must see args[0] == "join" so its switch picks the right handler.
        verify(parent).dispatch(eq(sender), argThat(new String[]{"join", "arena1"}));
    }

    @Test
    void executeWorksWithEmptyArgs() {
        FlatSubcommand alias = new FlatSubcommand(plugin, parent, "leave", "Leave", "");
        CommandSender sender = mock(CommandSender.class);

        invokeExecute(alias, sender, new String[0]);

        verify(parent).dispatch(eq(sender), argThat(new String[]{"leave"}));
    }

    @Test
    void suggestPrependsSubcommandNameAndReturnsParentResult() {
        FlatSubcommand alias = new FlatSubcommand(plugin, parent, "lang", "Lang", "[code]");
        CommandSender sender = mock(CommandSender.class);
        List<String> langCodes = Arrays.asList("en", "ru");
        when(parent.dispatchSuggest(eq(sender), any(String[].class))).thenReturn(langCodes);

        List<String> result = invokeSuggest(alias, sender, new String[]{"e"});

        assertSame(langCodes, result);
        verify(parent).dispatchSuggest(eq(sender), argThat(new String[]{"lang", "e"}));
    }

    @Test
    void suggestNullResultIsNormalisedToEmptyByCommandBase() {
        // The base PluginCommand class promises tabComplete never returns
        // null. We verify the FlatSubcommand surface honours the same
        // contract by going through the public Bukkit entry point.
        FlatSubcommand alias = new FlatSubcommand(plugin, parent, "leave", "Leave", "");
        CommandSender sender = mock(CommandSender.class);
        when(parent.dispatchSuggest(eq(sender), any(String[].class))).thenReturn(null);

        List<String> result = alias.tabComplete(sender, "leave", new String[0]);

        assertEquals(Collections.emptyList(), result);
    }

    // ── reflection helpers ────────────────────────────────────────────────

    private static void invokeExecute(FlatSubcommand alias, CommandSender sender, String[] args) {
        try {
            Method m = CommandService.PluginCommand.class.getDeclaredMethod(
                    "execute", CommandSender.class, String[].class);
            m.setAccessible(true);
            m.invoke(alias, sender, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeSuggest(FlatSubcommand alias, CommandSender sender, String[] args) {
        try {
            Method m = CommandService.PluginCommand.class.getDeclaredMethod(
                    "suggest", CommandSender.class, String[].class);
            m.setAccessible(true);
            return (List<String>) m.invoke(alias, sender, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Mockito {@code ArgumentMatcher} for {@code String[]} that compares
     * by content rather than identity.
     */
    private static String[] argThat(String[] expected) {
        return org.mockito.ArgumentMatchers.argThat(actual -> {
            try {
                assertArrayEquals(expected, actual);
                return true;
            } catch (AssertionError e) {
                return false;
            }
        });
    }
}
