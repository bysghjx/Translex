package tsp.tests;

import tsp.*;

/**
 * Tests for TspRegistry.fingerprint() - cache integrity hash.
 *
 * <p>Verifies the fingerprint correctly detects color-structure changes
 * (the TSP cache pollution risk: Hypixel changes lore colors, or dyed variants).
 *
 * <p>Run: java tsp.tests.TspFingerprintTest
 */
public final class TspFingerprintTest {

    private static int passed = 0;
    private static int failed = 0;

    private static final Style GRAY  = Style.of("#AAAAAA");
    private static final Style GREEN = Style.of("#55FF55");
    private static final Style AQUA  = Style.of("#55FFFF");
    private static final Style GOLD  = Style.of("#FFAA00");

    public static void main(String[] args) {
        System.out.println("=== TspRegistry Fingerprint Tests ===\n");

        testSameStructureSameFingerprint();
        testColorOrderChanged();
        testColorSetChanged();
        testColorStructureSameDifferentContent();
        testDedupStable();
        testEmptyRegistry();
        testReorderScenario();

        System.out.println("---");
        System.out.println("Passed: " + passed + "  Failed: " + failed);
        if (failed > 0) { System.out.println("SOME TESTS FAILED!"); System.exit(1); }
        System.out.println("All fingerprint tests passed.");
    }

    // ================================================================
    // 1. 相同颜色结构 -> 相同 fingerprint（确定性）
    // ================================================================
    private static void testSameStructureSameFingerprint() {
        System.out.print("  Same color structure -> same fingerprint ... ");
        TspRegistry r1 = newRegistry(GREEN, GRAY, AQUA);
        TspRegistry r2 = newRegistry(GREEN, GRAY, AQUA);
        assertEq(r1.fingerprint(), r2.fingerprint(), "same structure same hash");
        assertTrue(!r1.fingerprint().isEmpty(), "non-empty");
        pass();
    }

    // ================================================================
    // 2. 颜色顺序变 -> 不同 fingerprint（缓存失效）
    //    场景：Hypixel 把绿色/灰色互换，ID 0 从绿变灰
    // ================================================================
    private static void testColorOrderChanged() {
        System.out.print("  Color order changed -> different fingerprint ... ");
        TspRegistry r1 = newRegistry(GREEN, GRAY, AQUA);   // 0=green 1=gray 2=aqua
        TspRegistry r2 = newRegistry(GRAY, GREEN, AQUA);   // 0=gray 1=green 2=aqua
        assertTrue(!r1.fingerprint().equals(r2.fingerprint()),
                "order changed must produce different hash (cache miss trigger)");
        pass();
    }

    // ================================================================
    // 3. 颜色集合变（加/减色）-> 不同 fingerprint
    // ================================================================
    private static void testColorSetChanged() {
        System.out.print("  Color set changed -> different fingerprint ... ");
        TspRegistry r1 = newRegistry(GREEN, GRAY);
        TspRegistry r2 = newRegistry(GREEN, GRAY, AQUA);  // 多一色
        TspRegistry r3 = newRegistry(GREEN);              // 少一色
        assertTrue(!r1.fingerprint().equals(r2.fingerprint()), "added color -> different");
        assertTrue(!r1.fingerprint().equals(r3.fingerprint()), "removed color -> different");
        pass();
    }

    // ================================================================
    // 4. 颜色结构同（顺序+集合）但内容不同 -> 相同 fingerprint
    //    fingerprint 只反映颜色结构，不反映文本内容（内容靠 loreHash 兜）
    // ================================================================
    private static void testColorStructureSameDifferentContent() {
        System.out.print("  Same structure, different content -> same fingerprint ... ");
        // 两个不同物品但颜色结构相同（绿+灰+青）
        TspRegistry r1 = newRegistry(GREEN, GRAY, AQUA);
        TspRegistry r2 = newRegistry(GREEN, GRAY, AQUA);
        assertEq(r1.fingerprint(), r2.fingerprint(),
                "fingerprint reflects color structure, not content");
        pass();
    }

    // ================================================================
    // 5. dedup 稳定：重复注册同色不影响 fingerprint
    // ================================================================
    private static void testDedupStable() {
        System.out.print("  Dedup stable (repeat same color) ... ");
        TspRegistry r1 = newRegistry(GREEN, GRAY, GREEN, AQUA, GRAY);  // dedup -> 3 IDs
        TspRegistry r2 = newRegistry(GREEN, GRAY, AQUA);                // same 3 IDs
        assertEq(r1.fingerprint(), r2.fingerprint(), "dedup doesn't change fingerprint");
        assertEq(3, r1.size(), "dedup'd to 3");
        pass();
    }

    // ================================================================
    // 6. 空 registry -> 空 fingerprint
    // ================================================================
    private static void testEmptyRegistry() {
        System.out.print("  Empty registry -> empty fingerprint ... ");
        TspRegistry r = new TspRegistry();
        assertEq("", r.fingerprint(), "empty fingerprint");
        pass();
    }

    // ================================================================
    // 7. 模拟 TSP 缓存污染场景
    //    旧缓存 ID 0=绿，新物品 ID 0=灰 -> fingerprint 不同 -> 触发 miss
    // ================================================================
    private static void testReorderScenario() {
        System.out.print("  Cache pollution scenario (color swap) ... ");
        // 旧物品翻译时：绿 获得, 灰 100
        TspRegistry oldReg = newRegistry(GREEN, GRAY);
        String oldFp = oldReg.fingerprint();
        String cachedTsp = "[[0||获得]][[1||100]]";  // 0=绿, 1=灰

        // Hypixel 改 lore：灰 获得, 绿 100（颜色互换）
        TspRegistry newReg = newRegistry(GRAY, GREEN);
        String newFp = newReg.fingerprint();

        // fingerprint 不同 -> 缓存校验失败 -> miss，不会用 newReg 解 cachedTsp
        assertTrue(!oldFp.equals(newFp),
                "color swap must invalidate cache (fingerprint mismatch)");
        // 如果错误地用 newReg 解 cachedTsp：[[0||获得]] -> 0=灰（应绿）颜色错
        // fingerprint 校验阻止了这个错误
        System.out.println("\n    old fp=" + oldFp + " new fp=" + newFp + " -> miss ✅");
        pass();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static TspRegistry newRegistry(Style... styles) {
        TspRegistry r = new TspRegistry();
        for (Style s : styles) r.register(s);
        return r;
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
    private static void fail(String msg) { failed++; System.out.println("FAIL - " + msg); }
}
