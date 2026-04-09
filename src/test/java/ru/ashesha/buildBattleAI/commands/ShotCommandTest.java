package ru.ashesha.buildBattleAI.commands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.BuildBattleAI;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShotCommandTest {

    private BuildBattleAI plugin;
    private ShotCommand command;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        command = new ShotCommand(plugin);
    }

    // ===== Sender validation =====

    @Test
    void nonPlayerSenderGetsError() {
        CommandSender sender = mock(CommandSender.class);
        command.onCommand(sender, null, "shot", new String[]{"test"});
        verify(sender).sendMessage("\u00a7cThis command can only be used by a player.");
    }

    // ===== Argument validation =====

    @Test
    void noArgsSendsUsage() {
        Player player = mock(Player.class);
        command.onCommand(player, null, "shot", new String[0]);
        verify(player).sendMessage("\u00a7cUsage: /shot <fileName>");
    }

    // ===== Filename validation =====

    @Test
    void filenameWithDotsGetsError() {
        Player player = mock(Player.class);
        command.onCommand(player, null, "shot", new String[]{"test.png"});
        verify(player).sendMessage("\u00a7cFile name must contain only letters, digits, underscores, and hyphens.");
    }

    @Test
    void filenameWithSlashGetsError() {
        Player player = mock(Player.class);
        command.onCommand(player, null, "shot", new String[]{"../secret"});
        verify(player).sendMessage("\u00a7cFile name must contain only letters, digits, underscores, and hyphens.");
    }

    @Test
    void filenameWithSpecialCharsGetsError() {
        Player player = mock(Player.class);
        command.onCommand(player, null, "shot", new String[]{"test@file"});
        verify(player).sendMessage("\u00a7cFile name must contain only letters, digits, underscores, and hyphens.");
    }

    @Test
    void emptyFilenameGetsError() {
        Player player = mock(Player.class);
        command.onCommand(player, null, "shot", new String[]{""});
        verify(player).sendMessage("\u00a7cFile name must contain only letters, digits, underscores, and hyphens.");
    }

    @Test
    void filenameWithSpacesGetsError() {
        Player player = mock(Player.class);
        command.onCommand(player, null, "shot", new String[]{"my file"});
        verify(player).sendMessage("\u00a7cFile name must contain only letters, digits, underscores, and hyphens.");
    }

    // ===== Tab completion =====

    @Test
    void suggestFirstArgReturnsScreenshot() {
        List<String> result = command.onTabComplete(null, null, "shot", new String[]{""});
        assertEquals(1, result.size());
        assertEquals("screenshot", result.get(0));
    }

    @Test
    void suggestSecondArgReturnsEmpty() {
        List<String> result = command.onTabComplete(null, null, "shot", new String[]{"test", ""});
        assertTrue(result.isEmpty());
    }
}
