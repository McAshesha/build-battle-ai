package ru.ashesha.buildBattleAI.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MessageUtils} — centralized legacy color code handling,
 * component serialization, and legacy text splitting utilities.
 */
class MessageUtilsTest {

    // ===== toColorComponent =====

    @Test
    void toColorComponentNullReturnsEmpty() {
        Component result = MessageUtils.toColorComponent(null);
        assertNotNull(result);
        assertEquals(Component.empty(), result);
    }

    @Test
    void toColorComponentEmptyString() {
        Component result = MessageUtils.toColorComponent("");
        assertNotNull(result);
        assertEquals(Component.empty(), result);
    }

    @Test
    void toColorComponentPlainText() {
        Component result = MessageUtils.toColorComponent("Hello World");
        String serialized = LegacyComponentSerializer.legacyAmpersand().serialize(result);
        assertEquals("Hello World", serialized);
    }

    @Test
    void toColorComponentColorCodes() {
        Component result = MessageUtils.toColorComponent("&aGreen &cRed");
        String serialized = LegacyComponentSerializer.legacyAmpersand().serialize(result);
        assertEquals("&aGreen &cRed", serialized);
    }

    @Test
    void toColorComponentFormattingCodes() {
        Component result = MessageUtils.toColorComponent("&lBold &oItalic");
        String serialized = LegacyComponentSerializer.legacyAmpersand().serialize(result);
        assertEquals("&lBold &oItalic", serialized);
    }

    @Test
    void toColorComponentResetCode() {
        Component result = MessageUtils.toColorComponent("&cRed&rReset");
        String serialized = LegacyComponentSerializer.legacyAmpersand().serialize(result);
        assertTrue(serialized.contains("&c"));
        assertTrue(serialized.contains("Red"));
        assertTrue(serialized.contains("Reset"));
    }

    @Test
    void toColorComponentMultipleCodesInSequence() {
        Component result = MessageUtils.toColorComponent("&a&lBoldGreen");
        String serialized = LegacyComponentSerializer.legacyAmpersand().serialize(result);
        assertTrue(serialized.contains("&a"));
        assertTrue(serialized.contains("&l"));
        assertTrue(serialized.contains("BoldGreen"));
    }

    @Test
    void toColorComponentNoColorCodes() {
        Component result = MessageUtils.toColorComponent("No colors here");
        String serialized = LegacyComponentSerializer.legacyAmpersand().serialize(result);
        assertEquals("No colors here", serialized);
    }

    @Test
    void toColorComponentDoesNotInterpretSectionSign() {
        // § should be treated as literal text by the ampersand serializer
        Component result = MessageUtils.toColorComponent("\u00a7aNotGreen");
        String serialized = LegacyComponentSerializer.legacyAmpersand().serialize(result);
        assertTrue(serialized.contains("\u00a7a"));
    }

    // ===== translateColors =====

    @Test
    void translateColorsConvertsAmpersandCodes() {
        assertEquals("\u00a7aGreen \u00a7bAqua", MessageUtils.translateColors("&aGreen &bAqua"));
    }

    @Test
    void translateColorsHandlesFormatCodes() {
        assertEquals("\u00a7lBold \u00a7oItalic", MessageUtils.translateColors("&lBold &oItalic"));
    }

    @Test
    void translateColorsIgnoresInvalidCodes() {
        assertEquals("&xInvalid &zTest", MessageUtils.translateColors("&xInvalid &zTest"));
    }

    @Test
    void translateColorsHandlesUpperCaseCodes() {
        assertEquals("\u00a7AGreen \u00a7BBold", MessageUtils.translateColors("&AGreen &BBold"));
    }

    @Test
    void translateColorsEmptyString() {
        assertEquals("", MessageUtils.translateColors(""));
    }

    @Test
    void translateColorsNull() {
        assertEquals("", MessageUtils.translateColors(null));
    }

