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

        // v1.1: strip :HASH suffix from idPart before digit validation
        String checksumPart = null;
        int colon = idPart.indexOf(':');
        if (colon >= 0) {
            checksumPart = idPart.substring(colon + 1);
            idPart = idPart.substring(0, colon);
        }

        // Validate ID is purely numeric after trimming (and after stripping :HASH)
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

        // Reject nested tokens (spec §7: no nested tokens, checked on wire text)
        if (textPart.contains("[[")) return null;

        // Reconstruct clean token: [[ID[:HASH]||TEXT]] (no extra spaces)
        return (checksumPart != null ? idPart + ":" + checksumPart : idPart) + "||" + textPart;
    }

    /**
     * Find the {@code ||} separator position, skipping whitespace-aware.
     * Returns the index of the first {@code |}, or -1 if not found.
     * Escape-aware: {@code \]\]} and {@code \|\|} are escaped, not delimiters.
     */
    private static int findSeparator(String s) {
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) == '|' && s.charAt(i + 1) == '|') {
                // Count consecutive backslashes — odd → \|\| is escaped, not a separator
                int bs = 0;
                for (int j = i - 1; j >= 0 && s.charAt(j) == '\\'; j--) bs++;
                if ((bs & 1) == 1) continue; // escaped, skip
                return i;
            }
            // If we hit ]] before ||, no separator
            if (s.charAt(i) == ']' && i + 1 < s.length() && s.charAt(i + 1) == ']') {
                int bs = 0;
                for (int j = i - 1; j >= 0 && s.charAt(j) == '\\'; j--) bs++;
                if ((bs & 1) == 1) continue; // \]\] is escaped, not a closing delimiter
                return -1;
            }
        }
        return -1;
    }

    /**
     * Flatten an accidentally nested token like {@code [[A:hashA||[[B:hashB||textB]]textA]]}
     * into {@code [[B:hashB||textB]][[A:hashA||textA]]}. Called when the parser detects
     * {@code [[} inside token content (AI sometimes nests tokens instead of sequencing them).
     *
     * @param rawContent the text between the outer {@code [[} and {@code ]]}
     * @return flattened wire-format string, or null if unrecoverable
     */
    public static String flattenNestedToken(String rawContent) {
        // Find the separator ||
        int sep = -1;
        for (int i = 0; i < rawContent.length() - 1; i++) {
            if (rawContent.charAt(i) == '|' && rawContent.charAt(i + 1) == '|') {
                int bs = 0;
                for (int j = i - 1; j >= 0 && rawContent.charAt(j) == '\\'; j--) bs++;
                if ((bs & 1) == 1) continue; // escaped ||
                sep = i;
                break;
            }
        }
        if (sep < 0) return null;

        String idPart = rawContent.substring(0, sep);
        String textPart = rawContent.substring(sep + 2);

        // Find the nested [[ (must be after ||, not in ID)
        int nestOpen = textPart.indexOf("[[");
        if (nestOpen < 0) return null;

        // Find matching closing ]] for the nested token (count depth)
        int depth = 0;
        int nestClose = -1;
        for (int i = nestOpen + 2; i < textPart.length() - 1; i++) {
            if (textPart.startsWith("[[", i)) {
                depth++;
                i++; // skip second [
            } else if (textPart.startsWith("]]", i)) {
                int bs = 0;
                for (int j = i - 1; j >= 0 && textPart.charAt(j) == '\\'; j--) bs++;
                if ((bs & 1) == 0) { // not escaped
                    if (depth == 0) { nestClose = i; break; }
                    depth--;
                }
                i++; // skip second ]
            }
        }
        if (nestClose < 0) return null;

        String textBefore = textPart.substring(0, nestOpen);
        String nestedContent = textPart.substring(nestOpen + 2, nestClose);
        String textAfter = textPart.substring(nestClose + 2);

        // Validate the nested content can be parsed (at minimum has ||)
        if (!nestedContent.contains("||")) return null;

        // Reconstruct: [[outer||textBefore]] [[nested]] [[outer||textAfter]]
        // where empty parts are omitted
        StringBuilder sb = new StringBuilder();
        if (!textBefore.isEmpty()) {
            sb.append("[[").append(idPart).append("||").append(textBefore).append("]]");
        }
        sb.append("[[").append(nestedContent).append("]]");
        if (!textAfter.isEmpty()) {
            sb.append("[[").append(idPart).append("||").append(textAfter).append("]]");
        }
        return sb.toString();
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
                String wireText = cleaned.substring(pipe + 2);
                // v1.1: idPart 可能含 :HASH，拆出保留 checksum
                String checksum = null;
                int colon = idPart.indexOf(':');
                if (colon >= 0) {
                    checksum = idPart.substring(colon + 1);
                    idPart = idPart.substring(0, colon);
                }
                // v1.1: 非法 checksum -> 降级为 null（不阻塞解析，匹配 parseStrict 行为）
                if (checksum != null) {
                    if (checksum.isEmpty()) {
                        checksum = null;
                    } else if (!checksum.matches("[0-9a-fA-F]{1,8}")) {
                        checksum = null;  // 降级，不拒绝 token
                    }
                }
                int id = Integer.parseInt(idPart);
                // Unescape TEXT (reverse encoder escape of \]  and \\)
                String text = TspParser.unescapeText(wireText);
                yield new TspToken(id, text, checksum);
            }
        };
    }
}
