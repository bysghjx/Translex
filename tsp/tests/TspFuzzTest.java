package tsp.tests;

import tsp.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fuzz test: randomly generate styled text, send to AI in bulk, measure token accuracy.
 *
 * <p>Generates Hypixel-like patterns (stats, skill names, descriptions) with random
 * color assignments, encodes in TSP, packs into a JSON dictionary, sends to AI,
 * then verifies every token was preserved correctly.</p>
 *
 * <p>Usage: java tsp.tests.TspFuzzTest &lt;apiKey&gt; [rounds] [apiUrl] [model]</p>
 */
public final class TspFuzzTest {

    // ---- Color palette (Hypixel-accurate) ----
    private static final Style GRAY   = Style.of("#AAAAAA");
    private static final Style GREEN  = Style.of("#55FF55");
    private static final Style AQUA   = Style.of("#55FFFF");
    private static final Style GOLD   = Style.of("#FFAA00");
    private static final Style RED    = Style.of("#FF5555");
    private static final Style WHITE  = Style.of("#FFFFFF");
    private static final Style YELLOW = Style.of("#FFFF55");
    private static final Style BLUE   = Style.of("#5555FF");

    private static final Style[] PALETTE = {GRAY, GREEN, AQUA, GOLD, RED, WHITE, YELLOW, BLUE};
    private static final String[] COLORS = {"GRAY","GREEN","AQUA","GOLD","RED","WHITE","YELLOW","BLUE"};

    // ---- Hypixel term pools for realistic generation ----
    private static final String[] STATS = {"Damage", "Strength", "Crit Damage", "Defense", "Health",
            "Intelligence", "Speed", "Ferocity", "Mining Speed", "Cold Resistance"};
    private static final String[] SKILLS = {"Wooly Coat", "Tusk Luck", "Corpse Crusher", "Double Jump",
            "Strong Arm", "Mining Speed Boost", "Frozen Body", "Glacial Shield"};
    private static final String[] SPECIALS = {"Glacite", "Mineshafts", "Cold", "Magic Find",
            "Mining Fortune", "Pristine", "Frozen Corpse", "Tunnels"};
    private static final String[] VALUES = {"+150", "+30%", "56%", "+0.28", "+16.8", "100", "x2",
            "+40%", "{0}", "+5.6", "+250%", "+1.2k"};
    private static final String[] PROSE = {"Gain ", " for every ", " chance for ", " when ",
            " in your ", " current ", " looted in ", " doubled in the ",
            " and ", " gives ", " pet exp for ", "Progress to Level ", ": ",
            " chance to receive ", " inflicts ", " when damaging you in ",
            " the ", " reduces damage by ", " increases "};

    private static final ThreadLocalRandom rng = ThreadLocalRandom.current();

    // ---- Statistics ----
    private int totalTokensSent = 0;
    private int totalTokensPreserved = 0;
    private int totalTokensLost = 0;
    private int totalTokensModified = 0; // ID preserved, text changed
    private int totalPatterns = 0;
    private int perfectPatterns = 0;
    private int totalCalls = 0;

    // ---- Config ----
    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final HttpClient client;
    private final int patternsPerBatch = 10;

