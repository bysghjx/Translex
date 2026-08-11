package tsp.tests;

import tsp.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 针对之前出错的 lore 复测：Full TSP vs Hybrid。
 *
 * <p>重点 case：
 * <ul>
 *   <li>GREEN_BANDANA - 嵌套 token [[1:9120||[[2:cdda||更换]]它！]]</li>
 *   <li>GEMSTONE_GAUNTLET - 丢 token（Gemstones 段）</li>
 *   <li>GEMSTONE_DRILL_4 - §9 内联泄露</li>
 *   <li>MAMMOTH - auto-default 把 highlight 误判为默认色</li>
 *   <li>HYPERION - 普通对照</li>
 * </ul>
 *
 * Run: java tsp.tests.TspProblemLoreTest <apiKey>
 */
public final class TspProblemLoreTest {

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

    private static final String[] TARGETS = {
            "GEMSTONE_GAUNTLET", "GEMSTONE_DRILL_4", "GEMSTONE_DRILL_2",
            "HYPERION", "STARRED_MIDAS_SWORD"
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java tsp.tests.TspProblemLoreTest <apiKey>");
            System.exit(1);
        }
        String apiKey = args[0];

        List<TspLoreLoader.ItemData> items = TspLoreLoader.load(Path.of("tsp/data/lore.jsonl"));

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

        System.out.println("=== Problem Lore复测: Full vs Hybrid ===\n");

        for (String target : TARGETS) {
            TspLoreLoader.ItemData item = null;
            for (var it : items) {
                if (it.itemId().equals(target)) { item = it; break; }
            }
            if (item == null) {
                System.out.println("── " + target + ": NOT FOUND ──\n");
                continue;
            }

            System.out.println("══ " + target + " ══");
            List<List<tsp.StyledSegment>> lines = item.lines();

            int problemFull = 0, problemHybrid = 0;
            int paraTested = 0;
            int totalTokensFull = 0, totalTokensHybrid = 0;

            // 找所有段落（无冒号多行）测一遍
            for (int li = 0; li < lines.size(); li++) {
                String txt = lines.get(li).stream().map(s -> s.text()).reduce("", String::concat);
                if (txt.contains(":") || lines.get(li).size() <= 1) continue;

                // 合并连续无冒号行成段落
                List<List<tsp.StyledSegment>> paraLines = new ArrayList<>();
                paraLines.add(lines.get(li));
                for (int j = li + 1; j < lines.size() && paraLines.size() < 3; j++) {
                    String t = lines.get(j).stream().map(s -> s.text()).reduce("", String::concat);
                    if (t.contains(":") || t.isBlank()) break;
                    paraLines.add(lines.get(j));
                }

                List<tsp.StyledSegment> merged = new ArrayList<>();
                for (int i = 0; i < paraLines.size(); i++) {
                    if (i > 0) merged.add(tsp.StyledSegment.plain("\n"));
                    for (var seg : paraLines.get(i)) {
                        String text = seg.text();
                        if (NUMBER.matcher(text).matches()) {
                            merged.add(new tsp.StyledSegment("{" + merged.size() + "}", seg.style()));
                        } else {
                            merged.add(seg);
                        }
                    }
                }

                paraTested++;
                System.out.println("  Line " + li + ": " + txt.substring(0, Math.min(60, txt.length())));

                // Full
                Result fr = runOne(client, apiKey, merged, TspEncoder.Policy.FULL);
                // Hybrid
                Result hr = runOne(client, apiKey, merged, TspEncoder.Policy.HYBRID);

                totalTokensFull += fr.total;   // 实际 token 数（idHashSet.size()）
                totalTokensHybrid += hr.total;

                boolean fProb = fr.missing > 0 || fr.invalid > 0 || fr.nested > 0;
                boolean hProb = hr.missing > 0 || hr.invalid > 0 || hr.nested > 0;
                if (fProb) problemFull++;
                if (hProb) problemHybrid++;

                System.out.printf("    FULL:   tokens=%d ok=%d/%d missing=%d invalid=%d nested=%d%s%n",
                        fr.tokens, fr.correct, fr.total, fr.missing, fr.invalid, fr.nested,
                        fProb ? " <<<" : "");
                System.out.printf("    HYBRID: tokens=%d ok=%d/%d missing=%d invalid=%d nested=%d%s%n",
                        hr.tokens, hr.correct, hr.total, hr.missing, hr.invalid, hr.nested,
                        hProb ? " <<<" : "");
                if (fProb || hProb) {
                    System.out.println("    [AI-Full]   " + fr.aiPreview);
                    System.out.println("    [AI-Hybrid] " + hr.aiPreview);
                }
            }
            System.out.printf("  >> %s: %d 段, Full 问题 %d / Hybrid 问题 %d, tokens %d vs %d%n%n",
                    target, paraTested, problemFull, problemHybrid, totalTokensFull, totalTokensHybrid);
        }
    }

    static class Result {
        int tokens, chars, correct, total, repaired, missing, invalid, nested;
        String aiPreview;
    }

    private static Result runOne(HttpClient client, String apiKey,
                                  List<tsp.StyledSegment> merged, TspEncoder.Policy policy) throws Exception {
        // Hybrid: 合并相邻同色段（跟 TspFormat.encode/decode 一致）
        if (policy == TspEncoder.Policy.HYBRID) {
            merged = HybridPolicy.mergeAdjacentSameColor(merged);
        }
        TspRegistry registry = new TspRegistry();
        TspEncoder encoder = (policy == TspEncoder.Policy.HYBRID)
                ? TspEncoder.withHybrid(registry, merged, true)
                : new TspEncoder(registry, true);
        String tsp = encoder.encode(merged);

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

        TspParser parser = new TspParser(TspRecovery.Level.V1);
        TspParser.ParseResult parsed = parser.parse(aiText);
        TspDecoder decoder = new TspDecoder(decReg, idHashSet, hashToIds);
        decoder.decode(parsed);

        Result r = new Result();
        r.tokens = registry.size();
        r.chars = tsp.length();
        r.total = idHashSet.size();
        r.repaired = decoder.getRepairedCount();
        r.missing = decoder.getMissingCount();
        r.invalid = decoder.getInvalidCount();
        r.correct = countCorrect(parsed, idHashSet, hashToIds);
        for (var e : parsed.parseErrors()) {
            if (e.message() != null && e.message().toLowerCase().contains("nested")) r.nested++;
        }
        r.aiPreview = aiText.replace("\n", "\\n").substring(0, Math.min(100, aiText.length()));
        return r;
    }

    private static int countCorrect(TspParser.ParseResult parsed,
                                     Set<String> idHashSet,
                                     Map<String, List<Integer>> hashToIds) {
        int correct = 0;
        for (TspToken t : parsed.tokens()) {
            if (t.checksum() == null) continue;
            if (idHashSet.contains(t.id() + ":" + t.checksum())) correct++;
            else if (hashToIds.containsKey(t.checksum()) && hashToIds.get(t.checksum()).size() == 1) correct++;
        }
        return correct;
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
}
