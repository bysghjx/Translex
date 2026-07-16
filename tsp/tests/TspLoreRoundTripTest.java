package tsp.tests;

import tsp.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Phase 2 第一步：lore.jsonl -> Full TSP encode -> V1 decode -> round-trip 验证（无 AI）。
 *
 * <p>验证：
 * <ul>
 *   <li>桥接正确：segment JSON -> StyledSegment -> TSP -> StyledSegment</li>
 *   <li>Full TSP 编解码 round-trip：text + color 一致</li>
 *   <li>数字 {0} 占位符保护 + fillNumbers 还原</li>
 * </ul>
 *
 * <p>用 harvest 采集的真实 Hypixel lore 数据（292 物品 / 5171 行）。
 * Run: java tsp.tests.TspLoreRoundTripTest
 */
public final class TspLoreRoundTripTest {

    /** 数字段正则（与 Translex LineTemplate.NUMBER 一致）。 */
    private static final Pattern NUMBER = Pattern.compile(
            "[\\d.,+%kmb\\-s()]*\\d[\\d.,+%kmb\\-s()]*", Pattern.CASE_INSENSITIVE);

    private static int totalLines = 0;
    private static int roundTripOk = 0;
    private static int textMismatch = 0;
    private static int colorMismatch = 0;
    private static int numbersProtected = 0;
    private static int numbersRestored = 0;

    public static void main(String[] args) throws Exception {
        Path file = Path.of("tsp/data/lore.jsonl");
        List<TspLoreLoader.ItemData> items = TspLoreLoader.load(file);

        System.out.println("=== TSP Lore Round-Trip Test (Full TSP, no auto-default) ===");
        System.out.println("Loaded " + items.size() + " items\n");

        for (TspLoreLoader.ItemData item : items) {
            for (List<StyledSegment> line : item.lines()) {
                testLine(line);
            }
        }

        System.out.println("══════════════════════════════════════");
        System.out.println("  Round-Trip Summary");
        System.out.println("══════════════════════════════════════");
        System.out.printf("  Total lines:           %d%n", totalLines);
        System.out.printf("  Round-trip OK:         %d (%.2f%%)%n",
                roundTripOk, pct(roundTripOk, totalLines));
        System.out.printf("  Text mismatch:         %d%n", textMismatch);
        System.out.printf("  Color mismatch:        %d%n", colorMismatch);
        System.out.printf("  Numbers protected:     %d%n", numbersProtected);
        System.out.printf("  Numbers restored:      %d%n", numbersRestored);
        System.out.println();

        if (textMismatch == 0 && colorMismatch == 0) {
            System.out.println("✅ ALL lines round-trip correctly (text + color).");
            System.out.println("   Bridge + Full TSP codec verified on real Hypixel data.");
        } else {
            System.out.println("⚠ Mismatches found - bridge or codec has bugs.");
        }
    }

    private static void testLine(List<StyledSegment> originalLine) {
        totalLines++;

        // 1. 数字保护：扫描 segment，纯数字段 -> {N}，记录 vals
        List<String> vals = new ArrayList<>();
        List<StyledSegment> protectedLine = new ArrayList<>();
        for (StyledSegment seg : originalLine) {
            String text = seg.text();
            if (NUMBER.matcher(text).matches()) {
                protectedLine.add(new StyledSegment("{" + vals.size() + "}", seg.style()));
                vals.add(text);
                numbersProtected++;
            } else {
                protectedLine.add(seg);
            }
        }

        // 2. Full TSP encode（无 auto-default：所有 styled 段都 token 化）
        TspRegistry registry = new TspRegistry();
        TspEncoder encoder = new TspEncoder(registry);
        String tsp = encoder.encode(protectedLine);

        // 3. V1 parse + decode
        TspParser parser = new TspParser(TspRecovery.Level.V1);
        TspParser.ParseResult parsed = parser.parse(tsp);
        TspDecoder decoder = new TspDecoder(registry);
        List<StyledSegment> decoded = decoder.decode(parsed);

        // 4. fillNumbers：{N} -> vals[N]
        List<StyledSegment> restored = new ArrayList<>();
        for (StyledSegment seg : decoded) {
            String text = seg.text();
            String filled = fillNumbers(text, vals);
            if (!filled.equals(text)) numbersRestored++;
            restored.add(new StyledSegment(filled, seg.style()));
        }

        // 5. 对照原文：text + color
        if (!segmentsEqual(originalLine, restored)) {
            // 找具体不一致
            if (!textOf(originalLine).equals(textOf(restored))) textMismatch++;
            if (!colorsOf(originalLine).equals(colorsOf(restored))) colorMismatch++;
            if (textMismatch + colorMismatch <= 10) {  // 限制输出
                System.out.println("  MISMATCH: " + textOf(originalLine).substring(0, Math.min(60, textOf(originalLine).length())));
                System.out.println("    orig:   " + originalLine);
                System.out.println("    recon:  " + restored);
            }
        } else {
            roundTripOk++;
        }
    }

    private static String fillNumbers(String text, List<String> vals) {
        String r = text;
        for (int i = 0; i < vals.size(); i++) r = r.replace("{" + i + "}", vals.get(i));
        return r;
    }

    private static boolean segmentsEqual(List<StyledSegment> a, List<StyledSegment> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            StyledSegment sa = a.get(i), sb = b.get(i);
            if (!sa.text().equals(sb.text())) return false;
            if (!styleColorEquals(sa.style(), sb.style())) return false;
        }
        return true;
    }

    /** Phase 1 只比 color（bold/italic 暂忽略）。 */
    private static boolean styleColorEquals(Style a, Style b) {
        String ca = a.isEmpty() ? null : a.colorHex();
        String cb = b.isEmpty() ? null : b.colorHex();
        return java.util.Objects.equals(ca, cb);
    }

    private static String textOf(List<StyledSegment> segs) {
        StringBuilder sb = new StringBuilder();
        for (StyledSegment s : segs) sb.append(s.text());
        return sb.toString();
    }

    private static List<String> colorsOf(List<StyledSegment> segs) {
        List<String> c = new ArrayList<>();
        for (StyledSegment s : segs) c.add(s.style().isEmpty() ? null : s.style().colorHex());
        return c;
    }

    private static double pct(int n, int total) {
        return total > 0 ? 100.0 * n / total : 0;
    }
}
