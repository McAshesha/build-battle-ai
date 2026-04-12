package ru.ashesha.buildBattleAI.api;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable rich chat message composed of one or more {@link Segment}s.
 * <p>
 * Each segment carries display text and optional interactive properties
 * (click action, hover tooltip). Messages are built via the fluent
 * {@link Builder} API and can be sent through {@link BBAIMessageService}.
 * <p>
 * Supports placeholder replacement via {@link #replace(String, String)},
 * which returns a new message instance with all occurrences substituted
 * across text, click values, and hover text.
 *
 * @see BBAIMessageService
 */
@Getter
@EqualsAndHashCode
public class BBAIChatMessage {

    /** Unmodifiable list of message segments in display order. */
    private final List<Segment> segments;

    private BBAIChatMessage(List<Segment> segments) {
        this.segments = Collections.unmodifiableList(new ArrayList<Segment>(segments));
    }

    /**
     * Creates a new builder for constructing a rich chat message.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a simple message with a single plain-text segment.
     *
     * @param text the message text (supports {@code &} color codes)
     * @return a new message containing a single text segment
     */
    public static BBAIChatMessage of(@NonNull String text) {
        return builder().append(text).build();
    }

    /**
     * Returns a new message with all occurrences of {@code target} replaced
     * by {@code replacement} across every segment's text, click value, and hover text.
     *
     * @param target      the string to search for
     * @param replacement the string to substitute in
     * @return a new message with replacements applied
     */
    public BBAIChatMessage replace(@NonNull String target, @NonNull String replacement) {
        List<Segment> replacedSegments = new ArrayList<Segment>(segments.size());
        for (Segment segment : segments) {
            replacedSegments.add(segment.replace(target, replacement));
        }
        return new BBAIChatMessage(replacedSegments);
    }

    /**
     * Actions that can be triggered when a player clicks on a message segment.
     */
    public enum ClickAction {
        /** Opens a URL in the player's browser. */
        OPEN_URL,
        /** Immediately executes a command as the player. */
        RUN_COMMAND,
        /** Inserts a command into the player's chat input without executing it. */
        SUGGEST_COMMAND
    }

    /**
     * A single segment of a rich chat message. Each segment contains display text
     * and optional interactive properties (click action and hover tooltip).
     */
    @Getter
    @EqualsAndHashCode
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Segment {

        /** The display text for this segment (supports {@code &} color codes). */
        @NonNull private final String text;
        /** The action triggered on click, or {@code null} for no click behavior. */
        private final ClickAction clickAction;
        /** The value associated with the click action (URL, command, etc.), or {@code null}. */
        private final String clickValue;
        /** The tooltip text shown on hover, or {@code null} for no tooltip. */
        private final String hoverText;

        /**
         * Replaces occurrences of {@code target} in a nullable string value.
         *
         * @return the replaced string, or {@code null} if the input was {@code null}
         */
        private static String replaceValue(String value, String target, String replacement) {
            if (value == null) {
                return null;
            }
            return value.replace(target, replacement);
        }

        /**
         * Returns a new segment with all occurrences of {@code target} replaced
         * in text, click value, and hover text. The click action type is preserved.
         */
        private Segment replace(String target, String replacement) {
            return new Segment(
                    replaceValue(text, target, replacement),
                    clickAction,
                    replaceValue(clickValue, target, replacement),
                    replaceValue(hoverText, target, replacement)
            );
        }
    }

    /**
     * Fluent builder for constructing {@link BBAIChatMessage} instances
     * by appending segments with optional interactive properties.
     */
    public static class Builder {

        private final List<Segment> segments = new ArrayList<Segment>();

        /**
         * Appends a plain-text segment with no interactive properties.
         *
         * @param text the display text
         * @return this builder for chaining
         */
        public Builder append(@NonNull String text) {
            return append(text, null, null, null);
        }

        /**
         * Appends a text segment with a hover tooltip but no click action.
         *
         * @param text      the display text
         * @param hoverText the tooltip shown on hover
         * @return this builder for chaining
         */
        public Builder append(@NonNull String text, String hoverText) {
            return append(text, null, null, hoverText);
        }

        /**
         * Appends a clickable text segment with no hover tooltip.
         *
         * @param text        the display text
         * @param clickAction the action triggered on click
         * @param clickValue  the value for the click action (URL or command)
         * @return this builder for chaining
         */
        public Builder append(@NonNull String text, ClickAction clickAction, String clickValue) {
            return append(text, clickAction, clickValue, null);
        }

        /**
         * Appends a fully configured text segment with click action and hover tooltip.
         *
         * @param text        the display text
         * @param clickAction the action triggered on click, or {@code null}
         * @param clickValue  the value for the click action, or {@code null}
         * @param hoverText   the tooltip shown on hover, or {@code null}
         * @return this builder for chaining
         */
        public Builder append(@NonNull String text, ClickAction clickAction, String clickValue, String hoverText) {
            segments.add(new Segment(text, clickAction, clickValue, hoverText));
            return this;
        }

        /**
         * Builds an immutable {@link BBAIChatMessage} from the appended segments.
         *
         * @return the constructed message
         */
        public BBAIChatMessage build() {
            return new BBAIChatMessage(segments);
        }
    }
}
