package tsp.tests;

import tsp.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 3 对比：Translex 现有 <sN> 方案（位置 ID，不 dedup）大规模测试。
 *
 * <p>与 {@link TspLoreAiTest} 对比：同样 50 物品 / 强 prompt / Full 标签化，
 * 区别在编码语义：
 * <ul>
 *   <li>TSP：ID = 颜色别名，同色 dedup（❄Cold + Glacite 同青 = ID 1）</li>
 *   <li>sN（本测试）：ID = 位置序号，每段不同 ID（s4 ≠ s7，即使同青）</li>
 * </ul>
 *
 * <p>这测的是 Translex 现有 LineTemplate 行为。预期：位置 ID 下 AI 跨标签挪内容
 * 会更频繁地导致颜色错（因为同色挪动在 sN 也算错位，TSP 则免疫）。</p>
 *
 * <p>Run: java tsp.tests.TspLoreSnTest &lt;apiKey&gt; [sampleSize] [batchSize]
 */
public final class TspLoreSnTest {

    private static final Pattern NUMBER = Pattern.compile(
            "[\\d.,+%kmb\\-s()]*\\d[\\d.,+%kmb\\-s()]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SN_TAG = Pattern.compile("<s(\\d+)>(.*?)</s\\1>", Pattern.DOTALL);
    private static final Pattern ANCHOR = Pattern.compile(
            "\\d[\\d.,+%kmb\\-s()]*|[A-Za-z]{3,}|[❄✿♦★✦♥♣♠]");

    private static final String SYSTEM_PROMPT =
            "You are a Minecraft item tooltip translator. Translate to Simplified Chinese. " +
            "<sN> tags: N is a style ID PERMANENTLY BOUND to its content. " +
            "<sN>X</sN> MUST become <sN>translated X</sN> - N stays attached to its ORIGINAL content. " +
            "NEVER move content between different tags. NEVER merge or split tags. " +
            "{0} {1} etc. are number placeholders - output them LITERALLY, never fill or remove. " +
            "Reorder whole <sN>...</sN> blocks freely for natural Chinese. " +
            "Example: <s1>56%</s1> -> <s1>56%</s1>, <s4>Glacite</s4> -> <s4>冰川</s4> (NOT <s7>冰川</s7>). " +
            "Output ONLY the JSON object, no markdown.";

    private static int totalTokensSent = 0;
    private static int totalTokensPreserved = 0;
    private static int totalColorCorrect = 0;
    private static int totalColorWrong = 0;
    private static int totalParagraphs = 0;
    private static int perfectParagraphs = 0;
    private static int totalBatches = 0;
    // token 消耗 + prompt 长度
    private static long totalPromptTokens = 0;
    private static long totalCompletionTokens = 0;
    private static long totalPayloadChars = 0;

    private final String apiKey;
    private final HttpClient client;

    public TspLoreSnTest(String apiKey) {
        this.apiKey = apiKey;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java tsp.tests.TspLoreSnTest <apiKey> [sampleSize] [batchSize]");
            System.exit(1);
        }
        String apiKey = args[0];
        int sampleSize = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        int batchSize = args.length > 2 ? Integer.parseInt(args[2]) : 10;

        System.out.println("=== <sN> Lore AI Test (position-ID, strong prompt) ===");
        System.out.println("Sample: " + sampleSize + " items, batch: " + batchSize + "\n");

        List<TspLoreLoader.ItemData> items = TspLoreLoader.load(Path.of("tsp/data/lore.jsonl"));
        List<TspLoreLoader.ItemData> sample = sample(items, sampleSize);
        System.out.println("Sampled " + sample.size() + " items (pet-prioritized)\n");

        TspLoreSnTest test = new TspLoreSnTest(apiKey);
        test.run(sample, batchSize);
        test.printSummary();
    }

    private static List<TspLoreLoader.ItemData> sample(List<TspLoreLoader.ItemData> items, int n) {
        List<TspLoreLoader.ItemData> pets = new ArrayList<>();
        List<TspLoreLoader.ItemData> others = new ArrayList<>();
        for (var it : items) {
            if (it.itemId().contains("PET")) pets.add(it);
            else others.add(it);
        }
        Collections.shuffle(others, new Random(42));
        List<TspLoreLoader.ItemData> sample = new ArrayList<>();
        sample.addAll(pets.subList(0, Math.min(pets.size(), n / 2)));
        sample.addAll(others.subList(0, Math.min(others.size(), n - sample.size())));
        return sample;
    }

    private record Paragraph(List<List<StyledSegment>> lines) {}

