package tsp.tests;

import tsp.*;
import java.util.List;

/**
 * Hybrid 策略测试：选择性 token 化。
 *
 * <p>验证：
 * <ul>
 *   <li>默认色判定（plain 计票 + 字符加权）</li>
 *   <li>5 条硬保护规则（非默认色/数字/符号/placeholder/小段非默认色）</li>
 *   <li>默认色描述裸文本 + 连续合并</li>
 *   <li>round-trip（Hybrid encode -> decode 颜色正确）</li>
 * </ul>
 *
 * Run: java tsp.tests.TspHybridTest
 */
public final class TspHybridTest {

    private static int passed = 0;
    private static int failed = 0;

    private static final Style GRAY  = Style.of("#AAAAAA");
    private static final Style GREEN = Style.of("#55FF55");
    private static final Style AQUA  = Style.of("#55FFFF");
    private static final Style GOLD  = Style.of("#FFAA00");

    public static void main(String[] args) {
        System.out.println("=== TSP Hybrid Policy Tests ===\n");

        testDefaultDetection_PlainDominant();
        testDefaultDetection_ColorDominant();
        testDefaultDetection_MidasEnchantNoGray();
        testProtect_NonDefaultColor();
        testProtect_Number();
        testProtect_SpecialSymbol();
        testProtect_Placeholder();
        testNoProtect_DefaultColorDescription();
        testConsecutivePlainMerge();
        testRoundTrip();
        testTokenReduction();
        testFullVsHybridComparison();
        testMergeAdjacentSameColor();

        System.out.println("\n---");
        System.out.println("Passed: " + passed + "  Failed: " + failed);
        if (failed > 0) { System.out.println("SOME TESTS FAILED!"); System.exit(1); }
        System.out.println("All Hybrid tests passed.");
    }

    // ================================================================
    // 默认色判定
    // ================================================================

    /** Mammoth 描述行：大量 plain + 少量 highlight。
     *  旧 detectDefaultStyle 会把 AQUA 误判为默认色，Hybrid 应判定 EMPTY（plain 主导）。 */
    private static void testDefaultDetection_PlainDominant() {
        System.out.print("  Default: plain-dominant -> EMPTY ... ");
        List<StyledSegment> segs = List.of(
                StyledSegment.plain("Gain a "),
                StyledSegment.styled("56%", GREEN),
                StyledSegment.plain(" chance for mobs to not inflict "),
                StyledSegment.styled("Cold", AQUA),
                StyledSegment.plain(" when damaging you in the "),
                StyledSegment.styled("Glacite Mineshafts", AQUA),
                StyledSegment.plain(".")
        );
        Style def = HybridPolicy.detectHybridDefault(segs);
        assertEq(Style.EMPTY, def, "plain-dominant -> EMPTY");
        pass();
    }

    /** Stat 行：大量 GRAY + 数值 highlight。GRAY 字符数多 -> 默认色 GRAY。 */
    private static void testDefaultDetection_ColorDominant() {
        System.out.print("  Default: color-dominant -> GRAY ... ");
        List<StyledSegment> segs = List.of(
                StyledSegment.styled("Grants bonus damage of ", GRAY),
                StyledSegment.styled("+150", GREEN),
                StyledSegment.styled(" to all attacks.", GRAY)
        );
        Style def = HybridPolicy.detectHybridDefault(segs);
        assertEq(GRAY, def, "GRAY-dominant -> GRAY");
        pass();
    }

    /** Midas 附魔段：金色 55% 但无灰色 -> 不设默认色（全保护）。
     *  回归测试：旧按频率判定会误判金色为默认色，导致 Champion 等金色附魔名丢色。 */
    private static void testDefaultDetection_MidasEnchantNoGray() {
        System.out.print("  Default: Midas enchant (gold 55%, no gray) -> EMPTY ... ");
        Style GOLD = Style.of("#FFAA00");
        Style PURPLE = Style.of("#AA00AA");
        Style PINK = Style.of("#FF55FF");
        List<StyledSegment> segs = List.of(
                StyledSegment.styled("Chimera 5, ", PINK),
                StyledSegment.styled("Champion 10, ", GOLD),
                StyledSegment.styled("Bane of Arthropods 7", GOLD),
                StyledSegment.styled("Critical 7, ", GOLD),
                StyledSegment.styled("Cubism 6, ", GOLD),
                StyledSegment.styled("Drain 4", PURPLE)
        );
        Style def = HybridPolicy.detectHybridDefault(segs);
        assertEq(Style.EMPTY, def, "no gray -> EMPTY");
        HybridPolicy hp = new HybridPolicy(def);
        assertTrue(hp.shouldProtect(StyledSegment.styled("Champion 10, ", GOLD)),
                "Champion (gold) protected");
        pass();
    }

    // ================================================================
    // 保护规则
    // ================================================================

