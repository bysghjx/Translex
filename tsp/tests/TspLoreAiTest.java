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
 * Phase 3 第二步：Full TSP + 强 prompt + 真实 AI，大规模测颜色恢复率。
 *
 * <p>流程：
 * <ol>
 *   <li>读 lore.jsonl，采样 N 个物品（含 pet）</li>
 *   <li>每物品段落分组（简化版 ParagraphGrouper：连续无冒号描述行合并）</li>
 *   <li>每段落 Full TSP encode + 数字 {0} 保护</li>
 *   <li>批处理字典发 AI（强 prompt：ID 绑定内容 + 占位符原样保留）</li>
 *   <li>TSP decode + 统计：token 保留率 + 颜色恢复率（内容-锚点对照）</li>
 * </ol>
 *
 * <p>颜色恢复率判定（C 方法）：编码时记录每个 token ID 的"锚点"（数字/英文专有名词/符号），
 * 解码后检查同 ID token 是否含相同锚点。锚点匹配 = 颜色正确（内容没挪到错 ID）。</p>
 *
 * <p>Run: java tsp.tests.TspLoreAiTest &lt;apiKey&gt; [sampleSize] [batchSize]
 */
public final class TspLoreAiTest {

    private static final Pattern NUMBER = Pattern.compile(
            "[\\d.,+%kmb\\-s()]*\\d[\\d.,+%kmb\\-s()]*", Pattern.CASE_INSENSITIVE);
    // 锚点：数字、英文连续字母、特殊符号（❄✿♦等）- 用于内容-ID 对照
    private static final Pattern ANCHOR = Pattern.compile(
            "\\d[\\d.,+%kmb\\-s()]*|[A-Za-z]{3,}|[❄✿♦★✦♥♣♠]");

    private static final String SYSTEM_PROMPT =
            "You are a Minecraft item tooltip translator. Translate to Simplified Chinese. " +
            "[[NUMBER||TEXT]] tokens: NUMBER is a style ID PERMANENTLY BOUND to its content. " +
            "Rule: [[N||X]] MUST become [[N||translated X]] - NUMBER N stays attached to its ORIGINAL content. " +
            "NEVER move content to a different NUMBER. NEVER merge or split tokens. " +
            "{0} {1} etc. are number placeholders - output them LITERALLY, never fill or remove. " +
            "Example: [[0||56%]] -> [[0||56%]], [[1||Glacite]] -> [[1||冰川]] (NOT [[0||冰川]]). " +
            "Reorder whole tokens freely for natural Chinese. Output ONLY the JSON object, no markdown.";

    // ---- 统计 ----
    private static int totalTokensSent = 0;
    private static int totalTokensPreserved = 0;   // ID 集合匹配的 token 数
    private static int totalColorCorrect = 0;       // 锚点匹配（颜色正确）的 token 数
    private static int totalColorWrong = 0;         // 锚点不匹配（内容挪到错 ID）
    private static int totalParagraphs = 0;
    private static int perfectParagraphs = 0;       // 全 token 颜色对的段落
    private static int totalBatches = 0;
    // ---- token 消耗 + prompt 长度（API usage）----
    private static long totalPromptTokens = 0;      // API 返回的 prompt_tokens
    private static long totalCompletionTokens = 0;   // API 返回的 completion_tokens
    private static long totalPayloadChars = 0;       // 发送的 payload 总字符数

    private final String apiKey;
    private final HttpClient client;

    public TspLoreAiTest(String apiKey) {
        this.apiKey = apiKey;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java tsp.tests.TspLoreAiTest <apiKey> [sampleSize] [batchSize]");
            System.exit(1);
        }
        String apiKey = args[0];
        int sampleSize = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        int batchSize = args.length > 2 ? Integer.parseInt(args[2]) : 10;

        System.out.println("=== TSP Lore AI Test (Full TSP + strong prompt) ===");
        System.out.println("Sample: " + sampleSize + " items, batch: " + batchSize + "\n");

        List<TspLoreLoader.ItemData> items = TspLoreLoader.load(Path.of("tsp/data/lore.jsonl"));
        // 采样：优先 pet，再随机
        List<TspLoreLoader.ItemData> sample = sample(items, sampleSize);
        System.out.println("Sampled " + sample.size() + " items (pet-prioritized)\n");