    private static List<Paragraph> group(List<List<StyledSegment>> lines) {
        List<Paragraph> groups = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String text = lineText(lines.get(i));
            if (text.contains(":") || text.contains("：") || i == 0) {
                groups.add(new Paragraph(List.of(lines.get(i))));
                i++;
            } else {
                List<List<StyledSegment>> para = new ArrayList<>();
                while (i < lines.size()) {
                    String t = lineText(lines.get(i));
                    if (t.contains(":") || t.contains("：")) break;
                    para.add(lines.get(i));
                    i++;
                    if (para.size() >= 4) break;
                }
                groups.add(new Paragraph(para));
            }
        }
        return groups;
    }

    private static String lineText(List<StyledSegment> segs) {
        StringBuilder sb = new StringBuilder();
        for (var s : segs) sb.append(s.text());
        return sb.toString();
    }

    /** sN 编码：位置 ID（每段递增，不 dedup）。 */
    private record EncodedParagraph(
            String sn, Map<String, Integer> placeholderToId, int tokenCount,
            List<String> vals) {}

    private static EncodedParagraph encodeParagraph(Paragraph para) {
        List<StyledSegment> merged = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        for (int i = 0; i < para.lines.size(); i++) {
            if (i > 0) merged.add(StyledSegment.plain("\n"));
            for (var seg : para.lines.get(i)) {
                String text = seg.text();
                if (NUMBER.matcher(text).matches()) {
                    merged.add(new StyledSegment("{" + vals.size() + "}", seg.style()));
                    vals.add(text);
                } else {
                    merged.add(seg);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        Map<String, Integer> placeholderToId = new LinkedHashMap<>();
        int n = 0;
        for (var seg : merged) {
            if (seg.isPlain()) {
                sb.append(seg.text());
            } else {
                sb.append("<s").append(n).append(">").append(seg.text()).append("</s").append(n).append(">");
                Matcher pm = Pattern.compile("\\{\\d+\\}").matcher(seg.text());
                while (pm.find()) placeholderToId.put(pm.group(), n);
                n++;
            }
        }
        return new EncodedParagraph(sb.toString(), placeholderToId, n, vals);
    }

    private void run(List<TspLoreLoader.ItemData> sample, int batchSize) throws Exception {
        List<EncodedParagraph> all = new ArrayList<>();
        for (var item : sample) {
            for (var para : group(item.lines())) {
                all.add(encodeParagraph(para));
            }
        }
        System.out.println("Encoded " + all.size() + " paragraphs, "
                + all.stream().mapToInt(e -> e.tokenCount).sum() + " tokens total\n");

        for (int i = 0; i < all.size(); i += batchSize) {
            int end = Math.min(i + batchSize, all.size());
            processBatch(all.subList(i, end), ++totalBatches);
        }
    }

    private void processBatch(List<EncodedParagraph> batch, int batchNum) throws Exception {
        StringBuilder dict = new StringBuilder("{");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) dict.append(",");
            dict.append("\"").append(i).append("\":").append(jsonStr(batch.get(i).sn));
        }
        dict.append("}");

        long start = System.currentTimeMillis();
        String payload = dict.toString();
        totalPayloadChars += payload.length();
        String raw = callAi(SYSTEM_PROMPT, payload);
        long ms = System.currentTimeMillis() - start;

        Map<Integer, String> responses = parseDict(raw);

        int batchTokensSent = 0, batchPreserved = 0, batchColorOk = 0, batchColorWrong = 0, batchPerfect = 0;

        for (int i = 0; i < batch.size(); i++) {
            EncodedParagraph enc = batch.get(i);
            String aiText = responses.get(i);
            totalParagraphs++;
            totalTokensSent += enc.tokenCount;
            batchTokensSent += enc.tokenCount;
            if (aiText == null) continue;

            // 解析 <sN>text</sN>，收集占位符 -> sN 映射
            Set<Integer> gotIds = new HashSet<>();
            Map<String, Integer> gotPlaceholderId = new HashMap<>();
            int preserved = 0;
            Matcher m = SN_TAG.matcher(aiText);
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                String text = m.group(2);
                gotIds.add(n);
                if (n < enc.tokenCount) preserved++;
                Matcher pm = Pattern.compile("\\{\\d+\\}").matcher(text);
                while (pm.find()) gotPlaceholderId.put(pm.group(), n);
            }
            totalTokensPreserved += preserved;
            batchPreserved += preserved;

            // 颜色检查：占位符 {N} 是否还在原 sN
            int colorOk = 0, colorWrong = 0;
            for (var entry : enc.placeholderToId.entrySet()) {
                String ph = entry.getKey();
                int expectedId = entry.getValue();
                Integer gotId = gotPlaceholderId.get(ph);
                if (gotId != null && gotId == expectedId) {
                    colorOk++; totalColorCorrect++; batchColorOk++;
                } else {
                    colorWrong++; totalColorWrong++; batchColorWrong++;
                }
            }

            if (colorWrong == 0 && preserved == enc.tokenCount) {
                perfectParagraphs++;
                batchPerfect++;
            }
        }

        System.out.printf("  batch %2d: %d paragraphs, %dms, tokens %d/%d (%.0f%%), color ok %d wrong %d, perfect %d%n",
                batchNum, batch.size(), ms, batchPreserved, batchTokensSent,
                pct(batchPreserved, batchTokensSent), batchColorOk, batchColorWrong, batchPerfect);
    }

    private void printSummary() {
        System.out.println("\n══════════════════════════════════════");
        System.out.println("  <sN> Lore AI Test Summary (position-ID)");
        System.out.println("══════════════════════════════════════");
        System.out.printf("  API batches:           %d%n", totalBatches);
        System.out.printf("  Paragraphs tested:     %d%n", totalParagraphs);
        System.out.printf("  Perfect paragraphs:    %d (%.2f%%)%n",
                perfectParagraphs, pct(perfectParagraphs, totalParagraphs));
        System.out.printf("  Tokens sent:           %d%n", totalTokensSent);
        System.out.printf("  Tokens preserved:      %d (%.2f%%)%n",
                totalTokensPreserved, pct(totalTokensPreserved, totalTokensSent));
        System.out.printf("  Color correct:         %d%n", totalColorCorrect);
        System.out.printf("  Color wrong:           %d%n", totalColorWrong);
        System.out.printf("  Color accuracy:        %.2f%%%n",
                pct(totalColorCorrect, totalColorCorrect + totalColorWrong));
        System.out.println();
        System.out.println("  --- Token 消耗 & Prompt 长度 ---");
        System.out.printf("  System prompt chars:   %d%n", SYSTEM_PROMPT.length());
        System.out.printf("  Total payload chars:   %d (avg %d/batch)%n",
                totalPayloadChars, totalBatches > 0 ? totalPayloadChars / totalBatches : 0);
        System.out.printf("  Prompt tokens (API):   %d%n", totalPromptTokens);
        System.out.printf("  Completion tokens:     %d%n", totalCompletionTokens);
        System.out.printf("  Total tokens (API):    %d%n", totalPromptTokens + totalCompletionTokens);
        System.out.printf("  Avg tokens/batch:      %d%n",
                totalBatches > 0 ? (totalPromptTokens + totalCompletionTokens) / totalBatches : 0);
    }

    private static long extractUsage(String body, String field) {
        int k = body.indexOf("\"usage\"");
        if (k < 0) return 0;
        int f = body.indexOf("\"" + field + "\"", k);
        if (f < 0) return 0;
        int c = body.indexOf(':', f);
        int s = c + 1;
        while (s < body.length() && Character.isWhitespace(body.charAt(s))) s++;
        int e = s;
        while (e < body.length() && Character.isDigit(body.charAt(e))) e++;
        try { return Long.parseLong(body.substring(s, e)); }
        catch (Exception ex) { return 0; }
    }

    private String callAi(String system, String payload) throws Exception {
        String body = "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"system\",\"content\":\""
                + escape(system) + "\"},{\"role\":\"user\",\"content\":\"" + escape(payload)
                + "\"}],\"temperature\":0.3,\"thinking\":{\"type\":\"disabled\"}}";
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.deepseek.com/chat/completions"))
                        .timeout(Duration.ofSeconds(180))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    String respBody = resp.body();
                    totalPromptTokens += extractUsage(respBody, "prompt_tokens");
                    totalCompletionTokens += extractUsage(respBody, "completion_tokens");
                    return extractContent(respBody);
                }
                if (resp.statusCode() == 429 || resp.statusCode() >= 500) {
                    Thread.sleep(3000L * attempt);
                    continue;
                }
                return "{}";
            } catch (Exception e) {
                last = e;
                Thread.sleep(3000L * attempt);
            }
        }
        throw last != null ? last : new RuntimeException("AI call failed");
    }

    private static String extractContent(String json) {
        int k = json.indexOf("\"content\"");
        if (k == -1) return "";
        int c = json.indexOf(':', k);
        int q = json.indexOf('"', c + 1);
        if (q == -1) return "";
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

    private static Map<Integer, String> parseDict(String raw) {
        Map<Integer, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return map;
        Matcher m = Pattern.compile("\"(\\d+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(raw);
        while (m.find()) {
            String val = m.group(2)
                    .replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\\\", "\\");
            map.put(Integer.parseInt(m.group(1)), val);
        }
        return map;
    }

    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append("\"").toString();
    }

    private static String escape(String s) { return jsonStr(s).substring(1, jsonStr(s).length() - 1); }
    private static double pct(int n, int d) { return d > 0 ? 100.0 * n / d : 0; }
}
