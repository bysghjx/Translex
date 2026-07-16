package tsp.tests;

import tsp.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;

/**
 * End-to-end test: send TSP-encoded Hypixel tooltip to a real AI API,
 * decode the response, and verify token integrity.
 *
 * <p>Usage:
 *   java tsp.tests.TspAiTest &lt;apiKey&gt; [apiUrl] [model]
 *
 * <p>Defaults:
 *   apiUrl  = https://api.deepseek.com/chat/completions
 *   model   = deepseek-v4-flash
 *
 * <p>No Minecraft, no OkHttp, no Translex dependencies.
 * Pure Java 21 — java.net.http.HttpClient.</p>
 */
public final class TspAiTest {

    private static final String DEFAULT_URL   = "https://api.deepseek.com/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    // ---- Test data: Mammoth pet "Wooly Coat" paragraph (lines 4-6) ----
    private static final Style GRAY  = Style.of("#AAAAAA");
    private static final Style GREEN = Style.of("#55FF55");
    private static final Style AQUA  = Style.of("#55FFFF");

    private static final List<StyledSegment> MAMMOTH_PARA = List.of(
            StyledSegment.styled("Gain a ", GRAY),
            StyledSegment.styled("56%", GREEN),
            StyledSegment.styled(" chance for mobs to not\n", GRAY),
            StyledSegment.styled("inflict ", GRAY),
            StyledSegment.styled("❄ Cold ", AQUA),
            StyledSegment.styled(" when damaging you in\n", GRAY),
            StyledSegment.styled("the ", GRAY),
            StyledSegment.styled("Glacite Mineshafts", AQUA),
            StyledSegment.styled(".", GRAY)
    );