    public TspFuzzTest(String apiKey, String apiUrl, String model) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.model = model;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("Usage: java tsp.tests.TspFuzzTest <apiKey> [rounds] [apiUrl] [model]");
            System.exit(1);
        }
        String apiKey = args[0];
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        String apiUrl = args.length > 2 ? args[2] : "https://api.deepseek.com/chat/completions";
        String model  = args.length > 3 ? args[3] : "deepseek-v4-flash";

        System.out.println("=== TSP Fuzz Test ===");
        System.out.println("Rounds: " + rounds + "  Patterns/round: 10  Total: " + (rounds * 10));
        System.out.println("API: " + apiUrl + "  Model: " + model);
        System.out.println();

        TspFuzzTest test = new TspFuzzTest(apiKey, apiUrl, model);

        for (int r = 1; r <= rounds; r++) {
            System.out.println("── Round " + r + "/" + rounds + " ──");
            test.runRound(r);
            System.out.println();
        }

        test.printSummary(rounds * 10);
    }

    private void runRound(int roundNum) throws Exception {
        // Generate patterns
        List<Pattern> patterns = new ArrayList<>();
        for (int i = 0; i < patternsPerBatch; i++) {
            patterns.add(generatePattern(i));
        }

        // Print what we're about to send
        for (Pattern p : patterns) {
            System.out.printf("  [%d] %s -> reg=%s%n", p.id, p.label, p.registryDesc());
            System.out.printf("      TSP: %s%n", p.tspEncoded);
        }

        // Build JSON dictionary payload
        StringBuilder dict = new StringBuilder("{");
        for (int i = 0; i < patterns.size(); i++) {
            if (i > 0) dict.append(",");
            dict.append("\"").append(i).append("\":").append(jsonStr(patterns.get(i).tspEncoded));
        }
        dict.append("}");
        String payload = dict.toString();

        // Send to AI
        String systemPrompt = """
            You are a Minecraft item tooltip translator. Return a JSON object.
            Translate each value to Simplified Chinese.
            [[NUMBER||TEXT]] tokens MUST be preserved exactly:
            - Keep [[, ]], ||, and the NUMBER unchanged.
            - Translate only the TEXT inside each token.
            - Do NOT add or remove tokens.
            - Reorder tokens freely for natural Chinese.
            Output ONLY the JSON object, no markdown.""";

        String body = openAiBody(systemPrompt, payload);
        long start = System.currentTimeMillis();
        String raw = callAi(body);
        long ms = System.currentTimeMillis() - start;

        // Parse JSON response
        Map<Integer, String> responses = parseDict(raw);
        if (responses.isEmpty()) {
            System.out.println("  ❌ Failed to parse AI response! Raw: " + truncate(raw, 200));
            return;
        }

        int preservedThisRound = 0;
        int totalThisRound = 0;

        // Verify each pattern
        for (Pattern p : patterns) {
            String aiText = responses.get(p.id);
            if (aiText == null) {
                System.out.printf("  [%d] ❌ MISSING in response%n", p.id);
                continue;
            }

            totalPatterns++;
            totalThisRound += p.tokenCount;

            // Parse AI output
            TspParser parser = new TspParser();
            TspParser.ParseResult parsed = parser.parse(aiText);

            // Check token integrity: IDs preserved, count matches, content translated
            Set<Integer> expectedIds = new HashSet<>();
            for (TspToken t : p.originalTokens) expectedIds.add(t.id());

            Set<Integer> actualIds = new LinkedHashSet<>();
            boolean hasError = parsed.hasErrors();
            int idMismatch = 0;
            int textNotTranslated = 0;

            for (TspToken t : parsed.tokens()) {
                actualIds.add(t.id());
                if (!expectedIds.contains(t.id())) idMismatch++;
                // Check TEXT was actually translated (not identical to any original text)
                boolean translated = true;
                for (TspToken orig : p.originalTokens) {
                    if (orig.id() == t.id() && orig.text().equals(t.text())) {
                        translated = false;
                        break;
                    }
                }
                if (!translated) textNotTranslated++;
            }

            // A token is "preserved" if: ID exists in expected set + count matches
            int expectedCount = p.tokenCount;
            int actualCount = parsed.tokens().size();
            boolean idsMatch = expectedIds.equals(actualIds);
            boolean countMatch = expectedCount == actualCount;
            int preserved;

            if (idsMatch && countMatch && !hasError && idMismatch == 0) {
                preserved = p.tokenCount;
            } else {
                preserved = 0;
                for (TspToken t : parsed.tokens()) {
                    if (expectedIds.contains(t.id())) preserved++;
                }
                preserved = Math.min(preserved, p.tokenCount);
            }

            preservedThisRound += preserved;
            totalTokensSent += p.tokenCount;
            totalTokensPreserved += preserved;
            totalTokensLost += (p.tokenCount - preserved);
            if (parsed.hasErrors()) totalTokensModified++;

            if (preserved == p.tokenCount && !hasError && idMismatch == 0) {
                perfectPatterns++;
                System.out.printf("  [%d] ✅ PERFECT (%d/%d tokens — IDs match, content translated)%n",
                        p.id, preserved, p.tokenCount);
            } else {
                System.out.printf("  [%d] ⚠ %d/%d tokens preserved (idsMatch=%s countMatch=%s idWrong=%d notTrans=%d)%n",
                        p.id, preserved, p.tokenCount, idsMatch, countMatch, idMismatch, textNotTranslated);
                if (hasError) System.out.printf("      PARSE ERRORS: %s%n", parsed.errors());
                System.out.printf("      Sent IDs: %s -> Got IDs: %s%n", expectedIds, actualIds);
                System.out.printf("      AI raw:   %s%n", truncate(aiText, 120));
            }
        }

        totalCalls++;
        System.out.printf("  Round score: %d/%d tokens preserved (%.0f%%), %dms%n",
                preservedThisRound, totalThisRound,
                totalThisRound > 0 ? 100.0 * preservedThisRound / totalThisRound : 0, ms);
    }

    private void printSummary(int total) {
        System.out.println("══════════════════════════════════════");
        System.out.println("  Fuzz Test Summary");
        System.out.println("══════════════════════════════════════");
        System.out.printf("  API calls:        %d%n", totalCalls);
        System.out.printf("  Patterns tested:  %d%n", totalPatterns);
        System.out.printf("  Perfect patterns: %d/%d (%.0f%%)%n",
                perfectPatterns, totalPatterns,
                totalPatterns > 0 ? 100.0 * perfectPatterns / totalPatterns : 0);
        System.out.printf("  Tokens sent:      %d%n", totalTokensSent);
        System.out.printf("  Tokens preserved: %d/%d (%.1f%%)%n",
                totalTokensPreserved, totalTokensSent,
                totalTokensSent > 0 ? 100.0 * totalTokensPreserved / totalTokensSent : 0);
        System.out.printf("  Tokens lost:      %d%n", totalTokensLost);
        System.out.printf("  Parse errors:     %d%n", totalTokensModified);
    }

    // ================================================================
    // Pattern generation
    // ================================================================

    private record Pattern(int id, String label, String tspEncoded, String registryDesc,
                           List<TspToken> originalTokens, int tokenCount) {}

    private Pattern generatePattern(int id) {
        int type = rng.nextInt(6);
        List<StyledSegment> segments = switch (type) {
            case 0 -> genStatLine();
            case 1 -> genSkillParagraph();
            case 2 -> genNameWithPrefix();
            case 3 -> genStatWithPlaceholder();
            case 4 -> genMixedShort();
            default -> genDescription();
        };

        TspRegistry reg = new TspRegistry();
        TspEncoder enc = TspEncoder.withAutoDefault(reg, segments);
        String tsp = enc.encode(segments);

        // Collect original tokens from encoded output for verification
        TspParser checkParser = new TspParser();
        List<TspToken> tokens = checkParser.parse(tsp).tokens();

        String label = segments.stream()
                .filter(s -> !s.isPlain())
                .map(s -> s.text().replace("\n", "\\n"))
                .limit(3)
                .reduce((a, b) -> a + " | " + b)
                .orElse("???");

        // Describe registry
        StringBuilder rd = new StringBuilder("{");
        for (int i = 0; i < reg.size(); i++) {
            if (i > 0) rd.append(",");
            rd.append(i).append("=").append(colorName(reg.getStyle(i)));
        }
        rd.append("}");

        return new Pattern(id, truncate(label, 60), tsp, rd.toString(), tokens, tokens.size());
    }

    /** e.g. Damage: +150 (+30%) */
    private List<StyledSegment> genStatLine() {
        Style valColor = pickNonGray();
        Style pctColor = (rng.nextBoolean() && !valColor.equals(GREEN)) ? GREEN : pickNonGray();
        return List.of(
                seg(GRAY, pick(STATS) + ": "),
                seg(valColor, pick(VALUES)),
                seg(GRAY, " ("),
                seg(pctColor, pick(VALUES)),
                seg(GRAY, ")")
        );
    }

    /** e.g. Wooly Coat\nGain a 56% chance for... */
    private List<StyledSegment> genSkillParagraph() {
        Style termColor = pick(AQUA, BLUE);
        Style valColor = pick(GREEN, YELLOW, RED);
        String skill = pick(SKILLS);
        String special = pick(SPECIALS);
        String value = pick(VALUES);

        int lines = rng.nextInt(2, 4);
        List<StyledSegment> segs = new ArrayList<>();
        segs.add(seg(GOLD, skill));

        // First line of description
        segs.add(seg(GRAY, "\n" + pick(PROSE)));
        segs.add(seg(valColor, value));
        segs.add(seg(GRAY, pick(PROSE)));

        // Middle line(s)
        for (int i = 1; i < lines - 1; i++) {
            segs.add(seg(GRAY, pick(PROSE)));
            if (rng.nextBoolean()) segs.add(seg(termColor, special));
        }

        // Last line
        segs.add(seg(GRAY, pick(PROSE)));
        segs.add(seg(termColor, special));
        segs.add(seg(GRAY, pick(new String[]{" the ", " for ", "."})));

        return segs;
    }

    /** e.g. [Lvl 56] Mammoth */
    private List<StyledSegment> genNameWithPrefix() {
        return List.of(
                seg(GRAY, "[Lvl " + rng.nextInt(1, 200) + "] "),
                seg(GOLD, pick(SKILLS))
        );
    }

    /** e.g. Cold Resistance: {0} */
    private List<StyledSegment> genStatWithPlaceholder() {
        return List.of(
                seg(GRAY, pick(STATS) + ": "),
                seg(WHITE, "{0}")
        );
    }

    /** Short mixed: skill name + one stat */
    private List<StyledSegment> genMixedShort() {
        Style valColor = pickNonGray();
        return List.of(
                seg(GOLD, pick(SKILLS)),
                seg(valColor, "\n" + pick(VALUES)),
                seg(GRAY, " " + pick(STATS))
        );
    }

    /** Plain prose with embedded values/specials */
    private List<StyledSegment> genDescription() {
        Style valColor = pick(GREEN, YELLOW);
        Style termColor = pick(AQUA, BLUE);
        String special = pick(SPECIALS);
        String value = pick(VALUES);

        return List.of(
                seg(GRAY, pick(PROSE)),
                seg(valColor, value),
                seg(GRAY, pick(PROSE)),
                seg(termColor, special),
                seg(GRAY, pick(new String[]{" the ", ".", " in your ", " current "}))
        );
    }

    // ================================================================
    // HTTP
    // ================================================================

    private String callAi(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return "{\"error\":\"HTTP " + resp.statusCode() + "\"}";
        }
        // Extract content from choices[0].message.content
        return extractJsonStr(resp.body(), "content");
    }

    private String openAiBody(String systemPrompt, String userContent) {
        return "{\"model\":\"" + escape(model)
                + "\",\"messages\":[{\"role\":\"system\",\"content\":\"" + escape(systemPrompt)
                + "\"},{\"role\":\"user\",\"content\":\"" + escape(userContent)
                + "\"}],\"temperature\":0.3,\"thinking\":{\"type\":\"disabled\"}}";
    }

    /** Parse JSON dict response: {"0":"...", "1":"..."} */
    private static Map<Integer, String> parseDict(String raw) {
        Map<Integer, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return map;
        try {
            // Crude but works: extract "0":"value", "1":"value", etc.
            int i = raw.indexOf('{');
            if (i == -1) return map;
            int depth = 0;
            StringBuilder key = new StringBuilder();
            StringBuilder val = new StringBuilder();
            boolean inKey = false, inVal = false, inStr = false;
            String currentKey = null;

            for (int pos = i + 1; pos < raw.length(); pos++) {
                char c = raw.charAt(pos);
                if (c == '}' && !inStr && depth == 0) {
                    if (currentKey != null) {
                        try { map.put(Integer.parseInt(currentKey), unescape(val.toString())); }
                        catch (NumberFormatException ignored) {}
                    }
                    break;
                }
                if (inStr) {
                    if (c == '\\' && pos + 1 < raw.length()) {
                        char n = raw.charAt(pos + 1);
                        val.append(switch (n) { case 'n' -> '\n'; case 't' -> '\t'; case '"' -> '"';
                            case '\\' -> '\\'; default -> n; });
                        pos++;
                    } else if (c == '"') {
                        inStr = false;
                        if (inKey) { currentKey = val.toString(); val.setLength(0); inKey = false; }
                        else if (inVal) {
                            try { map.put(Integer.parseInt(currentKey), unescape(val.toString())); }
                            catch (NumberFormatException ignored) {}
                            val.setLength(0); inVal = false; currentKey = null;
                        }
                    } else val.append(c);
                } else if (c == '"') {
                    inStr = true;
                    if (currentKey == null) inKey = true; else inVal = true;
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    private static String extractJsonStr(String json, String field) {
        int k = json.indexOf("\"" + field + "\"");
        if (k == -1) return "";
        int c = json.indexOf(':', k);
        if (c == -1) return "";
        int q = json.indexOf('"', c + 1);
        if (q == -1) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = q + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '\\' && i + 1 < json.length()) {
                sb.append(switch (json.charAt(i + 1)) {
                    case 'n' -> '\n'; case 't' -> '\t'; case '"' -> '"';
                    case '\\' -> '\\'; default -> json.charAt(i + 1); });
                i++;
            } else if (ch == '"') break;
            else sb.append(ch);
        }
        return sb.toString();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static StyledSegment seg(Style s, String text) { return StyledSegment.styled(text, s); }

    @SafeVarargs private static <T> T pick(T... items) { return items[rng.nextInt(items.length)]; }

    private Style pickNonGray() {
        Style s;
        do { s = PALETTE[rng.nextInt(PALETTE.length)]; } while (s.equals(GRAY));
        return s;
    }

    private String colorName(Style s) {
        for (int i = 0; i < PALETTE.length; i++) {
            if (PALETTE[i].equals(s)) return COLORS[i];
        }
        return s.colorHex();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"");
    }

    private static String jsonStr(String s) { return "\"" + escape(s) + "\""; }
}