    /** 规则 1+5：非默认色段一律保护。 */
    private static void testProtect_NonDefaultColor() {
        System.out.print("  Protect: non-default color ... ");
        HybridPolicy hp = new HybridPolicy(GRAY);
        assertTrue(hp.shouldProtect(StyledSegment.styled("Rare", AQUA)), "AQUA on GRAY-default -> protect");
        assertTrue(hp.shouldProtect(StyledSegment.styled("Hyperion", GOLD)), "GOLD on GRAY-default -> protect");
        assertTrue(!hp.shouldProtect(StyledSegment.styled("body text", GRAY)), "GRAY on GRAY-default -> no protect");
        pass();
    }

    /** 规则 2：数字段保护（即使默认色）。 */
    private static void testProtect_Number() {
        System.out.print("  Protect: number ... ");
        HybridPolicy hp = new HybridPolicy(GRAY);
        assertTrue(hp.shouldProtect(new StyledSegment("100", GRAY)), "100 (default color) -> protect");
        assertTrue(hp.shouldProtect(new StyledSegment("+50", GRAY)), "+50 -> protect");
        assertTrue(hp.shouldProtect(new StyledSegment("5%", GRAY)), "5% -> protect");
        assertTrue(hp.shouldProtect(new StyledSegment("0.25", GRAY)), "0.25 -> protect");
        assertTrue(hp.shouldProtect(new StyledSegment("1,000", GRAY)), "1,000 -> protect");
        pass();
    }

    /** 规则 3：MC 特殊符号保护。 */
    private static void testProtect_SpecialSymbol() {
        System.out.print("  Protect: special symbol ... ");
        HybridPolicy hp = new HybridPolicy(GRAY);
        assertTrue(hp.shouldProtect(new StyledSegment("❄", GRAY)), "❄ -> protect");
        assertTrue(hp.shouldProtect(new StyledSegment("♦", GRAY)), "♦ -> protect");
        assertTrue(hp.shouldProtect(new StyledSegment("★", GRAY)), "★ -> protect");
        assertTrue(!hp.shouldProtect(new StyledSegment("Hello", GRAY)), "Hello -> no protect");
        pass();
    }

    /** 规则 4：placeholder 保护。 */
    private static void testProtect_Placeholder() {
        System.out.print("  Protect: placeholder ... ");
        HybridPolicy hp = new HybridPolicy(GRAY);
        assertTrue(hp.shouldProtect(new StyledSegment("{0}", GRAY)), "{0} -> protect");
        assertTrue(hp.shouldProtect(new StyledSegment("{1}", GRAY)), "{1} -> protect");
        assertTrue(hp.shouldProtect(StyledSegment.plain("{0}")), "plain {0} -> protect");
        pass();
    }

    /** 默认色普通描述不保护（裸文本）。 */
    private static void testNoProtect_DefaultColorDescription() {
        System.out.print("  No protect: default color description ... ");
        HybridPolicy hp = new HybridPolicy(GRAY);
        assertTrue(!hp.shouldProtect(new StyledSegment("Right click to activate", GRAY)), "description -> no protect");
        assertTrue(!hp.shouldProtect(new StyledSegment("Cooldown:", GRAY)), "Cooldown: -> no protect");
        assertTrue(!hp.shouldProtect(StyledSegment.plain("Deals damage to enemies.")), "plain description -> no protect");
        pass();
    }

    // ================================================================
    // 连续合并
    // ================================================================

    /** 连续默认色段应合并成裸文本，不分多个 token。 */
    private static void testConsecutivePlainMerge() {
        System.out.print("  Consecutive plain merge ... ");
        List<StyledSegment> segs = List.of(
                StyledSegment.styled("Deal ", GRAY),
                StyledSegment.styled("damage ", GRAY),
                StyledSegment.styled("100", GREEN),
                StyledSegment.styled(" to enemies", GRAY)
        );
        TspRegistry reg = new TspRegistry();
        TspEncoder enc = TspEncoder.withHybrid(reg, segs, true);
        String out = enc.encode(segs);
        // "Deal damage " 应作为连续裸文本，不分 token
        assertTrue(out.startsWith("Deal damage "), "consecutive GRAY merged: " + out);
        // 只 1 个 token（GREEN 的 100）
        assertEq(1, reg.size(), "only GREEN registered");
        pass();
    }

    // ================================================================
    // Round-trip
    // ================================================================

