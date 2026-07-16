package tsp;

import java.util.Objects;

/**
 * A segment of text with an optional {@link Style}.
 *
 * <p>Two segments are considered equivalent if they have the same text and style.
 * This is used for round-trip testing.</p>
 */
public record StyledSegment(String text, Style style) {

    public StyledSegment {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(style, "style must not be null");
    }

    /** Create a plain text segment (Style.EMPTY). */
    public static StyledSegment plain(String text) {
        return new StyledSegment(text, Style.EMPTY);
    }

    /** Create a styled segment. */
    public static StyledSegment styled(String text, Style style) {
        if (style == null || style.isEmpty()) return plain(text);
        return new StyledSegment(text, style);
    }

    public boolean isPlain() {
        return style.isEmpty();
    }

    @Override
    public String toString() {
        if (isPlain()) return "Plain('" + text + "')";
        return "Styled('" + text + "', " + style + ")";
    }
}
