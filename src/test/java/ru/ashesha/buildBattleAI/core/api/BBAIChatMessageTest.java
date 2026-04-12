package ru.ashesha.buildBattleAI.core.api;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.core.api.BBAIChatMessage.ClickAction;
import ru.ashesha.buildBattleAI.core.api.BBAIChatMessage.Segment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BBAIChatMessageTest {

    // ===== Builder & of() =====

    @Test
    void ofCreatesSingleSegmentMessage() {
        BBAIChatMessage msg = BBAIChatMessage.of("Hello");
        assertEquals(1, msg.getSegments().size());
        assertEquals("Hello", msg.getSegments().get(0).getText());
        assertNull(msg.getSegments().get(0).getClickAction());
        assertNull(msg.getSegments().get(0).getClickValue());
        assertNull(msg.getSegments().get(0).getHoverText());
    }

    @Test
    void builderAppendsMultipleSegments() {
        BBAIChatMessage msg = BBAIChatMessage.builder()
                .append("Part1")
                .append("Part2")
                .append("Part3")
                .build();
        assertEquals(3, msg.getSegments().size());
        assertEquals("Part1", msg.getSegments().get(0).getText());
        assertEquals("Part2", msg.getSegments().get(1).getText());
        assertEquals("Part3", msg.getSegments().get(2).getText());
    }

    @Test
    void builderAppendWithHoverText() {
        BBAIChatMessage msg = BBAIChatMessage.builder()
                .append("Click me", "Tooltip here")
                .build();
        Segment seg = msg.getSegments().get(0);
        assertEquals("Click me", seg.getText());
        assertNull(seg.getClickAction());
        assertNull(seg.getClickValue());
        assertEquals("Tooltip here", seg.getHoverText());
    }

    @Test
    void builderAppendWithClickAction() {
        BBAIChatMessage msg = BBAIChatMessage.builder()
                .append("Link", ClickAction.OPEN_URL, "https://example.com")
                .build();
        Segment seg = msg.getSegments().get(0);
        assertEquals("Link", seg.getText());
        assertEquals(ClickAction.OPEN_URL, seg.getClickAction());
        assertEquals("https://example.com", seg.getClickValue());
        assertNull(seg.getHoverText());
    }

    @Test
    void builderAppendFullSegment() {
        BBAIChatMessage msg = BBAIChatMessage.builder()
                .append("Run cmd", ClickAction.RUN_COMMAND, "/tp 0 0 0", "Teleport to origin")
                .build();
        Segment seg = msg.getSegments().get(0);
        assertEquals("Run cmd", seg.getText());
        assertEquals(ClickAction.RUN_COMMAND, seg.getClickAction());
        assertEquals("/tp 0 0 0", seg.getClickValue());
        assertEquals("Teleport to origin", seg.getHoverText());
    }

    @Test
    void builderAppendWithSuggestCommand() {
        BBAIChatMessage msg = BBAIChatMessage.builder()
                .append("Suggest", ClickAction.SUGGEST_COMMAND, "/help")
                .build();
        assertEquals(ClickAction.SUGGEST_COMMAND, msg.getSegments().get(0).getClickAction());
    }

    @Test
    void builderProducesEmptyMessageWithNoSegments() {
        BBAIChatMessage msg = BBAIChatMessage.builder().build();
        assertTrue(msg.getSegments().isEmpty());
    }

    // ===== Immutability =====

    @Test
    void segmentsListIsUnmodifiable() {
        BBAIChatMessage msg = BBAIChatMessage.of("test");
        List<Segment> segments = msg.getSegments();
        assertThrows(UnsupportedOperationException.class, () -> segments.add(
                BBAIChatMessage.builder().append("hack").build().getSegments().get(0)
        ));
    }

    @Test
    void segmentsListIsUnmodifiableRemove() {
        BBAIChatMessage msg = BBAIChatMessage.of("test");
        assertThrows(UnsupportedOperationException.class, () -> msg.getSegments().remove(0));
    }

    // ===== Equality =====

    @Test
    void equalMessagesAreEqual() {
        BBAIChatMessage a = BBAIChatMessage.of("Hello");
        BBAIChatMessage b = BBAIChatMessage.of("Hello");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentMessagesAreNotEqual() {
        BBAIChatMessage a = BBAIChatMessage.of("Hello");
        BBAIChatMessage b = BBAIChatMessage.of("World");
        assertNotEquals(a, b);
    }

    @Test
    void complexEqualMessages() {
        BBAIChatMessage a = BBAIChatMessage.builder()
                .append("text", ClickAction.OPEN_URL, "url", "hover")
                .build();
        BBAIChatMessage b = BBAIChatMessage.builder()
                .append("text", ClickAction.OPEN_URL, "url", "hover")
                .build();
        assertEquals(a, b);
    }

    @Test
    void segmentEquality() {
        BBAIChatMessage msg1 = BBAIChatMessage.builder()
                .append("t", ClickAction.RUN_COMMAND, "v", "h")
                .build();
        BBAIChatMessage msg2 = BBAIChatMessage.builder()
                .append("t", ClickAction.RUN_COMMAND, "v", "h")
                .build();
        assertEquals(msg1.getSegments().get(0), msg2.getSegments().get(0));
    }

    // ===== ClickAction enum coverage =====

    @Test
    void clickActionValuesExist() {
        ClickAction[] values = ClickAction.values();
        assertEquals(3, values.length);
        assertEquals(ClickAction.OPEN_URL, ClickAction.valueOf("OPEN_URL"));
        assertEquals(ClickAction.RUN_COMMAND, ClickAction.valueOf("RUN_COMMAND"));
        assertEquals(ClickAction.SUGGEST_COMMAND, ClickAction.valueOf("SUGGEST_COMMAND"));
    }
}
