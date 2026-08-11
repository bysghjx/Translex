package tsp;

/**
 * A valid parsed TSP token: {@code [[ID||TEXT]]} or {@code [[ID:HASH||TEXT]]} (v1.1).
 *
 * @param id       numeric style ID (references {@link TspRegistry})
 * @param text     the content text to which the style is bound
 * @param checksum optional content hash (v1.1, 4 hex); null for v1.0 tokens.
 *                 When present, decoder verifies (ID, checksum) to detect AI moving
 *                 content across color-IDs, and can auto-repair ID via checksum lookup.
 */
public record TspToken(int id, String text, String checksum) implements TspElement {

    /** v1.0 兼容构造（无 checksum）。 */
    public TspToken(int id, String text) {
        this(id, text, null);
    }

    public TspToken {
        if (id < 0) throw new IllegalArgumentException("id must be non-negative: " + id);
        if (text == null) throw new IllegalArgumentException("text must not be null");
    }

    /** Render this token back to TSP wire format (v1.1 if checksum present).
     *  <p>Escapes {@code \} and {@code ]} in TEXT so {@code ]]} in content
     *  does not prematurely close the token. Parser reverses this via
     *  {@link TspParser#unescapeText(String)}.</p> */
    public String toWire() {
        String escaped = escapeText(text);
        return checksum != null
                ? "[[" + id + ":" + checksum + "||" + escaped + "]]"
                : "[[" + id + "||" + escaped + "]]";
    }

    /** Escape {@code \} → {@code \\} then {@code ]} → {@code \]} so that
     *  {@code ]]} in content survives the wire round-trip. */
    static String escapeText(String s) {
        return s.replace("\\", "\\\\").replace("]", "\\]");
    }

    @Override
    public String toString() {
        return checksum != null
                ? "Token[[" + id + ":" + checksum + "||" + text + "]]"
                : "Token[[" + id + "||" + text + "]]";
    }
}
