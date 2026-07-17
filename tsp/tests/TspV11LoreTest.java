package tsp.tests;

import tsp.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * TSP v1.1 实测：随机抽 10 条真实 lore，跑 checksum + Level 1/2/3 Recovery。
 * 统计：normal / repaired / ambiguous / invalid / fallback + token 保留 + 颜色准确。
 * Run: java tsp.tests.TspV11LoreTest <apiKey> [count]
 */
public final class TspV11LoreTest {

    private static final Pattern NUMBER = Pattern.compile(
            "[\\d.,+%kmb\\-s()]*\\d[\\d.,+%kmb\\-s()]*", Pattern.CASE_INSENSITIVE);

    private static final String SYSTEM_PROMPT =
            "You are a Minecraft item tooltip translator. Translate to Simplified Chinese. " +
            "[[ID:HASH||TEXT]] tokens: ID:HASH is a COMPOUND identifier PERMANENTLY BOUND to its content. " +
            "[[ID:HASH||X]] MUST become [[ID:HASH||translated X]] - keep ID:HASH together, NEVER change ID or HASH. " +
            "NEVER move content to a different ID:HASH. NEVER merge or split tokens. " +
            "{0} {1} placeholders output literally. " +
            "Reorder whole tokens freely for natural Chinese. " +
            "Output ONLY the translated text, no markdown.";

