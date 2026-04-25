package ru.ashesha.buildBattleAI.util;

import lombok.experimental.UtilityClass;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Centralized text formatting utilities for the BuildBattleAI plugin.
 * <p>
 * This class is the <b>single authority</b> for all legacy Minecraft color code
 * handling in the project. All color translation and component serialization
 * must go through these methods — no other class should reference the section
 * sign character ({@code §}) directly.
 * <p>
 * Two primary serialization paths are provided:
 * <ul>
 *     <li>{@link #toColorComponent(String)} — converts {@code &}-coded text
 *         into an Adventure {@link Component}. Used by all PacketEvents-based
 *         services (chat, title, bar, tab, name, hologram, scoreboard).</li>
 *     <li>{@link #translateColors(String)} — converts {@code &}-coded text
 *         into a raw string with section-sign ({@code §}) codes. Used for
 *         non-component contexts such as pre-1.13 entity metadata strings
 *         and scoreboard entry names.</li>
 * </ul>
 * Additionally, {@link #toSectionColorComponent(String)} converts already-translated
 * {@code §}-coded text into a {@link Component} (e.g. after legacy line splitting).
 */
@UtilityClass
public class MessageUtils {

    /** The Minecraft section sign character used for native color codes. */
    private final char SECTION_SIGN = '\u00a7';

    /** Valid characters that follow a color prefix ({@code &} or {@code §}) to form a code. */
    private final String VALID_CODES = "0123456789abcdefklmnorABCDEFKLMNOR";

    /** Serializer for converting {@code &}-prefixed color codes into Adventure components. */
    private final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /** Serializer for converting {@code §}-prefixed color codes into Adventure components. */
    private final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

    /**
     * Translates {@code &} color codes to section sign ({@code §}) color codes.
     * Returns the input unchanged if it contains no {@code &} characters.
     * Treats {@code null} as an empty string.
     *
     * @param text the input text with {@code &} codes, or {@code null}
     * @return the text with Minecraft-native {@code §} codes
     */
    public String translateColors(String text) {
        if (text == null)
            return "";
        if (text.indexOf('&') == -1)
            return text;

        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length - 1; i++)
            if (chars[i] == '&' && VALID_CODES.indexOf(chars[i + 1]) != -1)
                chars[i] = SECTION_SIGN;

        return new String(chars);
    }

    /**
     * Deserializes a legacy {@code &}-coded string into an Adventure {@link Component}.
     * Treats {@code null} as an empty string.
     * <p>
     * This is the primary formatting method for all PacketEvents-based services.
     * Use this whenever the target API accepts an Adventure {@link Component}.
     *
     * @param text the raw text with {@code &} color codes, or {@code null}
     * @return the deserialized component
     */
    public Component toColorComponent(String text) {
        return text == null ? Component.empty() : LEGACY.deserialize(text);
    }

    /**
     * Deserializes a section-sign ({@code §})-coded string into an Adventure
     * {@link Component}. Treats {@code null} as an empty string.
     * <p>
     * Used for text that has already been translated via {@link #translateColors}
     * and needs to be converted into a component (e.g., split legacy scoreboard
     * lines on pre-1.13 servers).
     *
     * @param text the text with {@code §} color codes, or {@code null}
     * @return the deserialized component
     */
    public Component toSectionColorComponent(String text) {
        return SECTION.deserialize(text == null ? "" : text);
    }

    /**
     * Splits a section-sign-coded string into prefix and suffix parts,
     * each at most 16 characters, for use on pre-1.13 servers. Carries the
     * last active color and formatting codes from the prefix into the suffix
     * so that visual formatting is preserved across the split boundary.
     *
     * @param text the translated text with {@code §} color codes
     * @return a two-element array: {@code [prefix, suffix]}
     */
    public String[] splitLegacyLine(String text) {
        if (text.length() <= 16)
            return new String[]{text, ""};

        // Avoid splitting in the middle of a color code sequence (§X)
        int splitAt = 16;
        if (text.charAt(splitAt - 1) == SECTION_SIGN)
            splitAt--;

        String prefix = text.substring(0, splitAt);
        String carry = getLastColors(prefix);
        String suffix = carry + text.substring(splitAt);

        // Truncate suffix to fit the 16-char protocol limit
        if (suffix.length() > 16)
            suffix = suffix.substring(0, 16);

        return new String[]{prefix, suffix};
    }

    /**
     * Extracts the last active color and formatting codes from a legacy text string.
     * A color code ({@code §0}–{@code §f}) resets all prior formatting; {@code §r}
     * resets everything. Format codes ({@code §k}–{@code §o}) are cumulative until
     * the next color code or reset.
     *
     * @param text the text to scan for color codes
     * @return the cumulative color/format codes active at the end of the text
     */
    public String getLastColors(String text) {
        StringBuilder color = new StringBuilder();
        StringBuilder format = new StringBuilder();
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) != SECTION_SIGN)
                continue;
            char code = Character.toLowerCase(text.charAt(i + 1));
            if (code == 'r') {
                color.setLength(0);
                format.setLength(0);
            } else if ("0123456789abcdef".indexOf(code) >= 0) {
                color.setLength(0);
                color.append(SECTION_SIGN).append(text.charAt(i + 1));
                format.setLength(0); // a color code resets formatting
            } else if ("klmno".indexOf(code) >= 0)
                format.append(SECTION_SIGN).append(text.charAt(i + 1));
            i++; // skip the code character
        }
        return color.toString() + format;
    }
}