    // ---- TSP instruction appended to system prompt ----
    private static final String TSP_INSTRUCTION = """

            --- TRANSLATION RULES ---
            The text may contain tokens in the format [[NUMBER||TEXT]].
            These are style-protection tokens. You MUST:
            1. Keep [[ ... || ... ]] brackets and the NUMBER exactly as-is.
            2. Translate ONLY the TEXT part inside each token.
            3. Do NOT add spaces inside the brackets.
            4. Do NOT split, merge, or create new [[...]] tokens.
            5. The NUMBER is a style reference — never change it.
            6. Reorder tokens freely if needed for natural Chinese.

            Example:
            Input:  Gain [[1||56%]] chance [[2||❄ Cold]].
            Output: 有 [[1||56%]] 的概率受 [[2||❄ Cold]] 影响。""";

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("Usage: java tsp.tests.TspAiTest <apiKey> [apiUrl] [model]");
            System.exit(1);
        }

        String apiKey  = args[0];
        String apiUrl  = args.length > 1 && !args[1].isBlank() ? args[1] : DEFAULT_URL;
        String model   = args.length > 2 && !args[2].isBlank() ? args[2] : DEFAULT_MODEL;

        System.out.println("=== TSP AI End-to-End Test ===");
        System.out.println("API:  " + apiUrl);
        System.out.println("Model: " + model);
        System.out.println();

        // ── Step 1: Encode (with auto-default: body text = plain, highlights = tokens) ──
        TspRegistry registry = new TspRegistry();
        TspEncoder encoder = TspEncoder.withAutoDefault(registry, MAMMOTH_PARA);
        String encoded = encoder.encode(MAMMOTH_PARA);

        System.out.println("── Original paragraph ──");
        for (StyledSegment seg : MAMMOTH_PARA) {
            String marker = seg.isPlain() ? "" : " [" + seg.style().colorHex() + "]";
            System.out.print(seg.text() + marker);
        }
        System.out.println("\n");

        System.out.println("── TSP Encoded (sent to AI) ──");
        System.out.println(encoded);
        System.out.println();

        System.out.println("── Registry ──");
        for (int i = 0; i < registry.size(); i++) {
            System.out.println("  ID " + i + " → " + registry.getStyle(i));
        }
        System.out.println();

        // ── Step 2: Send to AI ──
        String systemPrompt = "You are a Minecraft item tooltip translator. Translate to Simplified Chinese. "
                + "Only output the translated text — no explanations, no markdown, no code blocks."
                + TSP_INSTRUCTION;

        String body = buildOpenAiBody(model, systemPrompt, encoded);
        System.out.println("── Sending to AI (" + body.length() + " bytes) ... ──");

        String aiResponse = callAi(apiKey, apiUrl, body);

        System.out.println("── AI Raw Response ──");
        System.out.println(aiResponse);
        System.out.println();

        // ── Step 3: Parse & Decode ──
        TspParser parser = new TspParser();
        TspParser.ParseResult parsed = parser.parse(aiResponse);
        TspDecoder decoder = new TspDecoder(registry);
        List<StyledSegment> decoded = decoder.decode(parsed);

        System.out.println("── Decoded Result ──");
        for (StyledSegment seg : decoded) {
            if (seg.isPlain()) {
                System.out.print(seg.text());
            } else {
                System.out.print("[" + seg.style().colorHex() + "]" + seg.text() + "[/" + seg.style().colorHex() + "]");
            }
        }
        System.out.println("\n");

        // ── Step 4: Report ──
        System.out.println("── Token Integrity Report ──");
        int origTokens = (int) MAMMOTH_PARA.stream().filter(s -> !s.isPlain()).count();
        int respTokens = parsed.tokens().size();

        System.out.printf("Original tokens: %d | AI returned tokens: %d%n", origTokens, respTokens);

        if (parsed.hasErrors()) {
            System.out.println("⚠ PARSER ERRORS: " + parsed.errors());
        }

        boolean allIdsValid = true;
        for (TspToken t : parsed.tokens()) {
            Style s = registry.getStyle(t.id());
            if (s.isEmpty()) {
                System.out.println("⚠ UNKNOWN ID: " + t.id() + " in token [[" + t.id() + "||" + t.text() + "]]");
                allIdsValid = false;
            }
        }

        if (origTokens == respTokens && !parsed.hasErrors() && allIdsValid) {
            System.out.println("✅ PERFECT — AI preserved all tokens correctly!");
        } else {
            System.out.println("⚠ Some tokens changed. Check raw response above.");
        }
    }

    // ---- HTTP ----

    private static String callAi(String apiKey, String apiUrl, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        String bodyStr = response.body();

        // Print usage
        System.out.println("── API Usage ──");
        String usage = extractJsonField(bodyStr, "usage");
        if (!usage.isEmpty()) {
            System.out.println("  " + usage);
        }
        System.out.println();

        if (response.statusCode() != 200) {
            String snippet = bodyStr.length() > 500 ? bodyStr.substring(0, 500) + "..." : bodyStr;
            return "HTTP " + response.statusCode() + ": " + snippet;
        }

        return extractContent(bodyStr);
    }

    /** Extract a JSON field value (simple, no parser dependency). */
    private static String extractJsonField(String json, String fieldName) {
        int keyIdx = json.indexOf("\"" + fieldName + "\"");
        if (keyIdx == -1) return "";
        int colonIdx = json.indexOf(':', keyIdx);
        if (colonIdx == -1) return "";
        int braceDepth = 0;
        boolean inString = false;
        StringBuilder val = new StringBuilder();
        for (int i = colonIdx + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') { val.append(c); val.append(json.charAt(i + 1)); i++; }
                else if (c == '"') { inString = false; val.append(c); }
                else val.append(c);
            } else {
                if (c == '"') { inString = true; val.append(c); }
                else if (c == '{' || c == '[') { braceDepth++; val.append(c); }
                else if (c == '}' || c == ']') {
                    if (braceDepth == 0) break;
                    braceDepth--; val.append(c);
                }
                else if (c == ',' && braceDepth == 0) break;
                else if (!Character.isWhitespace(c)) val.append(c);
            }
        }
        return val.toString().trim();
    }

    /** Extract message content from OpenAI-compatible JSON response. */
    private static String extractContent(String responseBody) {
        // Simple JSON extraction — avoids needing a JSON parser dependency
        // Look for "content":"..." in choices[0].message
        int contentIdx = responseBody.indexOf("\"content\"");
        if (contentIdx == -1) {
            return responseBody; // fallback: return raw
        }
        // Find the value string after "content":
        int colonIdx = responseBody.indexOf(':', contentIdx);
        if (colonIdx == -1) return responseBody;

        // Find opening quote
        int openQuote = responseBody.indexOf('"', colonIdx + 1);
        if (openQuote == -1) return responseBody;

        // Find closing quote (handling escaped quotes)
        StringBuilder content = new StringBuilder();
        int pos = openQuote + 1;
        while (pos < responseBody.length()) {
            char c = responseBody.charAt(pos);
            if (c == '\\' && pos + 1 < responseBody.length()) {
                char next = responseBody.charAt(pos + 1);
                switch (next) {
                    case 'n' -> content.append('\n');
                    case 't' -> content.append('\t');
                    case 'r' -> content.append('\r');
                    case '"' -> content.append('"');
                    case '\\' -> content.append('\\');
                    default -> { content.append('\\'); content.append(next); }
                }
                pos += 2;
            } else if (c == '"') {
                break;
            } else {
                content.append(c);
                pos++;
            }
        }
        return content.toString();
    }

    private static String buildOpenAiBody(String model, String systemPrompt, String userContent) {
        return """
            {
              "model": "%s",
              "messages": [
                {"role": "system", "content": "%s"},
                {"role": "user", "content": "%s"}
              ],
              "temperature": 0.3,
              "thinking": {"type": "disabled"},
              "response_format": {"type": "text"}
            }
            """.formatted(
                escapeJson(model),
                escapeJson(systemPrompt),
                escapeJson(userContent)
            );
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
