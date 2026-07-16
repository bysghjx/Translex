package tsp;

/**
 * A valid parsed TSP token: {@code [[ID||TEXT]]}.
 *
 * @param id   numeric style ID (references {@link TspRegistry})
 * @param text the content text to which the style is bound
 */
public record TspToken(int id, String text) implements TspElement {

    public TspToken {
        if (id < 0) throw new IllegalArgumentException("id must be non-negative: " + id);
        if (text == null) throw new IllegalArgumentException("text must not be null");
    }

    /** Render this token back to TSP wire format. */
    public String toWire() {
        return "[[" + id + "||" + text + "]]";
    }

    @Override
    public String toString() {
        return "Token[[" + id + "||" + text + "]]";
    }
}
