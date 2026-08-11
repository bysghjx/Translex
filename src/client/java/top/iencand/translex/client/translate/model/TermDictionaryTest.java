package top.iencand.translex.client.translate.model;

/**
 * TermDictionary 验证测试（独立 main，用 gradle runTermTest 运行）。
 * 验证：apply 替换、applyToTemplate (TSP+SN)、hasEnglishRemaining、learn、短路模拟。
 */
public final class TermDictionaryTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== TermDictionary Tests ===\n");
        TermDictionary dict = TermDictionary.get();

        testApply_EnchantName(dict);
        testApply_StatName(dict);
        testApplyToTemplate_TSP(dict);
        testApplyToTemplate_SN(dict);
        testHasEnglishRemaining_FullHit(dict);
        testHasEnglishRemaining_PartialHit(dict);
        testLearn_Runtime(dict);
        testShortCircuitSimulation(dict);

        System.out.println("\n---");
        System.out.println("Passed: " + passed + "  Failed: " + failed);
        if (failed > 0) System.exit(1);
        System.out.println("All TermDictionary tests passed.");
    }

    private static void testApply_EnchantName(TermDictionary dict) {
        System.out.print("  apply enchant name ... ");
        assertEq("锋利 7", dict.apply("Sharpness 7"), "Sharpness 7");
        assertEq("亡灵杀手 5", dict.apply("Smite 5"), "Smite 5");
        assertEq("节肢杀手 7", dict.apply("Bane of Arthropods 7"), "Bane of Arthropods 7");
        assertEq("冠军 10", dict.apply("Champion 10"), "Champion 10");
        pass();
    }

    private static void testApply_StatName(TermDictionary dict) {
        System.out.print("  apply stat name (SkyBlockTerm) ... ");
        assertEq("力量", dict.apply("Strength"), "Strength");
        assertEq("破坏力 8", dict.apply("Breaking Power 8"), "Breaking Power 8");
        pass();
    }

    private static void testApplyToTemplate_TSP(TermDictionary dict) {
        System.out.print("  applyToTemplate TSP ... ");
        String tmpl = "[[0||Chimera 5, ]][[1||Champion 10, Bane of Arthropods 7]]";
        String glossed = dict.applyToTemplate(tmpl, "TSP");
        assertTrue(glossed.contains("[[0||奇美拉 5, ]]"), "Chimera replaced: " + glossed);
        assertTrue(glossed.contains("[[1||冠军 10, 节肢杀手 7]]"), "Champion+Bane replaced: " + glossed);
        assertTrue(glossed.contains("[[0||") && glossed.contains("]]"), "tag structure preserved");
        pass();
    }

    private static void testApplyToTemplate_SN(TermDictionary dict) {
        System.out.print("  applyToTemplate SN ... ");
        String tmpl = "<s0>Sharpness </s0><s1>7</s1>";
        String glossed = dict.applyToTemplate(tmpl, "SN");
        assertTrue(glossed.contains("<s0>锋利 </s0>"), "Sharpness replaced: " + glossed);
        assertTrue(glossed.contains("<s1>7</s1>"), "s1 preserved");
        pass();
    }

    private static void testHasEnglishRemaining_FullHit(TermDictionary dict) {
        System.out.print("  hasEnglishRemaining full hit ... ");
        String glossed = "[[0||奇美拉 5, ]][[1||冠军 10, 节肢杀手 7]]";
        assertTrue(!dict.hasEnglishRemaining(glossed, "TSP"), "no english -> can short-circuit");
        pass();
    }

    private static void testHasEnglishRemaining_PartialHit(TermDictionary dict) {
        System.out.print("  hasEnglishRemaining partial hit ... ");
        // "Unknown" 不在词典 -> 有英文剩余
        String glossed = "[[0||奇美拉 5, ]][[1||Unknown 3]]";
        assertTrue(dict.hasEnglishRemaining(glossed, "TSP"), "has english -> need AI");
        pass();
    }

    private static void testLearn_Runtime(TermDictionary dict) {
        System.out.print("  learn runtime term ... ");
        // 先确认 Unknown 没翻译
        String before = dict.apply("UnknownEnchant 5");
        assertTrue(before.contains("UnknownEnchant"), "before learn: not translated");
        // 学习
        dict.learn("UnknownEnchant", "未知附魔");
        String after = dict.apply("UnknownEnchant 5");
        assertEq("未知附魔 5", after, "after learn");
        // 不覆盖已有（Sharpness 已有翻译，learn 不覆盖）
        dict.learn("Sharpness", "错误翻译");
        assertEq("锋利", dict.apply("Sharpness"), "learn doesn't overwrite preset");
        pass();
    }

    private static void testShortCircuitSimulation(TermDictionary dict) {
        System.out.print("  short-circuit simulation ... ");
        // 模拟附魔段编码 + 词典替换 + 短路判断
        String encoded = "[[0||Chimera 5, ]][[1||Champion 10, Bane of Arthropods 7]]";
        String glossed = dict.applyToTemplate(encoded, "TSP");
        boolean needAI = dict.hasEnglishRemaining(glossed, "TSP");
        assertTrue(!needAI, "enchant para fully covered -> no AI needed");
        System.out.println("(glossed: " + glossed + ")");
        pass();
    }

    private static void assertEq(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            failed++;
            System.out.println("FAIL - " + label + ": expected <" + expected + "> got <" + actual + ">");
            return;
        }
        pass();
    }

    private static void assertTrue(boolean cond, String label) {
        if (!cond) {
            failed++;
            System.out.println("FAIL - " + label);
            return;
        }
        pass();
    }

    private static void pass() { passed++; System.out.println("PASS"); }
}
