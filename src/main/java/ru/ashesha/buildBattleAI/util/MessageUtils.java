package ru.ashesha.buildBattleAI.util;

import lombok.experimental.UtilityClass;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Shared utilities for message sub-services.
 * <p>
 * Provides legacy {@code &}-coded text deserialization used across
 * all message sub-services (chat, title, bar, tab, name).
 */
@UtilityClass
public class MessageUtils {

    /** Serializer for converting legacy {@code &}-prefixed color codes into Adventure components. */
    public final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /**
     * Deserializes a legacy {@code &}-coded string into an Adventure {@link Component}.
     * Treats {@code null} as an empty string.
     *
     * @param text the raw text with {@code &} color codes, or {@code null}
     * @return the deserialized component
     */
    public Component toComponent(String text) {
        return LEGACY.deserialize(text == null ? "" : text);
    }
}
