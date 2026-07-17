package tsp;

/**
 * Protocol-level recovery strategy for TSP tokens.
 *
 * <p>Recovery v0 (per spec §8): malformed tokens are treated as plain text.
 * Recovery v1 adds whitespace tolerance and best-effort repair for common
 * LLM formatting mistakes.</p>
 *
 * <p>This class encapsulates ALL recovery logic — parser delegates to it.
 * No scattered if-else in the parser. Each recovery level is explicit and testable.</p>
 */
public final class TspRecovery {

    private TspRecovery() {}

    /**
     * Recovery level.
     */
    public enum Level {
        /** V0: malformed → plain text, continue parsing. Spec-compliant baseline. */
        V0,
        /** V1: whitespace tolerance around ID / separator / brackets.
         *  e.g. {@code [[ 0 || text ]]} → valid token {@code [[0||text]]} */
        V1
    }

    /**
     * Attempt to recover whitespace around token components.
     * V1 only: trims spaces after {@code [[}, around {@code ||}, and before {@code ]]}.
     *
     * @param raw the raw text between {@code [[} and {@code ]]}
     * @return the cleaned text, or null if unrecoverable
     */
    public static String recoverWhitespace(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // Trim leading whitespace after [[
        String s = raw.stripLeading();

        // Find separator || (may have spaces around it)
        int sep = findSeparator(s);
        if (sep < 0) return null;

        String idPart = s.substring(0, sep).strip();
        // TEXT: strip leading whitespace (structural, after ||), preserve trailing (content)
        String textPart = s.substring(sep + 2).strip();

        // Validate ID is purely numeric after trimming
        if (idPart.isEmpty() || !idPart.chars().allMatch(Character::isDigit)) {
            return null;
        }

        // Validate ID is not unreasonably large
        try {
            long idVal = Long.parseLong(idPart);
            if (idVal > Integer.MAX_VALUE) return null;
        } catch (NumberFormatException e) {
            return null;
        }

        // Reject nested tokens (spec §7: no nested tokens)
        if (textPart.contains("[[")) return null;

        // Reconstruct clean token: [[ID||TEXT]] (no extra spaces)
        return idPart + "||" + textPart;
    }

    /**
     * Find the {@code ||} separator position, skipping whitespace-aware.
     * Returns the index of the first {@code |}, or -1 if not found.
     */
    private static int findSeparator(String s) {
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) == '|' && s.charAt(i + 1) == '|') {
                // Found ||
                return i;
            }
            // If we hit ]] before ||, no separator
            if (s.charAt(i) == ']' && i + 1 < s.length() && s.charAt(i + 1) == ']') {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Best-effort repair of a malformed token. Returns a repaired TspToken or null.
     *
     * <p>Repair attempts (in order):
     * <ol>
     *   <li>V1: whitespace tolerance</li>
     *   <li>If unrecoverable → null (caller treats as plain text)</li>
     * </ol>
     */
    public static TspToken tryRepair(String rawBetweenBrackets, Level level) {
        return switch (level) {
            case V0 -> null; // no repair, strict parsing only
            case V1 -> {
                String cleaned = recoverWhitespace(rawBetweenBrackets);
                if (cleaned == null) yield null;
                int pipe = cleaned.indexOf("||");
                String idPart = cleaned.substring(0, pipe);
                String text = cleaned.substring(pipe + 2);
                // v1.1: idPart 可能含 :HASH，拆出保留 checksum
                String checksum = null;
                int colon = idPart.indexOf(':');
                if (colon >= 0) {
                    checksum = idPart.substring(colon + 1);
                    idPart = idPart.substring(0, colon);
                }
                int id = Integer.parseInt(idPart);
                yield new TspToken(id, text, checksum);
            }
        };
    }
}