    // 统计
    private static int totalParagraphs = 0;
    private static int totalTokens = 0;
    private static int normalCount = 0;       // Level 0 正确
    private static int repairedCount = 0;     // Level 2 修复
    private static int ambiguousCount = 0;    // Level 3 ambiguous
    private static int invalidCount = 0;      // Level 3 invalid
    private static int fallbackCount = 0;     // 段落回退
    private static int placeholderCorrect = 0; // 占位符 {0} 保留 + 在原 ID
    private static int placeholderTotal = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java tsp.tests.TspV11LoreTest <apiKey> [count]");
            System.exit(1);
        }
        String apiKey = args[0];
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 10;

        List<TspLoreLoader.ItemData> items = TspLoreLoader.load(Path.of("tsp/data/lore.jsonl"));
        Random rnd = new Random(42);  // 固定种子可复现
        List<TspLoreLoader.ItemData> sample = new ArrayList<>();
        // 优先含段落的物品（多 token，易触发挪动）
        List<TspLoreLoader.ItemData> withPara = new ArrayList<>();
        for (var it : items) {
            for (var line : it.lines()) {
                boolean hasColon = line.stream().map(s -> s.text()).reduce("", String::concat).contains(":");
                if (!hasColon && line.size() > 1) { withPara.add(it); break; }
            }
        }
        for (int i = 0; i < count && !withPara.isEmpty(); i++) {
            sample.add(withPara.get(rnd.nextInt(withPara.size())));
        }

        System.out.println("=== TSP v1.1 Lore Test (checksum + Level 1/2/3) ===");
        System.out.println("Sample: " + sample.size() + " items\n");

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

        for (int idx = 0; idx < sample.size(); idx++) {
            TspLoreLoader.ItemData item = sample.get(idx);
            System.out.println("── Item " + idx + ": " + item.itemId() + " ──");

            // 取第一个段落（多行合并）
            List<List<tsp.StyledSegment>> lines = item.lines();
            // 找第一个无冒号的多行段落
            List<List<tsp.StyledSegment>> paraLines = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                String txt = lines.get(i).stream().map(s -> s.text()).reduce("", String::concat);
                if (!txt.contains(":") && lines.get(i).size() > 1) {
                    paraLines.add(lines.get(i));
                    // 收集后续无冒号行
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

            // 合并段落 + 数字保护
            List<tsp.StyledSegment> merged = new ArrayList<>();
            List<String> vals = new ArrayList<>();
            for (int i = 0; i < paraLines.size(); i++) {
                if (i > 0) merged.add(tsp.StyledSegment.plain("\n"));
                for (var seg : paraLines.get(i)) {
                    String text = seg.text();
                    if (NUMBER.matcher(text).matches()) {
                        merged.add(new tsp.StyledSegment("{" + vals.size() + "}", seg.style()));
                        vals.add(text);
                    } else {
                        merged.add(seg);
                    }
                }
            }

            // encode v1.1（withChecksum）
            TspRegistry registry = new TspRegistry();
            TspEncoder encoder = new TspEncoder(registry, true);
            String tsp = encoder.encode(merged);

            // 建 idHashSet + hashToIds（decode 校验用）
            Set<String> idHashSet = new HashSet<>();
            Map<String, List<Integer>> hashToIds = new HashMap<>();
            TspRegistry decReg = new TspRegistry();
            for (var seg : merged) {
                if (!seg.isPlain()) {
                    int id = decReg.register(seg.style());
                    String hash = TspEncoder.sha4(seg.text());
                    idHashSet.add(id + ":" + hash);
                    hashToIds.computeIfAbsent(hash, k -> new ArrayList<>()).add(id);
                }
            }

            int tokenCount = registry.size();
            totalTokens += tokenCount;
            totalParagraphs++;

            // 发 AI
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
            String aiText = extractContent(resp.body());

            // decode + 校验
            TspParser parser = new TspParser(TspRecovery.Level.V1);
            TspDecoder decoder = new TspDecoder(decReg, idHashSet, hashToIds);
            List<tsp.StyledSegment> decoded = decoder.decode(parser.parse(aiText));

            int repaired = decoder.getRepairedCount();
            int ambiguous = decoder.getAmbiguousCount();
            int invalid = decoder.getInvalidCount();
            repairedCount += repaired;
            ambiguousCount += ambiguous;
            invalidCount += invalid;

            boolean fallback = decoder.getLevel3Count() > 0;
            if (fallback) fallbackCount++;
            else if (repaired > 0) repairedCount++;  // 已加
            else normalCount++;

            // 占位符校验（{0} 在原 ID）
            Map<String, Integer> gotPhId = new HashMap<>();
            for (var t : parser.parse(aiText).tokens()) {
                java.util.regex.Matcher pm = Pattern.compile("\\{\\d+\\}").matcher(t.text());
                while (pm.find()) gotPhId.put(pm.group(), t.id());
            }
            // 原 idHashSet 里含 {N} 的 (ID, HASH)
            for (var seg : merged) {
                if (!seg.isPlain() && seg.text().matches("\\{\\d+\\}")) {
                    placeholderTotal++;
                    Integer gotId = gotPhId.get(seg.text());
                    int expectedId = decReg.register(seg.style());
                    if (gotId != null && gotId == expectedId) placeholderCorrect++;
                }
            }

            System.out.printf("  tokens=%d repaired=%d ambiguous=%d invalid=%d fallback=%b%n",
                    tokenCount, repaired, ambiguous, invalid, fallback);
            System.out.println("  AI: " + aiText.replace("\n", "\\n").substring(0, Math.min(120, aiText.length())));
        }

        System.out.println("\n══════════════════════════════════════");
        System.out.println("  TSP v1.1 Lore Test Summary");
        System.out.println("══════════════════════════════════════");
        System.out.printf("  Paragraphs:       %d%n", totalParagraphs);
        System.out.printf("  Total tokens:     %d%n", totalTokens);
        System.out.printf("  Level 0 normal:   %d (%.1f%%)%n", normalCount, pct(normalCount, totalParagraphs));
        System.out.printf("  Level 2 repaired: %d%n", repairedCount);
        System.out.printf("  Level 3 ambiguous:%d%n", ambiguousCount);
        System.out.printf("  Level 3 invalid:  %d%n", invalidCount);
        System.out.printf("  Paragraph fallback:%d (%.1f%%)%n", fallbackCount, pct(fallbackCount, totalParagraphs));
        System.out.printf("  Placeholder:      %d/%d correct (%.1f%%)%n",
                placeholderCorrect, placeholderTotal, pct(placeholderCorrect, placeholderTotal));
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

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static double pct(int n, int d) { return d > 0 ? 100.0 * n / d : 0; }
}
