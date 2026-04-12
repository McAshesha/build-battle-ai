package ru.ashesha.buildBattleAI.core.message;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.core.message.ChatService.ChatMessage;
import ru.ashesha.buildBattleAI.core.message.ChatService.ClickAction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ChatMessage} and {@link ClickAction}.
 * <p>
 * Since {@code ChatMessage.getSegments()} is private, segment content
 * is verified indirectly through {@code BBAICommandTest} integration tests.
 * These tests cover construction, fluent API, null safety, and the enum.
 */
class ChatMessageTest {

    // ===== ChatMessage construction =====

    @Test
    void newChatMessageIsCreatedWithoutError() {
        assertNotNull(new ChatMessage());
    }

    @Test
    void appendPlainTextReturnsSameInstance() {
        ChatMessage message = new ChatMessage();
        assertSame(message, message.append("text"));
    }

    @Test
    void appendWithHoverReturnsSameInstance() {
        ChatMessage message = new ChatMessage();
        assertSame(message, message.append("text", "hover"));
    }

    @Test
    void appendWithClickReturnsSameInstance() {
        ChatMessage message = new ChatMessage();
        assertSame(message, message.append("text", ClickAction.RUN_COMMAND, "/cmd"));
    }

    @Test
    void appendFullReturnsSameInstance() {
        ChatMessage message = new ChatMessage();
        assertSame(message, message.append("text", ClickAction.RUN_COMMAND, "/cmd", "hover"));
    }

    @Test
    void chainingMultipleAppendsWorks() {
        ChatMessage message = new ChatMessage()
                .append("Part1")
                .append("Part2", "hover")
                .append("Part3", ClickAction.OPEN_URL, "https://example.com")
                .append("Part4", ClickAction.SUGGEST_COMMAND, "/help", "tooltip");
        assertNotNull(message);
    }

    // ===== Null safety =====

    @Test
    void appendNullTextThrows() {
        ChatMessage message = new ChatMessage();
        assertThrows(NullPointerException.class, () -> message.append(null));
    }

    @Test
    void appendFullNullTextThrows() {
        ChatMessage message = new ChatMessage();
        assertThrows(NullPointerException.class, () -> message.append(null, ClickAction.OPEN_URL, "url", "hover"));
    }

    @Test
    void appendWithNullClickActionDoesNotThrow() {
        ChatMessage message = new ChatMessage();
        assertDoesNotThrow(() -> message.append("text", null, null, null));
    }

    // ===== ClickAction enum =====

    @Test
    void clickActionValuesExist() {
        ClickAction[] values = ClickAction.values();
        assertEquals(3, values.length);
        assertEquals(ClickAction.OPEN_URL, ClickAction.valueOf("OPEN_URL"));
        assertEquals(ClickAction.RUN_COMMAND, ClickAction.valueOf("RUN_COMMAND"));
        assertEquals(ClickAction.SUGGEST_COMMAND, ClickAction.valueOf("SUGGEST_COMMAND"));
    }
}
