package top.iencand.translex.client.translate.pipeline;

import net.minecraft.network.chat.Component;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.translate.cache.TranslationCacheManager;
import top.iencand.translex.client.translate.model.StyledText;
import top.iencand.translex.client.translate.render.ChatRenderer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ItemTranslationValidator + ItemTranslationResultCollector 回归测试。
 *
 * <p>纯 JVM 运行（不起 Minecraft client）：
 * <ul>
 *   <li>Validator 是无状态纯逻辑，直接覆盖标签缺失/多标签、占位符缺失、extra 清理、附魔学习。</li>
 *   <li>Collector 通过反射注入 {@link ModConfig#instance} 避开 FabricLoader，并用 FakeRenderer
 *       计数渲染/错误提示，覆盖并发完成只渲染一次、多个错误只提示一次、段落/单行回退。</li>
 * </ul>
 *
 * <p>Run: {@code gradlew runItemTests}</p>
 */
public final class ItemPipelineTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== ItemPipeline (Validator + Collector) Tests ===\n");

        // Validator
        testValidator_KeepsValid();
        testValidator_MissingTag_Rejected();
        testValidator_TagCollapse_Rejected();
        testValidator_MissingPlaceholder_Rejected();
        testValidator_ExtraPlaceholder_CleanedAndKept();
        testValidator_NullInputs();
        testValidator_LearnEnchant_Tsp();
        testValidator_LearnEnchant_Sn();
        testValidator_LearnNonEnchant_Skipped();
        testValidator_ExtractTokens_Tsp();
        testValidator_ExtractTokens_LegacyHybridAlias();
        testValidator_ExtractTokens_Sn();

        // Collector
        testCollector_ConcurrentFinish_RendersOnce();
        testCollector_MultipleErrors_ReportOnce();
        testCollector_ErrorLine_FallbackToOriginal();
        testCollector_ErrorParagraph_FallbackToOriginal();
        testCollector_ValidLine_StoresResult();
        testCollector_ValidParagraph_StoresResult();
        testCollector_FinishImmediately_NoTasks();

        System.out.println("\n---");
        System.out.println("Passed: " + passed + "  Failed: " + failed);
        if (failed > 0) {
            System.out.println("SOME TESTS FAILED!");
            System.exit(1);
        }
        System.out.println("All ItemPipeline tests passed.");
    }

    // ================================================================
    // Validator
    // ================================================================

    private static void testValidator_KeepsValid() {
        System.out.print("  validator keeps valid ... ");
        String orig = "<s0>Sharpness </s0><s1>{0}</s1>";
        String ai = "<s0>锋利 </s0><s1>{0}</s1>";
        String res = ItemTranslationValidator.validateTranslation(orig, ai, 3);
        assertEq(ai, res, "valid ai result passes through unchanged");
    }

    private static void testValidator_MissingTag_Rejected() {
        System.out.print("  validator missing tag -> null ... ");
        String orig = "<s0>Defense: </s0><s1>{0}</s1>";
        String ai = "防御力 {0}";  // lost both tags
        String res = ItemTranslationValidator.validateTranslation(orig, ai, 1);
        assertTrue(res == null, "ai missing tags -> fallback to original (null)");
    }

    private static void testValidator_TagCollapse_Rejected() {
        System.out.print("  validator tag collapse -> null ... ");
        String orig = "<s0>a</s0><s1>b</s1><s2>c</s2>";
        String ai = "<s0>甲</s0><s1>乙丙</s1>";  // 3 tags -> 2 tags
        String res = ItemTranslationValidator.validateTranslation(orig, ai, 2);
        assertTrue(res == null, "collapsed tags -> null");
    }

    private static void testValidator_MissingPlaceholder_Rejected() {
        System.out.print("  validator missing placeholder -> null ... ");
        String orig = "<s0>Damage: </s0><s1>{0}</s1>";
        String ai = "<s0>伤害：</s0><s1></s1>";  // {0} lost
        String res = ItemTranslationValidator.validateTranslation(orig, ai, 4);
        assertTrue(res == null, "lost placeholder -> null");
    }

    private static void testValidator_ExtraPlaceholder_CleanedAndKept() {
        System.out.print("  validator extra placeholder cleaned ... ");
        String orig = "<s0>Damage: </s0><s1>{0}</s1>";
        String ai = "<s0>伤害：</s0><s1>{0}</s1><s2>{1}</s2>";  // extra {1}
        String res = ItemTranslationValidator.validateTranslation(orig, ai, 5);
        assertTrue(res != null, "extra placeholder does not reject");
        assertTrue(res != null && !res.contains("{1}"), "extra {1} removed: " + res);
    }

    private static void testValidator_NullInputs() {
        System.out.print("  validator null inputs ... ");
        assertEq("x", ItemTranslationValidator.validateTranslation(null, "x", 0), "null original -> passthrough ai result");
        assertEq(null, ItemTranslationValidator.validateTranslation("x", null, 0), "null ai -> passthrough null");
    }

    private static void testValidator_LearnEnchant_Tsp() {
        System.out.print("  validator learn enchant TSP ... ");
        String orig = "[[0||Sharpness 5, ]][[1||Smite 3]]";
        String trans = "[[0||锋利 5, ]][[1||亡灵杀手 3]]";
        // learn 走 TermDictionary，验证不抛异常且能学到 Sharpness
        ItemTranslationValidator.learnFromTranslation(orig, trans, "TSP");
        String applied = top.iencand.translex.client.translate.model.TermDictionary.get().apply("Sharpness 5");
        assertTrue(applied.contains("锋利"), "learned TSP enchant: " + applied);
    }

    private static void testValidator_LearnEnchant_Sn() {
        System.out.print("  validator learn enchant SN ... ");
        String orig = "<s0>Sharpness </s0><s1>5</s1>";
        String trans = "<s0>锋利 </s0><s1>5</s1>";
        ItemTranslationValidator.learnFromTranslation(orig, trans, "SN");
        String applied = top.iencand.translex.client.translate.model.TermDictionary.get().apply("Sharpness");
        assertTrue(applied.contains("锋利"), "learned SN enchant: " + applied);
    }

    private static void testValidator_LearnNonEnchant_Skipped() {
        System.out.print("  validator non-enchant not learned ... ");
        String orig = "<s0>Defense: </s0><s1>{0}</s1>";
        String trans = "<s0>防御力：</s0><s1>{0}</s1>";
        // 不应抛异常，且不应污染字典（无 "Name Number" 双模式）
        ItemTranslationValidator.learnFromTranslation(orig, trans, "SN");
        pass();
    }

    private static void testValidator_ExtractTokens_Tsp() {
        System.out.print("  validate extract tokens TSP ... ");
        var map = ItemTranslationValidator.extractTokensForLearn(
                "[[0||Sharpness 5, ]][[1||Smite 3]]", "TSP");
        assertEq(2, map.size(), "two tokens");
        assertTrue(map.containsKey("0") && map.get("0").contains("Sharpness"), "token 0");
        assertTrue(map.containsKey("1") && map.get("1").contains("Smite"), "token 1");
    }

    private static void testValidator_ExtractTokens_LegacyHybridAlias() {
        System.out.print("  validate extract tokens legacy HYBRID alias ... ");
        var map = ItemTranslationValidator.extractTokensForLearn(
                "[[0||Sharpness 5, ]][[1||Smite 3]]", "TSP-HYBRID");
        assertEq(2, map.size(), "legacy alias uses TSP token grammar");
        assertTrue(map.containsKey("0") && map.get("0").contains("Sharpness"), "token 0");
    }

    private static void testValidator_ExtractTokens_Sn() {
        System.out.print("  validate extract tokens SN ... ");
        var map = ItemTranslationValidator.extractTokensForLearn(
                "<s0>Sharpness </s0><s1>5</s1>", "SN");
        assertEq(2, map.size(), "two tokens");
        assertTrue(map.get("0").trim().equals("Sharpness"), "token 0 Sharpness");
        assertEq("5", map.get("1"), "token 1 value");
    }

    // ================================================================
    // Collector
    // ================================================================

    private static void testCollector_ConcurrentFinish_RendersOnce() throws Exception {
        System.out.print("  collector concurrent finish renders once ... ");
        FakeRenderer renderer = new FakeRenderer();
        ItemTranslationResultCollector c = newCollector(renderer, 2);
        // 两个任务同时完成，可能都会触发 maybeFinish，但 finish 的 CAS 应保证只 render 一次
        CompletableFuture<String> f1 = new CompletableFuture<>();
        CompletableFuture<String> f2 = new CompletableFuture<>();
        c.attach(Arrays.asList(ItemTranslationTask.ofLine(0, f1,
                StyledText.of(net.minecraft.network.chat.Component.literal("orig0")),
                "<s0>{0}</s0>", "k0", "SN", null),
                ItemTranslationTask.ofLine(1, f2,
                        StyledText.of(net.minecraft.network.chat.Component.literal("orig1")),
                        "<s0>{0}</s0>", "k1", "SN", null)));
        f1.complete("<s0>译0</s0>");
        f2.complete("<s0>译1</s0>");
        assertEq(1, renderer.resultRenders.get(), "finish renders result exactly once");
        assertEq(0, renderer.errorRenders.get(), "no error rendered");
    }

    private static void testCollector_MultipleErrors_ReportOnce() throws Exception {
        System.out.print("  collector multiple errors report once ... ");
        FakeRenderer renderer = new FakeRenderer();
        ItemTranslationResultCollector c = newCollector(renderer, 2);
        CompletableFuture<String> f1 = new CompletableFuture<>();
        CompletableFuture<String> f2 = new CompletableFuture<>();
        c.attach(Arrays.asList(ItemTranslationTask.ofLine(0, f1,
                StyledText.of(net.minecraft.network.chat.Component.literal("orig0")),
                "<s0>{0}</s0>", "k0", "SN", null),
                ItemTranslationTask.ofLine(1, f2,
                        StyledText.of(net.minecraft.network.chat.Component.literal("orig1")),
                        "<s0>{0}</s0>", "k1", "SN", null)));
        f1.complete("\u00A7cAI 请求失败 1");
        f2.complete("\u00A7cAI 请求失败 2");
        assertEq(1, renderer.errorRenders.get(), "multiple errors -> only one user error prompt");
        // 错误行应回退原文
        assertTrue(renderer.lastResult != null && renderer.lastResult.contains("orig0")
                        && renderer.lastResult.contains("orig1"),
                "error lines fall back to original: " + renderer.lastResult);
    }

    private static void testCollector_ErrorLine_FallbackToOriginal() throws Exception {
        System.out.print("  collector error line fallback ... ");
        FakeRenderer renderer = new FakeRenderer();
        ItemTranslationResultCollector c = newCollector(renderer, 1);
        CompletableFuture<String> f = new CompletableFuture<>();
        c.attach(List.of(ItemTranslationTask.ofLine(0, f,
                StyledText.of(net.minecraft.network.chat.Component.literal("origLine")),
                "<s0>{0}</s0>", "k0", "SN", null)));
        f.complete("\u00A7cboom");
        assertTrue(renderer.lastResult != null && renderer.lastResult.contains("origLine"),
                "fallback to original text: " + renderer.lastResult);
    }

    private static void testCollector_ErrorParagraph_FallbackToOriginal() throws Exception {
        System.out.print("  collector error paragraph fallback ... ");
        FakeRenderer renderer = new FakeRenderer();
        ItemTranslationResultCollector c = newCollector(renderer, 2);
        CompletableFuture<String> f = new CompletableFuture<>();
        Component paraOrig = Component.literal("line0\nline1");
        c.attach(List.of(ItemTranslationTask.ofParagraph(0, 2, f, StyledText.of(paraOrig),
                "<s0>{0}</s0>", "k0", "SN", null)));
        f.complete("\u00A7cboom");
        assertTrue(renderer.lastResult != null && renderer.lastResult.contains("orig0")
                        && renderer.lastResult.contains("orig1"),
                "paragraph fallback keeps original lines: " + renderer.lastResult);
    }

    private static void testCollector_ValidLine_StoresResult() throws Exception {
        System.out.print("  collector valid line stores result ... ");
        FakeRenderer renderer = new FakeRenderer();
        ItemTranslationResultCollector c = newCollector(renderer, 1);
        CompletableFuture<String> f = new CompletableFuture<>();
        c.attach(List.of(ItemTranslationTask.ofLine(0, f,
                StyledText.of(net.minecraft.network.chat.Component.literal("Damage: 10")),
                "<s0>Damage: </s0><s1>{0}</s1>", "k0", "SN", null)));
        f.complete("<s0>伤害：</s0><s1>{0}</s1>");
        assertTrue(renderer.lastResult != null && renderer.lastResult.contains("伤害"),
                "valid line rendered translated: " + renderer.lastResult);
    }

    private static void testCollector_ValidParagraph_StoresResult() throws Exception {
        System.out.print("  collector valid paragraph stores result ... ");
        FakeRenderer renderer = new FakeRenderer();
        ItemTranslationResultCollector c = newCollector(renderer, 2);
        CompletableFuture<String> f = new CompletableFuture<>();
        Component paraOrig = Component.literal("desc line one\ndesc line two");
        c.attach(List.of(ItemTranslationTask.ofParagraph(0, 2, f, StyledText.of(paraOrig),
                "<s0>desc line one</s0>", "k0", "SN", null)));
        f.complete("<s0>描述第一行 描述第二行</s0>");
        assertTrue(renderer.lastResult != null && renderer.lastResult.contains("描述"),
                "valid paragraph rendered translated: " + renderer.lastResult);
    }

    private static void testCollector_FinishImmediately_NoTasks() throws Exception {
        System.out.print("  collector finish immediately no tasks ... ");
        FakeRenderer renderer = new FakeRenderer();
        ItemTranslationResultCollector c = newCollector(renderer, 0);
        c.finishImmediately();
        assertEq(1, renderer.resultRenders.get(), "finishImmediately renders once");
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static ItemTranslationResultCollector newCollector(ChatRenderer renderer, int n) throws Exception {
        injectModConfig();
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < n; i++) lines.add(Component.literal("orig" + i));
        Component[] results = new Component[n];
        String[] stored = new String[n];
        Set<Integer> ai = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) ai.add(i);
        TranslationCacheManager cacheManager = new TranslationCacheManager();
        cacheManager.shutdown();
        return new ItemTranslationResultCollector(
                lines, "test", null, results, stored, "TD", ai, false,
                renderer, cacheManager, null,
                tsp.RecoveryStats.getInstance());
    }

    private static void injectModConfig() throws Exception {
        Field f = ModConfig.class.getDeclaredField("instance");
        f.setAccessible(true);
        if (f.get(null) == null) {
            java.lang.reflect.Constructor<ModConfig> ctor = ModConfig.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ModConfig cfg = ctor.newInstance();
            cfg.outputMode = "chat";
            f.set(null, cfg);
        }
    }

    /** 计数渲染的假 renderer，避免触发 Minecraft.getInstance()。 */
    private static final class FakeRenderer extends ChatRenderer {
        final AtomicInteger resultRenders = new AtomicInteger();
        final AtomicInteger errorRenders = new AtomicInteger();
        volatile String lastResult;
        volatile String lastError;

        @Override
        public void renderResult(String originalText, String translatedText, String displayId) {
            resultRenders.incrementAndGet();
            lastResult = translatedText;
        }

        @Override
        public void renderError(String errorDetail, String displayId) {
            errorRenders.incrementAndGet();
            lastError = errorDetail;
        }
    }

    private static void assertEq(Object expected, Object actual, String label) {
        boolean eq = (expected == null) ? (actual == null) : expected.equals(actual);
        if (!eq) {
            failed++;
            System.out.println("FAIL - " + label + ": expected <" + expected + "> got <" + actual + ">");
            return;
        }
        passed++;
        System.out.println("PASS");
    }

    private static void assertTrue(boolean cond, String label) {
        if (!cond) {
            failed++;
            System.out.println("FAIL - " + label);
            return;
        }
        passed++;
        System.out.println("PASS");
    }

    private static void pass() {
        passed++;
        System.out.println("PASS");
    }
}
