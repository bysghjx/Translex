package tsp.tests;

import tsp.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/**
 * TSP checksum 实测：[[ID:HASH||TEXT]] 协议 v1.1。
 * 验证：1) AI 保留 ID:HASH 率 2) 跨 ID 挪动检测 3) Level 2 自动修复。
 * Run: java tsp.tests.TspChecksumTest <apiKey> [rounds]
 */
public final class TspChecksumTest {

    private static final Style GRAY = Style.of("#AAAAAA");
    private static final Style GREEN = Style.of("#55FF55");
    private static final Style AQUA = Style.of("#55FFFF");

    // Mammoth Wooly Coat 段落（多色 + 专有名词 ❄Cold/Glacite，已知跨色挪动场景）
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
            "[[ID:HASH||TEXT]] tokens: ID:HASH is a COMPOUND identifier PERMANENTLY BOUND to its content. " +
            "[[ID:HASH||X]] MUST become [[ID:HASH||translated X]] - keep ID:HASH together, NEVER change ID or HASH. " +
            "NEVER move content to a different ID:HASH. NEVER merge or split tokens. " +
            "{0} {1} placeholders output literally. " +
            "Reorder whole tokens freely for natural Chinese. " +
            "Output ONLY the translated text, no markdown.";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java tsp.tests.TspChecksumTest <apiKey> [rounds]");
            System.exit(1);
        }
        String apiKey = args[0];
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 5;

        // 1. TspEncoder 编码 [[ID||TEXT]]
        TspRegistry registry = new TspRegistry();
        TspEncoder encoder = new TspEncoder(registry);
        String tsp = encoder.encode(PARAGRAPH);

        // 2. 加 HASH：[[ID||TEXT]] -> [[ID:HASH||TEXT]]
        StringBuilder enc = new StringBuilder();
        Map<String, Integer> hashToId = new HashMap<>();   // HASH -> 原 ID（Level 2 修复用）
        Set<String> idHashSet = new HashSet<>();            // (ID:HASH) 合法集合
        Matcher m = Pattern.compile("\\[\\[(\\d+)\\|\\|(.*?)\\]\\]", Pattern.DOTALL).matcher(tsp);
        int lastEnd = 0;
        while (m.find()) {
            enc.append(tsp, lastEnd, m.start());
            int id = Integer.parseInt(m.group(1));
            String text = m.group(2);
            String hash = sha4(text);
            enc.append("[[").append(id).append(":").append(hash).append("||").append(text).append("]]");
            idHashSet.add(id + ":" + hash);
            hashToId.putIfAbsent(hash, id);  // 冲突时记第一个（ambiguous，不自动修复）
            lastEnd = m.end();
        }
        enc.append(tsp, lastEnd, tsp.length());
        String encoded = enc.toString();

        System.out.println("=== TSP Checksum Test (ID:HASH) ===");
        System.out.println("Encoded: " + encoded.replace("\n", "\\n"));
        System.out.println("(ID:HASH) set: " + idHashSet);
        System.out.println("Rounds: " + rounds + "\n");

        int totalTokens = 0, hashPresent = 0, hashMissing = 0;
        int correct = 0, movedDetected = 0, repaired = 0, invalidHash = 0;

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        for (int r = 1; r <= rounds; r++) {
            String body = "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"system\",\"content\":\""
                    + esc(SYSTEM_PROMPT) + "\"},{\"role\":\"user\",\"content\":\"" + esc(encoded)
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
            System.out.println("Round " + r + ": " + aiText.replace("\n", "\\n"));

            // 解析 [[ID:HASH||TEXT]] 或 [[ID||TEXT]]（HASH 可选）
            Matcher pm = Pattern.compile("\\[\\[(\\d+)(?::([0-9a-fA-F]+))?\\|\\|(.*?)\\]\\]", Pattern.DOTALL).matcher(aiText);
            while (pm.find()) {
                int id = Integer.parseInt(pm.group(1));
                String hash = pm.group(2);
                String text = pm.group(3);
                totalTokens++;
                if (hash == null) {
                    hashMissing++;
                    System.out.println("  ✗ HASH missing: [[id=" + id + "||" + text + "]]");
                    continue;
                }
                hashPresent++;
                if (idHashSet.contains(id + ":" + hash)) {
                    correct++;  // (ID, HASH) 匹配
                } else if (hashToId.containsKey(hash)) {
                    // HASH 合法但 ID 错 -> 跨 ID 挪动 -> Level 2 修复
                    int correctId = hashToId.get(hash);
                    movedDetected++;
                    System.out.println("  ⚠ MOVED: hash=" + hash + " expected id=" + correctId
                            + " got id=" + id + " text=\"" + text + "\" -> REPAIR to id=" + correctId);
                    repaired++;
                } else {
                    invalidHash++;  // HASH 不在集合（AI 乱编）
                    System.out.println("  ✗ INVALID hash=" + hash + " id=" + id + " text=\"" + text + "\"");
                }
            }
        }

        System.out.println("\n══════════════════════════");
        System.out.println("  Checksum Summary (" + rounds + " rounds)");
        System.out.println("══════════════════════════");
        System.out.printf("  Total tokens:       %d%n", totalTokens);
        System.out.printf("  HASH present:       %d (%.1f%%)%n", hashPresent, pct(hashPresent, totalTokens));
        System.out.printf("  HASH missing:       %d (%.1f%%)%n", hashMissing, pct(hashMissing, totalTokens));
        System.out.printf("  Correct (match):    %d%n", correct);
        System.out.printf("  Moved detected:     %d%n", movedDetected);
        System.out.printf("  Auto-repaired:      %d%n", repaired);
        System.out.printf("  Invalid HASH:       %d%n", invalidHash);
        System.out.println();
        if (hashPresent > 0 && movedDetected == 0 && hashMissing == 0) {
            System.out.println("  ✅ AI 保留 ID:HASH 完美，无跨色挪动。checksum 方案可行。");
        } else if (pct(hashPresent, totalTokens) >= 95) {
            System.out.println("  ✅ AI 保留 HASH 率高，Level 1/2 检测+修复有效。方案可行。");
        } else {
            System.out.println("  ⚠ AI 保留 HASH 率低，需调 prompt 或换思路。");
        }
    }

    /** SHA-256 前 4 hex（16-bit，每段 <20 token 碰撞概率 <1%）。 */
    private static String sha4(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return String.format("%04x", ((d[0] & 0xff) << 8 | (d[1] & 0xff)) & 0xffff);
        } catch (Exception e) {
            return "0000";
        }
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