    /** Hybrid encode -> decode，颜色应正确还原。 */
    private static void testRoundTrip() {
        System.out.print("  Round-trip ... ");
        List<StyledSegment> input = List.of(
                StyledSegment.plain("Gain a "),
                StyledSegment.styled("56%", GREEN),
                StyledSegment.plain(" chance to receive "),
                StyledSegment.styled("❄ Cold", AQUA),
                StyledSegment.plain(".")
        );

        TspRegistry encReg = new TspRegistry();
        TspEncoder enc = TspEncoder.withHybrid(encReg, input, true);
        String tsp = enc.encode(input);

        // 用相同策略重建 decode registry
        TspRegistry decReg = new TspRegistry();
        HybridPolicy hp = new HybridPolicy(HybridPolicy.detectHybridDefault(input));
        java.util.Set<String> idHashSet = new java.util.HashSet<>();
        java.util.Map<String, List<Integer>> hashToIds = new java.util.HashMap<>();
        for (StyledSegment seg : input) {
            if (hp.shouldProtect(seg)) {
                int id = decReg.register(seg.style());
                String hash = TspEncoder.sha4(seg.text());
                idHashSet.add(id + ":" + hash);
                hashToIds.computeIfAbsent(hash, k -> new java.util.ArrayList<>()).add(id);
            }
        }

        TspParser parser = new TspParser(TspRecovery.Level.V1);
        TspDecoder decoder = new TspDecoder(decReg, idHashSet, hashToIds);
        List<StyledSegment> output = decoder.decode(parser.parse(tsp));

        // 验证关键段颜色
        assertEq(GREEN, findSegByText(output, "56%").style(), "56% -> GREEN");
        assertEq(AQUA, findSegByText(output, "❄ Cold").style(), "❄ Cold -> AQUA");
        assertTrue(decoder.getMissingCount() == 0, "no missing");
        assertTrue(decoder.getAmbiguousCount() + decoder.getInvalidCount() == 0, "no hard fail");
        pass();
    }

    // ================================================================
    // Token 缩减
    // ================================================================

    /** Hybrid 比 Full TSP token 数少（默认色裸文本不进 registry）。 */
    private static void testTokenReduction() {
        System.out.print("  Token reduction vs Full ... ");
        // GRAY 主导（长描述 + 多段），GREEN 仅短数值
        List<StyledSegment> segs = List.of(
                StyledSegment.styled("Grants a bonus to all damage dealt", GRAY),
                StyledSegment.styled("+150", GREEN),
                StyledSegment.styled(" when hitting enemies in combat.", GRAY)
        );

        TspRegistry fullReg = new TspRegistry();
        new TspEncoder(fullReg, true).encode(segs);
        int fullTokens = fullReg.size();

        TspRegistry hybridReg = new TspRegistry();
        TspEncoder.withHybrid(hybridReg, segs, true).encode(segs);
        int hybridTokens = hybridReg.size();

        assertTrue(hybridTokens < fullTokens, "Hybrid < Full: " + hybridTokens + " vs " + fullTokens);
        // Full: GRAY + GREEN = 2; Hybrid: GREEN only = 1（GRAY 字符多 -> 默认色裸文本）
        assertEq(1, hybridTokens, "Hybrid registry size");
        pass();
    }

    /** Full vs Hybrid 输出对照。 */
    private static void testFullVsHybridComparison() {
        System.out.print("  Full vs Hybrid output ... ");
        List<StyledSegment> segs = List.of(
                StyledSegment.styled("Grants bonus damage of ", GRAY),
                StyledSegment.styled("+150", GREEN),
                StyledSegment.styled(" to all attacks.", GRAY)
        );

        TspRegistry fullReg = new TspRegistry();
        String full = new TspEncoder(fullReg, true).encode(segs);

        TspRegistry hybridReg = new TspRegistry();
        String hybrid = TspEncoder.withHybrid(hybridReg, segs, true).encode(segs);

        // Full: [[0||Grants...]][[1||+150]][[0|| to all attacks.]] (GRAY 也 token)
        assertTrue(full.contains("||Grants"), "Full has GRAY token: " + full);
        // Hybrid: Grants bonus damage of [[0||+150]] to all attacks. (GRAY 裸文本)
        assertTrue(hybrid.startsWith("Grants bonus damage of "), "Hybrid GRAY is plain: " + hybrid);
        assertTrue(!hybrid.contains("||Grants"), "Hybrid no GRAY token: " + hybrid);
        pass();
    }

    /** 合并相邻同色段：附魔段 token 数下降。 */
    private static void testMergeAdjacentSameColor() {
        System.out.print("  Merge adjacent same-color ... ");
        Style GOLD = Style.of("#FFAA00");
        Style PURPLE = Style.of("#FF55FF");
        List<StyledSegment> segs = new java.util.ArrayList<>(List.of(
                StyledSegment.styled("Chimera 5", PURPLE),
                StyledSegment.styled(", ", PURPLE),
                StyledSegment.styled("Champion 10", GOLD),
                StyledSegment.styled(", ", GOLD),
                StyledSegment.styled("Bane of Arthropods 7", GOLD)
        ));
        List<StyledSegment> merged = HybridPolicy.mergeAdjacentSameColor(segs);
        assertEq(2, merged.size(), "5 segs -> 2 merged");
        assertEq("Chimera 5, ", merged.get(0).text(), "purple merged");
        assertEq("Champion 10, Bane of Arthropods 7", merged.get(1).text(), "gold merged");
        assertEq(PURPLE, merged.get(0).style(), "purple style kept");
        assertEq(GOLD, merged.get(1).style(), "gold style kept");
        pass();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static StyledSegment findSegByText(List<StyledSegment> segs, String text) {
        for (StyledSegment s : segs) {
            if (s.text().equals(text)) return s;
        }
        throw new AssertionError("segment not found: " + text);
    }

    private static void assertEq(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) throw new AssertionError(label + ": condition is false");
    }

    private static void pass() { passed++; System.out.println("PASS"); }
}
