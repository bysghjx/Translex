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
                // Malformed and unrecoverable — try nested flatten as last resort.
                // AI sometimes nests: [[A||[[B||textB]]textA]]. findClosingBrackets
                // matched the inner ]], so we must find the outer closing and flatten.
                boolean nestedFixed = false;
                int skipTo = openPos + 2;
                int close = findClosingBrackets(input, openPos + 2, len);
                if (close >= 0) {
                    String rawContent = input.substring(openPos + 2, close);
                    if (rawContent.contains("[[")) {
                        int outerClose = findOuterClosingBrackets(input, openPos + 2, len);
                        if (outerClose >= 0) {
                            String fullContent = input.substring(openPos + 2, outerClose);
                            String flattened = TspRecovery.flattenNestedToken(fullContent);
                            if (flattened != null) {
                                // Inline-parse the flattened tokens
                                TspParser innerParser = new TspParser(recoveryLevel);
                                TspParser.ParseResult innerResult = innerParser.parse(flattened);
                                if (!innerResult.elements().isEmpty()) {
                                    elements.addAll(innerResult.elements());
                                    parseErrors.addAll(innerResult.parseErrors());
                                    parseErrors.add(new ParseError(
                                            ParseError.Type.RECOVERED, openPos, fullContent,
                                            "Recovered nested token(s)"));
                                    pos = outerClose + 2;
                                    nestedFixed = true;
                                }
                            }
                        }
                    }
                }
                if (!nestedFixed) {
                    if (close >= 0) skipTo = close + 2;
                    String malformedText = input.substring(openPos, Math.min(skipTo, len));
                    elements.add(new TspText(malformedText));
                    if (tokenResult.parseError != null) {
                        parseErrors.add(tokenResult.parseError);
                    }
                    pos = skipTo;
                }
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
        // 非法 HASH 降级为无校验（保留 ID 丢弃 checksum），不阻塞解析。
        // 否则 AI 返回的 token 会整段变 plain text，导致 [[...]] 原文泄露。
        String checksum = null;
        int colon = idStr.indexOf(':');
        if (colon >= 0) {
            checksum = idStr.substring(colon + 1);
            idStr = idStr.substring(0, colon);
            if (!checksum.isEmpty() && !checksum.matches("[0-9a-fA-F]{1,8}")) {
                checksum = null;  // 非法 HASH -> 降级为无校验，不 reject
            }
            if (checksum != null && checksum.isEmpty()) checksum = null;
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
        if (text.contains("[[")) return null;  // nesting (checked on wire text, not unescaped)

        return new TspToken(id, unescapeText(text), checksum);
    }

    /**
     * Reverse {@link TspToken#escapeText(String)}: {@code \]} → {@code ]}, {@code \\} → {@code \}.
     * Scans left-to-right; unrecognised escape sequences are kept as-is.
     */
    public static String unescapeText(String s) {
        if (s == null || s.isEmpty() || s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '\\' -> sb.append('\\');
                    case ']'  -> sb.append(']');
                    default  -> sb.append(c).append(next);
                }
                i++; // skip next char
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Find the closing {@code ]]} for a token. Returns position of first {@code ]}, or -1.
     * Escape-aware: {@code \]} (odd leading backslashes) is an escaped bracket, not a delimiter.
     */
    private static int findClosingBrackets(String input, int fromIndex, int len) {
        return findClosingBracketsDepth(input, fromIndex, len, false);
    }

    /** Like findClosingBrackets but skips over nested [[...]] spans to find the outermost closing ]]. */
    private static int findOuterClosingBrackets(String input, int fromIndex, int len) {
        return findClosingBracketsDepth(input, fromIndex, len, true);
    }

    private static int findClosingBracketsDepth(String input, int fromIndex, int len, boolean skipNested) {
        int depth = 1;
        for (int i = fromIndex; i < len - 1; i++) {
            if (skipNested && input.charAt(i) == '[' && input.charAt(i + 1) == '[') {
                depth++;
            } else if (input.charAt(i) == ']' && input.charAt(i + 1) == ']') {
                int bs = 0;
                for (int j = i - 1; j >= 0 && input.charAt(j) == '\\'; j--) bs++;
                if ((bs & 1) == 0) { // not escaped
                    depth--;
                    if (depth == 0) return i;
                }
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
