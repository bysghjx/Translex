package top.iencand.translex.client.translate.model;

/**
 * TranslationFormatRegistry 注册表与格式族测试。
 */
public final class TranslationFormatRegistryTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== TranslationFormatRegistry Tests ===\n");

        testForId_SN();
        testForId_TSP();
        testForId_TSP_FULL();
        testForId_HYBRID();
        testForId_TSP_HYBRID();
        testForId_CaseInsensitive();
        testCanonicalId();
        testTspSyntaxCapability();
        testForId_Null_Fallback();
        testForId_Unknown_Fallback();

        System.out.println("\n---");
        System.out.println("Passed: " + passed + "  Failed: " + failed);
        if (failed > 0) System.exit(1);
        System.out.println("All TranslationFormatRegistry tests passed.");
    }

    private static void testForId_SN() {
        System.out.print("  forId SN ... ");
        TranslationFormat fmt = TranslationFormatRegistry.forId("SN");
        assertTrue(fmt instanceof SnFormat, "SN -> SnFormat");
        assertEq("SN", fmt.id(), "id is SN");
    }

    private static void testForId_TSP() {
        System.out.print("  forId TSP ... ");
        TranslationFormat fmt = TranslationFormatRegistry.forId("TSP", true);
        assertTrue(fmt instanceof TspFormat, "TSP -> TspFormat");
        assertEq("TSP", fmt.id(), "id is TSP");
    }

    private static void testForId_TSP_FULL() {
        System.out.print("  forId TSP-FULL ... ");
        TranslationFormat fmt = TranslationFormatRegistry.forId("TSP-FULL", true);
        assertTrue(fmt instanceof TspFormat, "TSP-FULL -> TspFormat");
        assertEq("TSP", fmt.id(), "TSP-FULL canonical id is TSP");
    }

    private static void testForId_HYBRID() {
        System.out.print("  forId HYBRID ... ");
        TranslationFormat fmt = TranslationFormatRegistry.forId("HYBRID", true);
        assertTrue(fmt instanceof TspFormat, "HYBRID -> TspFormat");
        assertEq("HYBRID", fmt.id(), "id is HYBRID");
    }

    private static void testForId_TSP_HYBRID() {
        System.out.print("  forId TSP-HYBRID ... ");
        TranslationFormat fmt = TranslationFormatRegistry.forId("TSP-HYBRID", true);
        assertTrue(fmt instanceof TspFormat, "TSP-HYBRID -> TspFormat");
        assertEq("HYBRID", fmt.id(), "id is HYBRID");
    }

    private static void testForId_CaseInsensitive() {
        System.out.print("  forId case insensitive ... ");
        TranslationFormat fmt1 = TranslationFormatRegistry.forId("sn", true);
        assertTrue(fmt1 instanceof SnFormat, "sn -> SnFormat");
        TranslationFormat fmt2 = TranslationFormatRegistry.forId("tsp", true);
        assertTrue(fmt2 instanceof TspFormat, "tsp -> TspFormat");
        TranslationFormat fmt3 = TranslationFormatRegistry.forId("hybrid", true);
        assertTrue(fmt3 instanceof TspFormat, "hybrid -> TspFormat");
    }

    private static void testCanonicalId() {
        System.out.print("  canonical ids ... ");
        assertEq("TSP", TranslationFormatRegistry.canonicalId("TSP-FULL"),
                "display full alias");
        assertEq("HYBRID", TranslationFormatRegistry.canonicalId("TSP-HYBRID"),
                "legacy hybrid alias");
        assertEq("HYBRID", TranslationFormatRegistry.canonicalId(" hybrid "),
                "trimmed hybrid");
        assertEq("SN", TranslationFormatRegistry.canonicalId(null),
                "null defaults to SN");
    }

    private static void testTspSyntaxCapability() {
        System.out.print("  TSP syntax capability ... ");
        assertTrue(!TranslationFormatRegistry.usesTspSyntax("SN"), "SN syntax");
        assertTrue(TranslationFormatRegistry.usesTspSyntax("TSP"), "TSP syntax");
        assertTrue(TranslationFormatRegistry.usesTspSyntax("TSP-FULL"),
                "display full syntax");
        assertTrue(TranslationFormatRegistry.usesTspSyntax("HYBRID"), "HYBRID syntax");
        assertTrue(TranslationFormatRegistry.usesTspSyntax("TSP-HYBRID"),
                "legacy HYBRID syntax");
    }

    private static void testForId_Null_Fallback() {
        System.out.print("  forId null fallback ... ");
        TranslationFormat fmt = TranslationFormatRegistry.forId(null);
        assertTrue(fmt instanceof SnFormat, "null -> SnFormat fallback");
        assertEq("SN", fmt.id(), "id is SN");
    }

    private static void testForId_Unknown_Fallback() {
        System.out.print("  forId unknown fallback ... ");
        TranslationFormat fmt = TranslationFormatRegistry.forId("UNKNOWN_FORMAT");
        assertTrue(fmt instanceof SnFormat, "unknown -> SnFormat fallback");
        assertEq("SN", fmt.id(), "id is SN");
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

    private static void pass() { passed++; }
}
