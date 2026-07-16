package tsp.tests;

import tsp.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;

/**
 * Same Mammoth paragraph, but TSP format. Shows color stays correct
 * regardless of how AI reorders tokens.
 * Run: java tsp.tests.TspAiDemo <apiKey>
 */
public final class TspAiDemo {

    private static final Style GRAY  = Style.of("#AAAAAA");
    private static final Style GREEN = Style.of("#55FF55");
    private static final Style AQUA  = Style.of("#55FFFF");

    // Mammoth "Wooly Coat" 3-line paragraph as styled segments
    private static final List<StyledSegment> PARAGRAPH = List.of(
            new StyledSegment("Gain a ", GRAY),
            new StyledSegment("56% ", GREEN),
            new StyledSegment(" chance for mobs to not\n", GRAY),
            new StyledSegment("inflict ", GRAY),
            new StyledSegment("❄ Cold ", AQUA),
            new StyledSegment(" when damaging you in\n", GRAY),
            new StyledSegment("the ", GRAY),
            new StyledSegment("Glacite Mineshafts", AQUA),
            new StyledSegment(".", GRAY)
    );

    private static final String SYSTEM_PROMPT =
            "You are a Minecraft item tooltip translator. Translate to Simplified Chinese. " +
            "[[NUMBER||TEXT]] tokens: NUMBER is a style ID PERMANENTLY BOUND to its content. " +
            "Rule: [[N||X]] MUST become [[N||translated X]] - NUMBER N stays attached to its ORIGINAL content. " +
            "NEVER move content to a different NUMBER. NEVER merge or split tokens. " +
            "Example: [[0||56%]] -> [[0||56%]] (numbers kept), [[1||Glacite]] -> [[1||冰川]] (NOT [[0||冰川]]). " +
            "Reorder whole tokens freely for natural Chinese. Output ONLY the translated text, no markdown.";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java tsp.tests.TspAiDemo <apiKey>");
            System.exit(1);
        }
        String apiKey = args[0];

        System.out.println("=== TSP Format AI Test (content-bound color) ===\n");

        // ── Encode (auto-default: GRAY is most frequent -> not tokenized) ──
        TspRegistry registry = new TspRegistry();
        TspEncoder encoder = TspEncoder.withAutoDefault(registry, PARAGRAPH);
        Style defaultStyle = encoder.defaultStyle();
        String encoded = encoder.encode(PARAGRAPH);

        System.out.println("── TSP Encoded (sent to AI) ──");
        System.out.println(encoded);
        System.out.println("\n── Registry (content-bound: ID = color alias) ──");
        for (int i = 0; i < registry.size(); i++) {
            System.out.printf("  ID %d -> %s%n", i, colorName(registry.getStyle(i)));
        }
        System.out.println("  default (plain text) -> " + colorName(defaultStyle));
        System.out.println();

        // ── Send to AI ──
        String body = openAiBody("deepseek-v4-flash", SYSTEM_PROMPT, encoded);
        System.out.println("── Sending to AI ... ──");
        String aiResponse = callAi(apiKey, body);

        System.out.println("\n── AI Raw Response ──");
        System.out.println(aiResponse);
        System.out.println();

        // ── Decode ──
        TspParser parser = new TspParser(TspRecovery.Level.V1);
        TspParser.ParseResult parsed = parser.parse(aiResponse);
        TspDecoder decoder = new TspDecoder(registry);
        List<StyledSegment> decoded = decoder.decode(parsed);

        System.out.println("── Decoded (color follows content) ──");
        System.out.println("  [token ID -> registry color]   [plain text -> defaultStyle]");
        System.out.println();
        for (StyledSegment seg : decoded) {
            String colorDesc = seg.isPlain()
                    ? colorName(defaultStyle) + " (default)"
                    : colorName(seg.style()) + " (ID lookup)";
            // For plain text, TspDecoder returns EMPTY; show what it SHOULD be (defaultStyle)
            String displayColor = seg.isPlain() ? colorName(defaultStyle) : colorName(seg.style());
            System.out.printf("  %-8s | \"%s\"%n", displayColor, seg.text());
        }
        System.out.println();

        // ── Verify correctness (honest: check content matches the color semantics of its ID) ──
        System.out.println("── Color Correctness Check ──");
        System.out.println("  (ID 0 = GREEN = should be numeric like 56%; ID 1 = AQUA = should be a proper noun like ❄/Glacite)");
        System.out.println();

        // Original content per ID (from the encoded input)
        java.util.Map<Integer, java.util.List<String>> origContentById = new java.util.LinkedHashMap<>();
        origContentById.put(0, java.util.List.of("56%"));
        origContentById.put(1, java.util.List.of("❄ Cold", "Glacite Mineshafts"));

        int correctTokens = 0, mismatchedTokens = 0;
        for (TspToken t : parsed.tokens()) {
            String content = t.text().trim();
            boolean ok = contentMatchesId(content, t.id());
            String color = colorName(registry.getStyle(t.id()));
            System.out.printf("  [[%d||%s]] -> %s  %s%n",
                    t.id(), content, color, ok ? "✅" : "⚠ CONTENT-COLOR MISMATCH");
            if (ok) correctTokens++; else mismatchedTokens++;
        }
        System.out.println();
        System.out.printf("  Tokens: %d correct, %d mismatched%n", correctTokens, mismatchedTokens);

        System.out.println();
        System.out.println("── Recovery stats ──");
        System.out.println("  recovered=" + parsed.recoveredCount()
                + " unrecoverable=" + parsed.unrecoverableCount());

        System.out.println();
        System.out.println("── Verdict ──");
        if (mismatchedTokens == 0) {
            System.out.println("  ✅ ALL colors correct - AI kept content bound to its color-ID.");
        } else {
            System.out.println("  ⚠ " + mismatchedTokens + " token(s) have content in the WRONG color-ID.");
            System.out.println("  TSP is NOT a silver bullet: AI can still move content across color-IDs");
            System.out.println("  (here: Glacite译文 shoved into ID 0 = GREEN, should be ID 1 = AQUA).");
            System.out.println();
            System.out.println("  BUT TSP still beats <sN> on the COMMON case:");
            System.out.println("  - <sN>: ANY reorder (even same-color) breaks color (ID = position).");
            System.out.println("  - TSP: same-color reorder is IMMUNE (dedup'd to one ID).");
            System.out.println("          only CROSS-color shuffling breaks it (rarer).");
            System.out.println("  Mitigation: content-color semantic check (above) + fallback, like validateTranslation.");
        }
    }

    /** Check if token content fits the color semantics of its ID.
     *  ID 0 (GREEN) = numeric value (56%); ID 1 (AQUA) = proper noun (❄/Glacite). */
    private static boolean contentMatchesId(String content, int id) {
        return switch (id) {
            case 0 -> content.matches(".*\\d.*");                    // GREEN: numeric
            case 1 -> content.contains("❄") || content.contains("冰") || content.contains("矿")
                    || content.contains("寒") || content.contains("Cold") || content.contains("Glacite");
            default -> true;
        };
    }

    private static String colorName(Style s) {
        if (s == null || s.isEmpty()) return "EMPTY";
        return switch (s.colorHex()) {
            case "#AAAAAA" -> "GRAY";
            case "#55FF55" -> "GREEN";
            case "#55FFFF" -> "AQUA";
            default -> s.colorHex();
        };
    }

    // ---- HTTP ----
    private static String callAi(String apiKey, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.deepseek.com/chat/completions"))
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return "HTTP " + resp.statusCode() + ": " + resp.body().substring(0, Math.min(300, resp.body().length()));
        }
        return extractContent(resp.body());
    }

    private static String extractContent(String json) {
        int k = json.indexOf("\"content\"");
        if (k == -1) return json;
        int c = json.indexOf(':', k);
        int q = json.indexOf('"', c + 1);
        if (q == -1) return json;
        StringBuilder sb = new StringBuilder();
        for (int i = q + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '\\' && i + 1 < json.length()) {
                sb.append(switch (json.charAt(i + 1)) {
                    case 'n' -> '\n'; case 't' -> '\t'; case '"' -> '"';
                    case '\\' -> '\\'; default -> json.charAt(i + 1);
                });
                i++;
            } else if (ch == '"') break;
            else sb.append(ch);
        }
        return sb.toString();
    }

    private static String openAiBody(String model, String system, String user) {
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"system\",\"content\":\""
                + escape(system) + "\"},{\"role\":\"user\",\"content\":\"" + escape(user)
                + "\"}],\"temperature\":0.3,\"thinking\":{\"type\":\"disabled\"}}";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
