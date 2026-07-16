package tsp;

import java.util.Objects;

/**
 * A structured parse error / recovery event from {@link TspParser}.
 *
 * <p>Replaces the old plain-string errors with a structured record so callers can
 * count, classify, and back up unrecoverable input for later analysis
 * (improving recovery rules, tuning prompts, etc.).</p>
 *
 * @param type       error category
 * @param position   index in the original input where the token started
 * @param rawContent the FULL raw content between {@code [[} and {@code ]]}, untruncated
 *                   (kept verbatim so it can be backed up and analyzed later)
 * @param message    human-readable description (for logging / backward compat)
 */
public record ParseError(Type type, int position, String rawContent, String message) {

    public enum Type {
        /** V1 recovery succeeded - token repaired and produced. Token is non-null. */
        RECOVERED,
        /** Token malformed and could not be repaired. Treated as plain text. Token is null. */
        MALFORMED,
        /** Token never closed (no matching ]]). Treated as plain text. Token is null. */
        UNCLOSED
    }

    public ParseError {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(rawContent, "rawContent");
        Objects.requireNonNull(message, "message");
        if (position < 0) throw new IllegalArgumentException("position must be >= 0: " + position);
    }

    /** True if this error was successfully recovered (token still produced). */
    public boolean recovered() {
        return type == Type.RECOVERED;
    }

    /** True if the token could not be salvaged (became plain text). */
    public boolean unrecoverable() {
        return type != Type.RECOVERED;
    }

    @Override
    public String toString() {
        return type + "@" + position + " [" + rawContent + "] " + message;
    }
}
