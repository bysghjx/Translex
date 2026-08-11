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
 * Full TSP vs Hybrid 对照实测：随机抽 N 条真实 lore，两种策略各跑一遍。
 *
 * <p>统计对照（用户设计的表格）：
 * <ul>
 *   <li>API input token（prompt 字符数代理）</li>
 *   <li>token 数（registry size）</li>
 *   <li>颜色正确率（checksum Level 0 通过 = 颜色对）</li>
 *   <li>Recovery 次数（repaired + fallback）</li>
 *   <li>嵌套 token 次数（parse recovery 里的 nested）</li>
 *   <li>token 泄露次数（invalid + missing）</li>
 * </ul>
 *
 * Run: java tsp.tests.TspHybridAiTest <apiKey> [count]
 */
public final class TspHybridAiTest {

    private static final Pattern NUMBER = Pattern.compile(
            "[\\d.,+%kmb\\-s()]*\\d[\\d.,+%kmb\\-s()]*", Pattern.CASE_INSENSITIVE);

    private static final String SYSTEM_PROMPT =
            "Translate to Simplified Chinese. [[ID:HASH||TEXT]] tokens: ID:HASH is bound to its content. " +
            "[[ID:HASH||X]] -> [[ID:HASH||translated X]], keep ID:HASH, never move/merge/split tokens. " +
            "{0} {1} placeholders output literally. " +
            "Reorder whole tokens freely for natural Chinese. " +
            "Output ONLY the translated text.";

    // 每策略独立统计
    static class Stats {
        String name;
        int paragraphs = 0;
        int totalTokens = 0;        // TSP token（保护段数 idHashSet.size()，非 API 费用）
        long inputChars = 0;        // 发给 AI 的字符数
        int colorCorrect = 0;       // Level 0 通过的 token 数
        int colorTotal = 0;         // 总 token 数（decode 侧）
        int repaired = 0;           // Level 2
        int fallback = 0;           // Level 3 hard fail 段落数
        int nested = 0;             // 嵌套拍平次数
        int invalid = 0;            // HASH 不合法
        int missing = 0;            // 丢 token
        long promptTokens = 0;      // API 费用：prompt_tokens（含缓存命中）
        long completionTokens = 0;  // API 费用：completion_tokens
        long cachedTokens = 0;      // API 缓存命中 token

        Stats(String name) { this.name = name; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java tsp.tests.TspHybridAiTest <apiKey> [count]");
            System.exit(1);
        }
        String apiKey = args[0];
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 50;

        List<TspLoreLoader.ItemData> items = TspLoreLoader.load(Path.of("tsp/data/lore.jsonl"));
        Random rnd = new Random(42);
        List<TspLoreLoader.ItemData> withPara = new ArrayList<>();
        for (var it : items) {
            for (var line : it.lines()) {
                boolean hasColon = line.stream().map(s -> s.text()).reduce("", String::concat).contains(":");
                if (!hasColon && line.size() > 1) { withPara.add(it); break; }
            }
        }
        List<TspLoreLoader.ItemData> sample = new ArrayList<>();
        for (int i = 0; i < count && !withPara.isEmpty(); i++) {
            sample.add(withPara.get(rnd.nextInt(withPara.size())));
        }

        System.out.println("=== Full TSP vs Hybrid AI Test ===");
        System.out.println("Sample: " + sample.size() + " items\n");

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

        Stats full = new Stats("Full TSP");
        Stats hybrid = new Stats("Hybrid");

        for (int idx = 0; idx < sample.size(); idx++) {
            TspLoreLoader.ItemData item = sample.get(idx);
            List<List<tsp.StyledSegment>> lines = item.lines();
            List<List<tsp.StyledSegment>> paraLines = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                String txt = lines.get(i).stream().map(s -> s.text()).reduce("", String::concat);
                if (!txt.contains(":") && lines.get(i).size() > 1) {
                    paraLines.add(lines.get(i));
                    for (int j = i + 1; j < lines.size() && paraLines.size() < 3; j++) {
                        String t = lines.get(j).stream().map(s -> s.text()).reduce("", String::concat);
                        if (t.contains(":") || t.isBlank()) break;
                        paraLines.add(lines.get(j));
                    }
                    break;
                }
            }
            if (paraLines.isEmpty()) {
                paraLines.add(lines.get(Math.min(1, lines.size() - 1)));
            }

