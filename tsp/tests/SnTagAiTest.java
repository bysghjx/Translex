package tsp.tests;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends a paragraph in Translex's CURRENT <sN> format to AI, then analyzes
 * whether reapply (position-bound color) produces correct colors.
 *
 * This shows the exact failure mode TSP is meant to fix.
 * Run: java tsp.tests.SnTagAiTest <apiKey>
 */
public final class SnTagAiTest {

    private static final String STYLE_TAG_RE = "<s(\\d+)>(.*?)</s\\1>";

    // Mammoth "Wooly Coat" paragraph (3 lore lines merged with \n)
    // After merge: LineTemplate.fromText assigns GLOBAL IDs s0..s8
    private static final String PARAGRAPH =
            "<s0>Gain a </s0><s1>56% </s1><s2>chance for mobs to not</s2>\n" +
            "<s3>inflict </s3><s4>❄ Cold </s4><s5>when damaging you in</s5>\n" +
            "<s6>the </s6><s7>Glacite Mineshafts</s7><s8>.</s8>";

    // styleMap: ID -> (color, original content) - position-bound
    private static final Map<Integer, String[]> STYLE_MAP = new LinkedHashMap<>();
    static {
        STYLE_MAP.put(0, new String[]{"GRAY",  "Gain a "});
        STYLE_MAP.put(1, new String[]{"GREEN", "56% "});
        STYLE_MAP.put(2, new String[]{"GRAY",  "chance for mobs to not"});
        STYLE_MAP.put(3, new String[]{"GRAY",  "inflict "});
        STYLE_MAP.put(4, new String[]{"AQUA",  "❄ Cold "});   // ← special: aqua
        STYLE_MAP.put(5, new String[]{"GRAY",  "when damaging you in"});
        STYLE_MAP.put(6, new String[]{"GRAY",  "the "});
        STYLE_MAP.put(7, new String[]{"AQUA",  "Glacite Mineshafts"}); // ← special: aqua
        STYLE_MAP.put(8, new String[]{"GRAY",  "."});
    }

    private static final String SYSTEM_PROMPT =
            "You are a Minecraft item tooltip translator. Translate the text into Simplified Chinese. " +
            "<sN> tags: N is a style ID PERMANENTLY BOUND to its content. " +
            "<sN>X</sN> MUST become <sN>translated X</sN> - N stays attached to its ORIGINAL content. " +
            "NEVER move content between different tags. NEVER merge or split tags. " +
            "Reorder whole <sN>...</sN> blocks freely for natural Chinese. " +
            "Example: <s1>56%</s1> -> <s1>56%</s1>, <s4>Glacite</s4> -> <s4>冰川</s4> (NOT <s7>冰川</s7>). " +
            "Output ONLY the translated text, no markdown.";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java tsp.tests.SnTagAiTest <apiKey>");
            System.exit(1);
        }
        String apiKey = args[0];
        String apiUrl = "https://api.deepseek.com/chat/completions";
        String model = "deepseek-v4-flash";

        System.out.println("=== <sN> Format AI Test (current Translex approach) ===\n");
        System.out.println("── Original paragraph (3 lines merged) ──");
        System.out.println(PARAGRAPH);
        System.out.println("\n── styleMap (position-bound: ID = position in Component tree) ──");
        STYLE_MAP.forEach((id, vc) ->
                System.out.printf("  s%d = %-6s  originally: \"%s\"%n", id, vc[0], vc[1]));
        System.out.println();

        // Send to AI
        String body = openAiBody(model, SYSTEM_PROMPT, PARAGRAPH);
        System.out.println("── Sending to AI ... ──");
        String aiResponse = callAi(apiKey, apiKey, apiUrl, body);

        System.out.println("\n── AI Raw Response ──");
        System.out.println(aiResponse);
        System.out.println();

        // Analyze: extract <sN> tags from AI response, check if content stayed with correct ID
        analyzeTagIntegrity(aiResponse);
    }

    private static void analyzeTagIntegrity(String aiResponse) {
        System.out.println("── Tag Integrity Analysis (reapply = color by position ID) ──");

        Matcher m = Pattern.compile(STYLE_TAG_RE, Pattern.DOTALL).matcher(aiResponse);
        List<int[]> issues = new ArrayList<>();
        int tagCount = 0;

        System.out.println("  AI-returned tags:");
        while (m.find()) {
            int id = Integer.parseInt(m.group(1));
            String content = m.group(2).trim();
            tagCount++;
            String[] expected = STYLE_MAP.get(id);
            String expectedColor = expected != null ? expected[0] : "???";
            String origContent = expected != null ? expected[1].trim() : "???";

            // Check: is the content at this ID still the SAME semantic content?
            // (i.e. did AI move content to a wrong ID?)
            boolean contentMatches = contentMatches(content, origContent);
            String status = contentMatches ? "OK" : "⚠ MOVED";

            System.out.printf("    s%d [%-6s] -> \"%s\"  %s%n",
                    id, expectedColor, content, status);

            if (!contentMatches) {
                issues.add(new int[]{id});
                System.out.printf("      ↑ reapply gives %s to \"%s\", but original s%d was \"%s\" (%s)%n",
                        expectedColor, content, id, origContent, expectedColor);
            }
        }

        System.out.println();
        System.out.println("── Verdict ──");
        if (issues.isEmpty()) {
            System.out.println("  AI preserved ID<->content binding. reapply colors would be correct THIS time.");
            System.out.println("  (But this is not guaranteed - AI can move content between IDs on other runs)");
        } else {
            System.out.println("  ⚠ " + issues.size() + " tag(s) have content moved to wrong ID!");
            System.out.println("  reapply (position-bound color) would produce WRONG colors:");
            System.out.println("  - content gets the color of the POSITION (ID), not its original color");
            System.out.println("  This is exactly the bug TSP fixes (color binds to content, not position)");
        }
    }

    /** Loose check: does the translated content still relate to the original? */
    private static boolean contentMatches(String translated, String original) {
        // Numbers/symbols are strong anchors: 56% should stay with s1, ❄ with s4, etc.
        // If a number/symbol moved, that's a clear sign of content relocation.
        String[] anchors = {"56%", "❄", "Cold", "Glacite", "Mineshafts", "Gain", "inflict"};
        for (String a : anchors) {
            if (original.contains(a) && !translated.toLowerCase().contains(a.toLowerCase())
                    && !hasTranslationOf(a, translated)) {
                // original had this anchor but translated doesn't -> content may have moved
                // (but could also be legitimately translated away - only flag clear cases)
            }
        }
        // Simple heuristic: if original had a distinctive token (❄, 56%, Glacite) and
        // the translated text at this ID doesn't contain it (or its obvious translation),
        // flag it. For ❄ and 56% these are almost never translated away.
        if (original.contains("56%") && !translated.contains("56%")) return false;
        if (original.contains("❄") && !translated.contains("❄")) return false;
        if (original.contains("Glacite") && !translated.contains("Glacite") && !translated.contains("冰")) return false;
        if (original.contains("Mineshafts") && !translated.contains("Mineshafts") && !translated.contains("矿")) return false;
        return true;
    }

    private static boolean hasTranslationOf(String anchor, String translated) {
        return switch (anchor) {
            case "Glacite" -> translated.contains("冰");
            case "Mineshafts" -> translated.contains("矿");
            case "Cold" -> translated.contains("寒") || translated.contains("冷");
            default -> false;
        };
    }

    // ---- HTTP ----
    private static String callAi(String apiKey, String ignored, String apiUrl, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
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
