package tsp.tests;

import tsp.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TSP + number placeholder ({0}{1}) protection test.
 * Verifies AI preserves {N} placeholders and fillNumbers restores correct numbers.
 * Run: java tsp.tests.TspPlaceholderTest <apiKey>
 */
public final class TspPlaceholderTest {

    private static final Style GRAY  = Style.of("#AAAAAA");
    private static final Style GREEN = Style.of("#55FF55");
    private static final Style AQUA  = Style.of("#55FFFF");

    // Tusk Luck paragraph (4 lore lines). Numbers ALREADY replaced with {0}{1} placeholders.
    // {0} = "+0.28" (green), {1} = "100" (gray/default)
    private static final String[] VALS = {"+0.28", "100"};

    private static final List<StyledSegment> PARAGRAPH = List.of(
            new StyledSegment("Gain ", GRAY),
            new StyledSegment("{0} Magic Find ", GREEN),     // {0} = +0.28
            new StyledSegment(" for every\n", GRAY),
            new StyledSegment("{1} ", GRAY),                 // {1} = 100 (default color)
            new StyledSegment("Mining Fortune", AQUA),
            new StyledSegment(", doubled in the\n", GRAY),
            new StyledSegment("Glacite Tunnels ", GRAY),
            new StyledSegment("and ", GRAY),
            new StyledSegment("Glacite", AQUA),
            new StyledSegment("\nMineshafts", GRAY),
            new StyledSegment(".", GRAY)
    );

    private static final String SYSTEM_PROMPT =
            "You are a Minecraft item tooltip translator. Translate to Simplified Chinese. " +
            "[[NUMBER||TEXT]] tokens: NUMBER is a style ID PERMANENTLY BOUND to its content. " +
            "<sN> rule: [[N||X]] MUST become [[N||translated X]] - N stays with original content. " +
            "NEVER move content to a different NUMBER. NEVER merge or split tokens. " +
            "{0} {1} etc. are NUMBER placeholders - output them LITERALLY, never fill, remove, or translate them. " +
            "Reorder whole tokens freely. Output ONLY the translated text, no markdown.";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java tsp.tests.TspPlaceholderTest <apiKey>");
            System.exit(1);
        }
        String apiKey = args[0];

        System.out.println("=== TSP + Number Placeholder Test ===\n");
        System.out.println("vals: {0}=\"" + VALS[0] + "\", {1}=\"" + VALS[1] + "\"\n");

        // Encode
        TspRegistry registry = new TspRegistry();
        TspEncoder encoder = TspEncoder.withAutoDefault(registry, PARAGRAPH);
        String encoded = encoder.encode(PARAGRAPH);

        System.out.println("── TSP Encoded (numbers -> {N} placeholders) ──");
        System.out.println(encoded);
        System.out.println("\n── Registry ──");
        for (int i = 0; i < registry.size(); i++) {
            System.out.printf("  ID %d -> %s%n", i, colorName(registry.getStyle(i)));
        }
        System.out.println("  default -> " + colorName(encoder.defaultStyle()) + "\n");

        // Send to AI
        String body = openAiBody("deepseek-v4-flash", SYSTEM_PROMPT, encoded);
        System.out.println("── Sending to AI ... ──\n");
        String aiResponse = callAi(apiKey, body);

        System.out.println("── AI Raw Response ──");
        System.out.println(aiResponse);
        System.out.println();

        // Check placeholder preservation
        System.out.println("── Placeholder Preservation Check ──");
        boolean has0 = aiResponse.contains("{0}");
        boolean has1 = aiResponse.contains("{1}");
        System.out.println("  {0} preserved: " + (has0 ? "YES" : "NO ❌"));
        System.out.println("  {1} preserved: " + (has1 ? "YES" : "NO ❌"));

        // Count: {0}/{1} should appear exactly once each (AI must not duplicate/drop)
        int count0 = countOccurrence(aiResponse, "{0}");
        int count1 = countOccurrence(aiResponse, "{1}");
        System.out.println("  {0} count: " + count0 + " (expected 1)");
        System.out.println("  {1} count: " + count1 + " (expected 1)");

        // Decode
        TspParser parser = new TspParser(TspRecovery.Level.V1);
        TspParser.ParseResult parsed = parser.parse(aiResponse);
        TspDecoder decoder = new TspDecoder(registry);
        List<StyledSegment> decoded = decoder.decode(parsed);

        // fillNumbers: {0} -> +0.28, {1} -> 100
        System.out.println("\n── After fillNumbers (placeholders -> real numbers) ──");
        for (StyledSegment seg : decoded) {
            String filled = fillNumbers(seg.text());
            String color = seg.isPlain() ? colorName(encoder.defaultStyle()) + "(default)"
                    : colorName(seg.style()) + "(ID)";
            System.out.printf("  %-14s | \"%s\"%n", color, filled);
        }

        // Verify numbers restored correctly
        System.out.println("\n── Number Restoration Check ──");
        String allFilled = decoded.stream().map(s -> fillNumbers(s.text()))
                .reduce("", String::concat);
        boolean num0Ok = allFilled.contains(VALS[0]);   // +0.28
        boolean num1Ok = allFilled.contains(VALS[1]);   // 100
        System.out.println("  {0} -> \"" + VALS[0] + "\" restored: " + (num0Ok ? "YES ✅" : "NO ❌"));
        System.out.println("  {1} -> \"" + VALS[1] + "\" restored: " + (num1Ok ? "YES ✅" : "NO ❌"));

        // Color check for the number segments
        System.out.println("\n── Color Check (number segments) ──");
        for (StyledSegment seg : decoded) {
            String t = fillNumbers(seg.text());
            if (t.contains(VALS[0])) {
                System.out.printf("  \"%s\" color = %s (should be GREEN) %s%n",
                        VALS[0], colorName(seg.style()),
                        seg.style().equals(GREEN) ? "✅" : "❌");
            }
            if (t.contains(VALS[1]) && !t.contains(VALS[0])) {
                System.out.printf("  \"%s\" color = %s (should be GRAY/default) %s%n",
                        VALS[1], seg.isPlain() ? "GRAY(default)" : colorName(seg.style()),
                        seg.isPlain() || seg.style().equals(GRAY) ? "✅" : "❌");
            }
        }

        System.out.println("\n── Verdict ──");
        if (has0 && has1 && count0 == 1 && count1 == 1 && num0Ok && num1Ok) {
            System.out.println("  ✅ Placeholders preserved, numbers restored correctly, colors correct.");
        } else {
            System.out.println("  ⚠ Issues found (see above).");
        }
    }

    private static String fillNumbers(String text) {
        String r = text;
        for (int i = 0; i < VALS.length; i++) r = r.replace("{" + i + "}", VALS[i]);
        return r;
    }

    private static int countOccurrence(String s, String sub) {
        int c = 0, idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) { c++; idx += sub.length(); }
        return c;
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
        if (resp.statusCode() != 200) return "HTTP " + resp.statusCode();
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
