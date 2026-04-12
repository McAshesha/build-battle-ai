package ru.ashesha.buildBattleAI.commands;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.core.message.ChatService;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class BBAICommandTest {

    private BuildBattleAI plugin;
    private PluginContext context;
    private BBAICommand command;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        context = mock(PluginContext.class);
        when(plugin.getContext()).thenReturn(context);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        command = new BBAICommand(plugin);
    }

    // ===== No arguments: plugin info =====

    @Test
    void noArgsShowsPluginInfo() {
        CommandSender sender = mock(CommandSender.class);
        PluginDescriptionFile desc = mock(PluginDescriptionFile.class);
        when(desc.getVersion()).thenReturn("1.0-SNAPSHOT");
        when(desc.getAuthors()).thenReturn(Collections.singletonList("McAshesha"));
        when(plugin.getDescription()).thenReturn(desc);

        command.onCommand(sender, null, "bbai", new String[0]);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender, times(5)).sendMessage(captor.capture());
        List<String> msgs = captor.getAllValues();
        assertTrue(msgs.get(0).contains("BuildBattleAI"));
        assertTrue(msgs.get(1).contains("1.0-SNAPSHOT"));
        assertTrue(msgs.get(2).contains("McAshesha"));
        assertTrue(msgs.get(3).contains("Enabled"));
        assertTrue(msgs.get(4).contains("/bbai demo"));
    }

    // ===== Demo validation =====

    @Test
    void demoFromConsoleSendsError() {
        CommandSender sender = mock(CommandSender.class);
        command.onCommand(sender, null, "bbai", new String[]{"demo"});
        verify(sender).sendMessage("\u00a7cDemo commands can only be used by a player.");
    }

    @Test
    void demoWithNullMessageServiceSendsError() {
        Player player = mock(Player.class);
        when(context.getMessageService()).thenReturn(null);
        command.onCommand(player, null, "bbai", new String[]{"demo"});
        verify(player).sendMessage("\u00a7cMessageService is not available.");
    }

    @Test
    void demoUnknownModeSendsError() {
        Player player = mock(Player.class);
        BBAIMessageService ms = mock(BBAIMessageService.class);
        when(context.getMessageService()).thenReturn(ms);
        command.onCommand(player, null, "bbai", new String[]{"demo", "invalid"});
        verify(player).sendMessage(contains("Unknown demo mode"));
    }

    @Test
    void demoCaseInsensitive() {
        CommandSender sender = mock(CommandSender.class);
        command.onCommand(sender, null, "bbai", new String[]{"DEMO"});
        verify(sender).sendMessage("\u00a7cDemo commands can only be used by a player.");
    }

    // ===== Demo modes =====

    @Test
    void demoChatSendsChatMessage() {
        Player player = mock(Player.class);
        BBAIMessageService ms = mock(BBAIMessageService.class);
        when(context.getMessageService()).thenReturn(ms);

        command.onCommand(player, null, "bbai", new String[]{"demo", "chat"});

        verify(ms).sendChat(eq(player), anyString());
        verify(player).sendMessage("\u00a7aChat demo sent.");
    }

    @Test
    void demoRichSendsRichMessage() {
        Player player = mock(Player.class);
        BBAIMessageService ms = mock(BBAIMessageService.class);
        when(context.getMessageService()).thenReturn(ms);

        command.onCommand(player, null, "bbai", new String[]{"demo", "rich"});

        verify(ms).sendChat(eq(player), any(ChatService.ChatMessage.class));
        verify(player).sendMessage("\u00a7aRich chat demo sent.");
    }

    @Test
    void demoBarSendsActionBar() {
        Player player = mock(Player.class);
        BBAIMessageService ms = mock(BBAIMessageService.class);
        when(context.getMessageService()).thenReturn(ms);

        command.onCommand(player, null, "bbai", new String[]{"demo", "bar"});

        verify(ms).sendActionBar(eq(player), anyString());
        verify(player).sendMessage("\u00a7aAction bar demo sent.");
    }

    @Test
    void demoTitleSendsTitle() {
        Player player = mock(Player.class);
        BBAIMessageService ms = mock(BBAIMessageService.class);
        when(context.getMessageService()).thenReturn(ms);

        command.onCommand(player, null, "bbai", new String[]{"demo", "title"});

        verify(ms).sendTitle(eq(player), anyString(), anyString(), anyInt(), anyInt(), anyInt());
        verify(player).sendMessage("\u00a7aTitle demo sent.");
    }

    @Test
    void demoTabSendsTabWithPlayerName() {
        Player player = mock(Player.class);
        BBAIMessageService ms = mock(BBAIMessageService.class);
        when(context.getMessageService()).thenReturn(ms);
        when(player.getName()).thenReturn("Steve");

        command.onCommand(player, null, "bbai", new String[]{"demo", "tab"});

        ArgumentCaptor<String> footer = ArgumentCaptor.forClass(String.class);
        verify(ms).sendTab(eq(player), anyString(), footer.capture());
        assertTrue(footer.getValue().contains("Steve"));
        verify(player).sendMessage("\u00a7aTab demo sent.");
    }

    @SuppressWarnings("unchecked")
    @Test
    void demoListNameSendsPlayerListName() {
        Player player = mock(Player.class);
        Server server = mock(Server.class);
        BBAIMessageService ms = mock(BBAIMessageService.class);
        when(context.getMessageService()).thenReturn(ms);
        when(player.getName()).thenReturn("Steve");
        when(player.getServer()).thenReturn(server);
        doReturn(Collections.<Player>emptyList()).when(server).getOnlinePlayers();

        command.onCommand(player, null, "bbai", new String[]{"demo", "listname"});

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(ms).sendPlayerListName(eq(player), name.capture(), any(Collection.class));
        assertTrue(name.getValue().contains("Steve"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void demoResetClearsTabAndListName() {
        Player player = mock(Player.class);
        Server server = mock(Server.class);
        BBAIMessageService ms = mock(BBAIMessageService.class);
        when(context.getMessageService()).thenReturn(ms);
        when(player.getServer()).thenReturn(server);
        doReturn(Collections.<Player>emptyList()).when(server).getOnlinePlayers();

        command.onCommand(player, null, "bbai", new String[]{"demo", "reset"});

        verify(ms).sendTab(player, "", "");
        verify(ms).sendPlayerListName(eq(player), isNull(String.class), any(Collection.class));
        verify(player).sendMessage("\u00a7aTab header/footer and player list name were reset.");
    }

    @SuppressWarnings("unchecked")
    @Test
    void demoAllRunsEveryDemo() {
        Player player = mock(Player.class);
        Server server = mock(Server.class);
        BBAIMessageService ms = mock(BBAIMessageService.class);
        when(context.getMessageService()).thenReturn(ms);
        when(player.getName()).thenReturn("Steve");
        when(player.getServer()).thenReturn(server);
        doReturn(Collections.<Player>emptyList()).when(server).getOnlinePlayers();

        command.onCommand(player, null, "bbai", new String[]{"demo", "all"});

        verify(ms).sendChat(eq(player), anyString());
        verify(ms).sendChat(eq(player), any(ChatService.ChatMessage.class));
        verify(ms).sendActionBar(eq(player), anyString());
        verify(ms).sendTitle(eq(player), anyString(), anyString(), anyInt(), anyInt(), anyInt());
        verify(ms).sendTab(eq(player), anyString(), anyString());
        verify(ms).sendPlayerListName(eq(player), anyString(), any(Collection.class));
        verify(player).sendMessage("\u00a7aAll messaging demos were sent.");
    }

    @SuppressWarnings("unchecked")
    @Test
    void demoDefaultModeIsAll() {
        Player player = mock(Player.class);
        Server server = mock(Server.class);
        BBAIMessageService ms = mock(BBAIMessageService.class);
        when(context.getMessageService()).thenReturn(ms);
        when(player.getName()).thenReturn("Steve");
        when(player.getServer()).thenReturn(server);
        doReturn(Collections.<Player>emptyList()).when(server).getOnlinePlayers();

        command.onCommand(player, null, "bbai", new String[]{"demo"});

        verify(player).sendMessage("\u00a7aAll messaging demos were sent.");
    }

    // ===== onCommand =====

    @Test
    void onCommandAlwaysReturnsTrue() {
        PluginDescriptionFile desc = mock(PluginDescriptionFile.class);
        when(desc.getVersion()).thenReturn("1.0");
        when(desc.getAuthors()).thenReturn(Collections.emptyList());
        when(plugin.getDescription()).thenReturn(desc);

        assertTrue(command.onCommand(mock(CommandSender.class), null, "bbai", new String[0]));
    }

    // ===== Tab completion =====

    @Test
    void suggestFirstArgReturnsDemoOnly() {
        List<String> result = command.onTabComplete(null, null, "bbai", new String[]{""});
        assertEquals(1, result.size());
        assertEquals("demo", result.get(0));
    }

    @Test
    void suggestSecondArgAfterDemoReturnsModes() {
        List<String> result = command.onTabComplete(null, null, "bbai", new String[]{"demo", ""});
        assertEquals(8, result.size());
        assertTrue(result.containsAll(
                Arrays.asList("all", "chat", "rich", "bar", "title", "tab", "listname", "reset")));
    }

    @Test
    void suggestSecondArgNotDemoReturnsEmpty() {
        List<String> result = command.onTabComplete(null, null, "bbai", new String[]{"other", ""});
        assertTrue(result.isEmpty());
    }

    @Test
    void suggestThirdArgReturnsEmpty() {
        List<String> result = command.onTabComplete(null, null, "bbai", new String[]{"demo", "chat", ""});
        assertTrue(result.isEmpty());
    }
}