            List<tsp.StyledSegment> merged = new ArrayList<>();
            for (int i = 0; i < paraLines.size(); i++) {
                if (i > 0) merged.add(tsp.StyledSegment.plain("\n"));
                for (var seg : paraLines.get(i)) {
                    String text = seg.text();
                    if (NUMBER.matcher(text).matches()) {
                        merged.add(new tsp.StyledSegment("{" + (merged.size()) + "}", seg.style()));
                    } else {
                        merged.add(seg);
                    }
                }
            }

            if (idx % 5 == 0) System.out.println("Item " + idx + "/" + sample.size() + ": " + item.itemId());

            // Full TSP
            runOne(client, apiKey, merged, full, TspEncoder.Policy.FULL);
            // Hybrid
            runOne(client, apiKey, merged, hybrid, TspEncoder.Policy.HYBRID);
        }

        printSummary(full, hybrid);
    }

    private static void runOne(HttpClient client, String apiKey,
                                List<tsp.StyledSegment> merged, Stats stats,
                                TspEncoder.Policy policy) throws Exception {
        // Hybrid: 合并相邻同色段（跟 TspFormat.encode/decode 一致）
        if (policy == TspEncoder.Policy.HYBRID) {
            merged = HybridPolicy.mergeAdjacentSameColor(merged);
        }
        TspRegistry registry = new TspRegistry();
        TspEncoder encoder = (policy == TspEncoder.Policy.HYBRID)
                ? TspEncoder.withHybrid(registry, merged, true)
                : new TspEncoder(registry, true);
        String tsp = encoder.encode(merged);

        // 建 decode 校验集（按 policy 决定哪些段进 registry）
        tsp.Style defaultStyle = (policy == TspEncoder.Policy.HYBRID)
                ? HybridPolicy.detectHybridDefault(merged) : null;
        HybridPolicy hp = (policy == TspEncoder.Policy.HYBRID)
                ? new HybridPolicy(defaultStyle) : null;

        Set<String> idHashSet = new HashSet<>();
        Map<String, List<Integer>> hashToIds = new HashMap<>();
        TspRegistry decReg = new TspRegistry();
        for (var seg : merged) {
            boolean protect = (hp != null) ? hp.shouldProtect(seg) : !seg.isPlain();
            if (protect) {
                int id = decReg.register(seg.style());
                String hash = TspEncoder.sha4(seg.text());
                idHashSet.add(id + ":" + hash);
                hashToIds.computeIfAbsent(hash, k -> new ArrayList<>()).add(id);
            }
        }

        stats.totalTokens += idHashSet.size();  // 实际 token 数（保护段数，非颜色种类）
        stats.inputChars += tsp.length();
        stats.paragraphs++;

        String body = "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"system\",\"content\":\""
                + esc(SYSTEM_PROMPT) + "\"},{\"role\":\"user\",\"content\":\"" + esc(tsp)
                + "\"}],\"temperature\":0.3,\"thinking\":{\"type\":\"disabled\"}}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.deepseek.com/chat/completions"))
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        String respBody = resp.body();
        String aiText = extractContent(respBody);

        // API 费用 token（从 usage 解析）
        long[] usage = extractUsage(respBody);
        stats.promptTokens += usage[0];
        stats.completionTokens += usage[1];
        stats.cachedTokens += usage[2];

        TspParser parser = new TspParser(TspRecovery.Level.V1);
        TspParser.ParseResult parsed = parser.parse(aiText);
        TspDecoder decoder = new TspDecoder(decReg, idHashSet, hashToIds);
        decoder.decode(parsed);

        // 统计
        stats.repaired += decoder.getRepairedCount();
        stats.invalid += decoder.getInvalidCount();
        stats.missing += decoder.getMissingCount();
        if (decoder.getAmbiguousCount() + decoder.getInvalidCount() > 0) stats.fallback++;
        // 颜色正确率：checksum 校验通过的 token 数（Level 0 + Level 2 修复的）
        int correct = countCorrectTokens(parsed, idHashSet, hashToIds);
        stats.colorCorrect += correct;
        stats.colorTotal += idHashSet.size();
        // 嵌套次数（recovery 事件里含 "nested"）
        for (var e : parsed.parseErrors()) {
            if (e.message() != null && e.message().toLowerCase().contains("nested")) stats.nested++;
        }
    }

    /** 统计 checksum 校验通过的 token 数（Level 0 + Level 2 修复算正确）。 */
    private static int countCorrectTokens(TspParser.ParseResult parsed,
                                            Set<String> idHashSet,
                                            Map<String, List<Integer>> hashToIds) {
        int correct = 0;
        for (TspToken t : parsed.tokens()) {
            if (t.checksum() == null) continue;
            String pair = t.id() + ":" + t.checksum();
            if (idHashSet.contains(pair)) {
                correct++;  // Level 0
            } else if (hashToIds.containsKey(t.checksum()) && hashToIds.get(t.checksum()).size() == 1) {
                correct++;  // Level 2 修复
            }
        }
        return correct;
    }

    private static void printSummary(Stats full, Stats hybrid) {
        System.out.println("\n════════════════════════════════════════════════════");
        System.out.println("  Full TSP vs Hybrid AI Test Summary");
        System.out.println("════════════════════════════════════════════════════");
        System.out.printf("%-25s %15s %15s%n", "Metric", "Full TSP", "Hybrid");
        System.out.println("─────────────────────────────────────────────────────");
        System.out.printf("%-25s %15d %15d%n", "Paragraphs", full.paragraphs, hybrid.paragraphs);
        System.out.printf("%-25s %15d %15d%n", "TSP tokens (protected)", full.totalTokens, hybrid.totalTokens);
        System.out.printf("%-25s %15d %15d%n", "Input chars (to AI)", full.inputChars, hybrid.inputChars);
        System.out.println("─────────────────────────────────────────────────────");
        System.out.printf("%-25s %15d %15d%n", "API prompt tokens", full.promptTokens, hybrid.promptTokens);
        System.out.printf("%-25s %15d %15d%n", "API completion tokens", full.completionTokens, hybrid.completionTokens);
        System.out.printf("%-25s %15d %15d%n", "API cached tokens", full.cachedTokens, hybrid.cachedTokens);
        long fullApiTotal = full.promptTokens + full.completionTokens;
        long hybridApiTotal = hybrid.promptTokens + hybrid.completionTokens;
        System.out.printf("%-25s %15d %15d%n", "API total (prompt+comp)", fullApiTotal, hybridApiTotal);
        System.out.println("─────────────────────────────────────────────────────");
        System.out.printf("%-25s %14.1f%% %14.1f%%.%n", "Color accuracy",
                pct(full.colorCorrect, full.colorTotal), pct(hybrid.colorCorrect, hybrid.colorTotal));
        System.out.printf("%-25s %15d %15d%n", "Level 2 repaired", full.repaired, hybrid.repaired);
        System.out.printf("%-25s %15d %15d%n", "Level 3 fallback (para)", full.fallback, hybrid.fallback);
        System.out.printf("%-25s %15d %15d%n", "Nested tokens", full.nested, hybrid.nested);
        System.out.printf("%-25s %15d %15d%n", "Invalid HASH", full.invalid, hybrid.invalid);
        System.out.printf("%-25s %15d %15d%n", "Missing tokens", full.missing, hybrid.missing);
        System.out.println("════════════════════════════════════════════════════");

        double tspTokenDelta = pct(full.totalTokens - hybrid.totalTokens, full.totalTokens);
        double apiTokenDelta = fullApiTotal > 0
                ? 100.0 * (fullApiTotal - hybridApiTotal) / fullApiTotal : 0;
        double charDelta = pctLong(full.inputChars - hybrid.inputChars, full.inputChars);
        System.out.printf("  Hybrid vs Full: TSP tokens %+.1f%%, API tokens %+.1f%%, input chars %+.1f%%%n",
                -tspTokenDelta, -apiTokenDelta, -charDelta);
        System.out.println("  (负值 = Hybrid 更少 = 更省)");
    }

    private static double pct(int n, int d) { return d > 0 ? 100.0 * n / d : 0; }
    private static double pctLong(long n, long d) { return d > 0 ? 100.0 * n / d : 0; }

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

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** 从 API 返回解析 usage: [prompt_tokens, completion_tokens, cached_tokens]。 */
    private static long[] extractUsage(String json) {
        long[] r = {0, 0, 0};
        int u = json.indexOf("\"usage\"");
        if (u == -1) return r;
        r[0] = extractLong(json, "\"prompt_tokens\"", u);
        r[1] = extractLong(json, "\"completion_tokens\"", u);
        r[2] = extractLong(json, "\"prompt_cache_hit_tokens\"", u)
                + extractLong(json, "\"cached_tokens\"", u);
        return r;
    }

    private static long extractLong(String json, String key, int from) {
        int k = json.indexOf(key, from);
        if (k == -1) return 0;
        int c = json.indexOf(':', k + key.length());
        if (c == -1) return 0;
        long v = 0;
        boolean neg = false;
        for (int i = c + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '-') { neg = true; continue; }
            if (ch < '0' || ch > '9') break;
            v = v * 10 + (ch - '0');
        }
        return neg ? -v : v;
    }
}
