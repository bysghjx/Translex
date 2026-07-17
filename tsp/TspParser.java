package tsp;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a TSP-encoded string into a sequence of {@link TspElement}s.
 *
 * <p>Validates (per spec §7):
 * <ul>
 *   <li>balanced {@code [[...]]} delimiters</li>
 *   <li>numeric IDs</li>
 *   <li>valid {@code ||} separator</li>
 *   <li>no nested tokens</li>
 * </ul>
 *
 * <p>Recovery (per spec §8 + v1 whitespace tolerance):
 * Malformed tokens are repaired if possible; otherwise treated as plain text.
 * The parser never throws - errors are collected as structured {@link ParseError}s
 * in {@link ParseResult#parseErrors()}.</p>
 */
public final class TspParser {

    private final TspRecovery.Level recoveryLevel;

    /** Strict parser - V0 recovery: malformed -> plain text. */
    public TspParser() {
        this(TspRecovery.Level.V0);
    }

    /** Parser with explicit recovery level. V1 adds whitespace tolerance. */
    public TspParser(TspRecovery.Level recoveryLevel) {
        this.recoveryLevel = recoveryLevel;
    }

    /**
     * Parse a TSP-encoded string.
     *
     * @param input the raw TSP string (may contain tokens and plain text)
     * @return a ParseResult with parsed elements and any errors encountered
     */
    public ParseResult parse(String input) {
        if (input == null || input.isEmpty()) {
            return new ParseResult(List.of(), List.of());
        }

        List<TspElement> elements = new ArrayList<>();
        List<ParseError> parseErrors = new ArrayList<>();
        int pos = 0;
        int len = input.length();

        while (pos < len) {
            int openPos = input.indexOf("[[", pos);

            if (openPos == -1) {
                String rest = input.substring(pos);
                if (!rest.isEmpty()) {
                    elements.add(new TspText(rest));
                }
                break;
            }

            // Text before the opening bracket is plain text
            if (openPos > pos) {
                elements.add(new TspText(input.substring(pos, openPos)));
            }

            // Attempt to parse a token starting at openPos
            TokenParseResult tokenResult = tryParseToken(input, openPos, len);
            if (tokenResult.token != null) {
                elements.add(tokenResult.token);
                pos = tokenResult.nextPos;
                if (tokenResult.parseError != null) {
                    // Recovered token - record the recovery event
                    parseErrors.add(tokenResult.parseError);
                }
            } else {
                // Malformed and unrecoverable.
                // Skip the entire [[...]] span (if closed) so we don't parse inner tokens.
                int skipTo = openPos + 2;
                int close = findClosingBrackets(input, openPos + 2, len);
                if (close >= 0) {
                    skipTo = close + 2;
                }
                String malformedText = input.substring(openPos, Math.min(skipTo, len));
                elements.add(new TspText(malformedText));
                if (tokenResult.parseError != null) {
                    parseErrors.add(tokenResult.parseError);
                }
                pos = skipTo;
            }
        }

        return new ParseResult(List.copyOf(elements), List.copyOf(parseErrors));
    }

    /**
     * Try to parse a complete {@code [[ID||TEXT]]} token starting at {@code start}.
     */
    private TokenParseResult tryParseToken(String input, int start, int len) {
        int pos = start + 2;  // skip "[["

        int closePos = findClosingBrackets(input, pos, len);
        if (closePos < 0) {
            // Unclosed: back up the raw content we did see (from start+2 to end)
            String rawTail = input.substring(pos);
            return TokenParseResult.fail(new ParseError(
                    ParseError.Type.UNCLOSED, start, rawTail,
                    "Unclosed token at position " + start));
        }

        String rawContent = input.substring(pos, closePos);

        // --- Strict parse ---
        TspToken strict = parseStrict(rawContent);
        if (strict != null) {
            return TokenParseResult.success(strict, closePos + 2, null);
        }

        // --- Recovery: try to repair ---
        TspToken repaired = TspRecovery.tryRepair(rawContent, recoveryLevel);
        if (repaired != null) {
            ParseError err = new ParseError(
                    ParseError.Type.RECOVERED, start, rawContent,
                    "Recovered whitespace in token");
            return TokenParseResult.success(repaired, closePos + 2, err);
        }

        // --- Unrecoverable ---
        return TokenParseResult.fail(new ParseError(
                ParseError.Type.MALFORMED, start, rawContent,
                "Malformed token at position " + start));
    }

    /**
     * Strict parse of content between [[ and ]].
     * Rules: numeric ID immediately after [[, exactly "||" separator, TEXT immediately
     * after ||, no nesting. Trailing whitespace in TEXT is preserved (it's content).
     *
     * @return valid TspToken or null (caller may attempt recovery)
     */
    private static TspToken parseStrict(String rawContent) {
        if (rawContent.isEmpty()) return null;

        // Find "||" - must not cross into nested [[...]]
        int sepIdx = -1;
        for (int i = 0; i < rawContent.length() - 1; i++) {
            char c = rawContent.charAt(i);
            if (c == '[' && rawContent.charAt(i + 1) == '[') {
                return null;  // nested token
            }
            if (c == '|' && rawContent.charAt(i + 1) == '|') {
                sepIdx = i;
                break;
            }
        }

        if (sepIdx < 0) return null;  // no separator

        String idStr = rawContent.substring(0, sepIdx);
        String text = rawContent.substring(sepIdx + 2);

        // v1.1: ID 可能含 :HASH（[[ID:HASH||TEXT]]）。拆出 checksum。
        String checksum = null;
        int colon = idStr.indexOf(':');
        if (colon >= 0) {
            checksum = idStr.substring(colon + 1);
            idStr = idStr.substring(0, colon);
            // checksum 必须是 hex（4-6 位）；非法 -> strict 拒绝，V1 recovery 处理
            if (checksum.isEmpty() || !checksum.matches("[0-9a-fA-F]{1,8}")) return null;
        }

        // ID must be purely numeric, no whitespace
        if (idStr.isEmpty() || !idStr.chars().allMatch(Character::isDigit)) {
            return null;
        }

        int id;
        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            return null;
        }

        if (id < 0) return null;

        // Strict: TEXT preserved verbatim (leading/trailing whitespace is content).
        // Structural whitespace around ID/|| makes idStr non-numeric -> strict rejects,
        // V1 recovery handles it. Keeps encoder round-trip safe (V0 default).
        if (text.contains("[[")) return null;  // nesting

        return new TspToken(id, text, checksum);
    }

    /**
     * Find the closing {@code ]]} for a token. Returns position of first {@code ]}, or -1.
     */
    private static int findClosingBrackets(String input, int fromIndex, int len) {
        for (int i = fromIndex; i < len - 1; i++) {
            if (input.charAt(i) == ']' && input.charAt(i + 1) == ']') {
                return i;
            }
        }
        return -1;
    }

    // ================================================================
    // Internal result type
    // ================================================================

    private static final class TokenParseResult {
        final TspToken token;        // null if unrecoverable
        final int nextPos;
        final ParseError parseError;  // non-null for recovered/unrecoverable events

        private TokenParseResult(TspToken token, int nextPos, ParseError parseError) {
            this.token = token;
            this.nextPos = nextPos;
            this.parseError = parseError;
        }

        static TokenParseResult success(TspToken token, int nextPos, ParseError recovered) {
            return new TokenParseResult(token, nextPos, recovered);
        }

        static TokenParseResult fail(ParseError error) {
            return new TokenParseResult(null, -1, error);
        }
    }

    // ================================================================
    // Public types
    // ================================================================

    /**
     * The result of parsing a TSP string.
     *
     * @param elements    parsed elements (tokens + plain text)
     * @param parseErrors structured recovery/error events (may be empty)
     */
    public record ParseResult(List<TspElement> elements, List<ParseError> parseErrors) {

        public List<TspToken> tokens() {
            return elements.stream()
                    .filter(e -> e instanceof TspToken)
                    .map(e -> (TspToken) e)
                    .toList();
        }

        /** Structured errors - use this for counting / backup. */
        public List<ParseError> parseErrors() {
            return parseErrors;
        }

        /** Human-readable error messages (backward compatible with old {@code errors()}). */
        public List<String> errors() {
            return parseErrors.stream().map(ParseError::message).toList();
        }

        public boolean hasErrors() {
            return !parseErrors.isEmpty();
        }

        // ---- counts (per-parse) ----

        /** Number of tokens successfully recovered via V1. */
        public int recoveredCount() {
            return (int) parseErrors.stream().filter(ParseError::recovered).count();
        }

        /** Number of tokens that could not be recovered (became plain text). */
        public int unrecoverableCount() {
            return (int) parseErrors.stream().filter(ParseError::unrecoverable).count();
        }

        /** Unrecoverable errors only - their {@link ParseError#rawContent()} is the
         *  full malformed input, suitable for backup/analysis. */
        public List<ParseError> unrecoverableErrors() {
            return parseErrors.stream().filter(ParseError::unrecoverable).toList();
        }

        @Override
        public String toString() {
            return "ParseResult{elements=" + elements.size()
                    + ", recovered=" + recoveredCount()
                    + ", unrecoverable=" + unrecoverableCount()
                    + (hasErrors() ? ", errors=" + parseErrors : "") + "}";
        }
    }
}