        TspLoreAiTest test = new TspLoreAiTest(apiKey);
        test.run(sample, batchSize);
        test.printSummary();
    }

    private static List<TspLoreLoader.ItemData> sample(List<TspLoreLoader.ItemData> items, int n) {
        // pet 优先（itemId 含 PET），再补充其他
        List<TspLoreLoader.ItemData> pets = new ArrayList<>();
        List<TspLoreLoader.ItemData> others = new ArrayList<>();
        for (var it : items) {
            if (it.itemId().contains("PET")) pets.add(it);
            else others.add(it);
        }
        Collections.shuffle(others, new Random(42));  // 固定种子可复现
        List<TspLoreLoader.ItemData> sample = new ArrayList<>();
        sample.addAll(pets.subList(0, Math.min(pets.size(), n / 2)));
        sample.addAll(others.subList(0, Math.min(others.size(), n - sample.size())));
        return sample;
    }

    // ================================================================
    // 段落分组（简化版 ParagraphGrouper）
    // ================================================================

    private record Paragraph(List<List<StyledSegment>> lines, boolean isMerged) {}

    /** 简化分组：含冒号的行独立，连续无冒号描述行合并成段落。 */
    private static List<Paragraph> group(List<List<StyledSegment>> lines) {
        List<Paragraph> groups = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String text = lineText(lines.get(i));
            if (text.contains(":") || text.contains("：") || i == 0) {
                groups.add(new Paragraph(List.of(lines.get(i)), false));
                i++;
            } else {
                // 收集连续无冒号行
                List<List<StyledSegment>> para = new ArrayList<>();
                while (i < lines.size()) {
                    String t = lineText(lines.get(i));
                    if (t.contains(":") || t.contains("：")) break;
                    para.add(lines.get(i));
                    i++;
                    if (para.size() >= 4) break;  // 限制段落大小
                }
                groups.add(new Paragraph(para, para.size() >= 2));
            }
        }
        return groups;
    }

    private static String lineText(List<StyledSegment> segs) {
        StringBuilder sb = new StringBuilder();
        for (var s : segs) sb.append(s.text());
        return sb.toString();
    }

    // ================================================================
    // 编码：段落 -> TSP + 数字保护 + 锚点记录
    // ================================================================

    private record EncodedParagraph(
            String tsp,                      // TSP 编码字符串
            Map<String, Integer> placeholderToId,  // {N} -> 原 token ID（占位符绑定的颜色 ID）
            int tokenCount,
            List<String> vals                // 数字占位符原值
    ) {}

    private static EncodedParagraph encodeParagraph(Paragraph para) {
        // 合并段落多行（\n 分隔）
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

        TspRegistry registry = new TspRegistry();
        TspEncoder encoder = new TspEncoder(registry);  // Full TSP（无 auto-default）
        String tsp = encoder.encode(merged);

        // 记录每个占位符 {N} 绑定的 token ID（颜色 ID）
        // dedup 下同 ID 可能有多个 token，只记录占位符的 ID 绑定
        Map<String, Integer> placeholderToId = new LinkedHashMap<>();
        Matcher tm = Pattern.compile("\\[\\[(\\d+)\\|\\|(.*?)\\]\\]", Pattern.DOTALL).matcher(tsp);
        while (tm.find()) {
            int id = Integer.parseInt(tm.group(1));
            String content = tm.group(2);
            Matcher pm = Pattern.compile("\\{\\d+\\}").matcher(content);
            while (pm.find()) placeholderToId.put(pm.group(), id);
        }

        return new EncodedParagraph(tsp, placeholderToId, registry.size(), vals);
    }

    // ================================================================
    // 主流程
    // ================================================================

    private void run(List<TspLoreLoader.ItemData> sample, int batchSize) throws Exception {
        // 编码所有段落
        List<EncodedParagraph> all = new ArrayList<>();
        for (var item : sample) {
            for (var para : group(item.lines())) {
                all.add(encodeParagraph(para));
            }
        }
        System.out.println("Encoded " + all.size() + " paragraphs, "
                + all.stream().mapToInt(e -> e.tokenCount).sum() + " tokens total\n");

        // 批处理发 AI
        for (int i = 0; i < all.size(); i += batchSize) {
            int end = Math.min(i + batchSize, all.size());
            List<EncodedParagraph> batch = all.subList(i, end);
            processBatch(batch, ++totalBatches);
        }
    }

    private void processBatch(List<EncodedParagraph> batch, int batchNum) throws Exception {
        // 构造字典 payload
        StringBuilder dict = new StringBuilder("{");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) dict.append(",");
            dict.append("\"").append(i).append("\":").append(jsonStr(batch.get(i).tsp));
        }
        dict.append("}");

        long start = System.currentTimeMillis();
        String payload = dict.toString();
        totalPayloadChars += payload.length();
        String raw = callAi(SYSTEM_PROMPT, payload);
        long ms = System.currentTimeMillis() - start;

        Map<Integer, String> responses = parseDict(raw);

        // 调试：前 2 批或解析失败时打印 raw
        if (batchNum <= 2 || responses.size() < batch.size() / 2) {
            System.out.println("  [debug batch " + batchNum + "] payload.len=" + payload.length()
                    + " responses=" + responses.size() + "/" + batch.size());
            System.out.println("  [debug raw] " + raw.substring(0, Math.min(400, raw.length())));
        }

        int batchTokensSent = 0, batchPreserved = 0, batchColorOk = 0, batchColorWrong = 0;
        int batchPerfect = 0;

        for (int i = 0; i < batch.size(); i++) {
            EncodedParagraph enc = batch.get(i);
            String aiText = responses.get(i);
            totalParagraphs++;
            totalTokensSent += enc.tokenCount;
            batchTokensSent += enc.tokenCount;

            if (aiText == null) {
                System.out.println("  batch " + batchNum + " [" + i + "] MISSING");
                continue;
            }

            // 解码
            TspParser parser = new TspParser(TspRecovery.Level.V1);
            TspParser.ParseResult parsed = parser.parse(aiText);

            // 统计 token 保留（ID 集合）
            Set<Integer> sentIds = new HashSet<>();
            for (int id = 0; id < enc.tokenCount; id++) sentIds.add(id);
            Set<Integer> gotIds = new HashSet<>();
            for (var t : parsed.tokens()) gotIds.add(t.id());
            int preserved = 0;
            for (int id : sentIds) if (gotIds.contains(id)) preserved++;
            totalTokensPreserved += preserved;
            batchPreserved += preserved;

            // 颜色恢复率：占位符-ID 绑定检查
            // 找 AI 返回里每个占位符 {N} 在哪个 token ID
            Map<String, Integer> gotPlaceholderId = new HashMap<>();
            for (var t : parsed.tokens()) {
                Matcher pm = Pattern.compile("\\{\\d+\\}").matcher(t.text());
                while (pm.find()) gotPlaceholderId.put(pm.group(), t.id());
            }
            // 检查每个占位符是否还在原 ID（颜色没挪）
            int colorOk = 0, colorWrong = 0;
            for (var entry : enc.placeholderToId.entrySet()) {
                String ph = entry.getKey();
                int expectedId = entry.getValue();
                Integer gotId = gotPlaceholderId.get(ph);
                if (gotId != null && gotId == expectedId) {
                    colorOk++; totalColorCorrect++; batchColorOk++;
                } else {
                    colorWrong++; totalColorWrong++; batchColorWrong++;
                    if (totalColorWrong <= 12) {
                        System.out.println("  [WRONG #" + totalColorWrong + "] " + ph
                                + " expected id=" + expectedId + " got id=" + gotId);
                        System.out.println("    ENCODED: " + enc.tsp.replace("\n", "\\n")
                                .substring(0, Math.min(200, enc.tsp.length())));
                    }
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
        System.out.println("  TSP Lore AI Test Summary");
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

    /** 从 API 响应提取 usage 字段。 */
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

    // ================================================================
    // HTTP + JSON
    // ================================================================

    private String callAi(String system, String payload) throws Exception {
        String body = "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"system\",\"content\":\""
                + escape(system) + "\"},{\"role\":\"user\",\"content\":\"" + escape(payload)
                + "\"}],\"temperature\":0.3,\"thinking\":{\"type\":\"disabled\"}}";
        // 重试 3 次，应对 API 偶发超时/限流
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
                // 429/5xx 重试
                if (resp.statusCode() == 429 || resp.statusCode() >= 500) {
                    System.out.println("  [retry " + attempt + "] HTTP " + resp.statusCode());
                    Thread.sleep(3000L * attempt);
                    continue;
                }
                return "{}";
            } catch (Exception e) {
                last = e;
                System.out.println("  [retry " + attempt + "] " + e.getClass().getSimpleName());
                Thread.sleep(3000L * attempt);
            }
        }
        throw last != null ? last : new RuntimeException("AI call failed after 3 retries");
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
        // 简单提取 "N": "value"（允许冒号前后空格，AI 返回格式化 JSON）
        java.util.regex.Matcher m = Pattern.compile("\"(\\d+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(raw);
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
