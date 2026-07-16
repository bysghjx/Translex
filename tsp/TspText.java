package tsp;

import java.util.Objects;

/**
 * A plain-text span in the parsed TSP output.
 * Includes both genuine plain text and malformed tokens that were recovered as plain text.
 */
public record TspText(String text) implements TspElement {

    public TspText {
        Objects.requireNonNull(text, "text must not be null");
    }

    @Override
    public String toString() {
        return "Text('" + text + "')";
    }
}