    @Test
    void translateColorsNoAmpersand() {
        assertEquals("Plain text", MessageUtils.translateColors("Plain text"));
    }

    @Test
    void translateColorsTrailingAmpersand() {
        assertEquals("Text&", MessageUtils.translateColors("Text&"));
    }

    @Test
    void translateColorsResetCode() {
        assertEquals("\u00a7rReset", MessageUtils.translateColors("&rReset"));
    }

    @Test
    void translateColorsFastPathReturnsOriginalInstance() {
        String input = "No ampersands here";
        assertSame(input, MessageUtils.translateColors(input));
    }

    // ===== toSectionColorComponent =====

    @Test
    void toSectionColorComponentNull() {
        assertEquals(Component.empty(), MessageUtils.toSectionColorComponent(null));
    }

    @Test
    void toSectionColorComponentDeserializesSectionCodes() {
        Component result = MessageUtils.toSectionColorComponent("\u00a7aGreen");
        // Re-serialize with section serializer to verify round-trip
        String serialized = LegacyComponentSerializer.legacySection().serialize(result);
        assertEquals("\u00a7aGreen", serialized);
    }

    // ===== splitLegacyLine =====

    @Test
    void splitLegacyLineShortTextGoesToPrefix() {
        String[] parts = MessageUtils.splitLegacyLine("Hello");
        assertEquals("Hello", parts[0]);
        assertEquals("", parts[1]);
    }

    @Test
    void splitLegacyLineExactly16Chars() {
        String text = "1234567890123456";
        String[] parts = MessageUtils.splitLegacyLine(text);
        assertEquals(text, parts[0]);
        assertEquals("", parts[1]);
    }

    @Test
    void splitLegacyLineLongTextSplitsAtBoundary() {
        String text = "12345678901234567890";
        String[] parts = MessageUtils.splitLegacyLine(text);
        assertEquals("1234567890123456", parts[0]);
        assertEquals("7890", parts[1]);
    }

    @Test
    void splitLegacyLineDoesNotSplitInsideColorCode() {
        String text = "012345678901234\u00a7c_rest";
        String[] parts = MessageUtils.splitLegacyLine(text);
        assertEquals("012345678901234", parts[0]);
        assertTrue(parts[1].contains("\u00a7c"));
    }

    @Test
    void splitLegacyLineCarriesColorToSuffix() {
        String text = "\u00a7cRed text that is quite long";
        String[] parts = MessageUtils.splitLegacyLine(text);
        assertTrue(parts[1].startsWith("\u00a7c"),
                "Suffix should inherit the red color from prefix");
    }

    @Test
    void splitLegacyLineSuffixTruncatedTo16() {
        String text = "1234567890123456" + "abcdefghijklmnopqrstuvwxyz";
        String[] parts = MessageUtils.splitLegacyLine(text);
        assertTrue(parts[1].length() <= 16,
                "Suffix must not exceed 16 characters, was: " + parts[1].length());
    }

    // ===== getLastColors =====

    @Test
    void getLastColorsReturnsEmptyForNoColors() {
        assertEquals("", MessageUtils.getLastColors("plain text"));
    }

    @Test
    void getLastColorsReturnsLastColor() {
        assertEquals("\u00a7c", MessageUtils.getLastColors("\u00a7aGreen \u00a7cRed"));
    }

    @Test
    void getLastColorsResetClearsAll() {
        assertEquals("", MessageUtils.getLastColors("\u00a7cRed \u00a7rReset"));
    }

    @Test
    void getLastColorsIncludesFormatAfterColor() {
        assertEquals("\u00a7c\u00a7l",
                MessageUtils.getLastColors("\u00a7c\u00a7lBold Red"));
    }

    @Test
    void getLastColorsColorResetsFormat() {
        assertEquals("\u00a7a",
                MessageUtils.getLastColors("\u00a7lBold \u00a7aGreen"));
    }
}
