package ru.ashesha.buildBattleAI.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MessageUtils} — legacy {@code &}-coded text deserialization
 * into Adventure {@link Component} objects.
 */
class MessageUtilsTest {

    // ===== toComponent =====

    @Test
    void nullInputReturnsEmptyComponent() {
        Component result = MessageUtils.toComponent(null);
        assertNotNull(result);
        assertEquals(Component.empty(), result);
    }

    @Test
    void emptyStringReturnsEmptyComponent() {
        Component result = MessageUtils.toComponent("");
        assertNotNull(result);
        assertEquals(Component.empty(), result);
    }

    @Test
    void plainTextPreservedAsIs() {
        Component result = MessageUtils.toComponent("Hello World");
        String serialized = LegacyComponentSerializer.legacyAmpersand().serialize(result);
        assertEquals("Hello World", serialized);
    }

    @Test
    void colorCodesParsed() {
        Component result = MessageUtils.toComponent("&aGreen &cRed");
        // Re-serialize to verify codes are parsed and re-emitted correctly
        String serialized = LegacyComponentSerializer.legacyAmpersand().serialize(result);
        assertEquals("&aGreen &cRed", serialized);
    }

    @Test
    void formattingCodesParsed() {
        Component result = MessageUtils.toComponent("&lBold &oItalic");
        String serialized = LegacyComponentSerializer.legacyAmpersand().serialize(result);
        assertEquals("&lBold &oItalic", serialized);
    }

    @Test
    void resetCodeParsed() {
        Component result = MessageUtils.toComponent("&cRed&rReset");
        String serialized = LegacyComponentSerializer.legacyAmpersand().serialize(result);
        assertTrue(serialized.contains("&c"));
        assertTrue(serialized.contains("Red"));
        assertTrue(serialized.contains("Reset"));
    }

    @Test
    void multipleColorCodesInSequence() {
        Component result = MessageUtils.toComponent("&a&lBoldGreen");
        String serialized = LegacyComponentSerializer.legacyAmpersand().serialize(result);
        assertTrue(serialized.contains("&a"));
        assertTrue(serialized.contains("&l"));
        assertTrue(serialized.contains("BoldGreen"));
    }

    @Test
    void textWithoutColorCodesUnchanged() {
        Component result = MessageUtils.toComponent("No colors here");
        String serialized = LegacyComponentSerializer.legacyAmpersand().serialize(result);
        assertEquals("No colors here", serialized);
    }

    // ===== LEGACY serializer field =====

    @Test
    void legacySerializerIsNotNull() {
        assertNotNull(MessageUtils.LEGACY);
    }

    @Test
    void legacySerializerUsesAmpersandCharacter() {
        // Verify round-trip: serialize → deserialize → serialize preserves & codes
        Component original = MessageUtils.LEGACY.deserialize("&6Gold");
        String roundTripped = MessageUtils.LEGACY.serialize(original);
        assertEquals("&6Gold", roundTripped);
    }

    @Test
    void legacySerializerDoesNotUseSectionSign() {
        // § should be treated as literal text, not a color code
        Component result = MessageUtils.LEGACY.deserialize("§aNotGreen");
        String serialized = MessageUtils.LEGACY.serialize(result);
        // The § should be preserved literally, not interpreted as a color code
        assertTrue(serialized.contains("§a"));
    }
}
