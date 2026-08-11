package top.iencand.translex.client.translate.model;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * SN/TSP/HYBRID 编解码往返测试。
 *
 * <p>SN decode 路径会经过 StyleCodec.reapply 触发 ModConfig.get()，
 * 在纯 JVM 环境（无 Fabric）不可用。SN 的 encode 和模板验证在此测试，
 * SN decode 的往返行为由 StyledTextTest 中 renderSn 测试覆盖。</p>
 */
public final class FormatRoundTripTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== Format Round-Trip Tests ===\n");

        // SN: encode + template
        testSn_Id();
        testSn_Encode_Plain();
        testSn_Encode_Styled();
        testSn_Encode_WithNumbers();
        testSn_StripFormatTags();

        // TSP FULL: full round-trip (uses renderTsp, no ModConfig)
        testTsp_Id();
        testTsp_RoundTrip_Plain();
        testTsp_RoundTrip_Styled();
        testTsp_RoundTrip_WithNumbers();
        testTsp_StripFormatTags();
        testTsp_RegistryHash_Match();
        testTsp_RegistryHash_Mismatch();
        testTsp_ChecksumOff_Basic();

        // HYBRID: full round-trip
        testHybrid_Id();
        testHybrid_RoundTrip_Plain();
        testHybrid_RoundTrip_Styled();
        testHybrid_RoundTrip_WithNumbers();

        System.out.println("\n---");
        System.out.println("Passed: " + passed + "  Failed: " + failed);
        if (failed > 0) System.exit(1);
        System.out.println("All FormatRoundTrip tests passed.");
    }

    // ========== SN (encode only, decode requires Fabric) ==========

    private static void testSn_Id() {
        System.out.print("  SN id ... ");
        assertEq("SN", new SnFormat().id(), "id is SN");
    }

    private static void testSn_Encode_Plain() {
        System.out.print("  SN encode plain ... ");
        SnFormat fmt = new SnFormat();
        Component c = Component.literal("Hello World");
        TranslationFormat.Encoded enc = fmt.encode(c);
        assertTrue(enc.registryHash() == null, "SN has no registryHash");
        assertEq("Hello World", enc.template(), "plain template");
    }

    private static void testSn_Encode_Styled() {
        System.out.print("  SN encode styled ... ");
        SnFormat fmt = new SnFormat();
        MutableComponent c = Component.empty();
        c.append(Component.literal("Red").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        TranslationFormat.Encoded enc = fmt.encode(c);
        assertTrue(enc.template().contains("<s0>"), "encode has tag");
        assertTrue(enc.template().contains("Red"), "encode has text");
    }

    private static void testSn_Encode_WithNumbers() {
        System.out.print("  SN encode with numbers ... ");
        SnFormat fmt = new SnFormat();
        MutableComponent c = Component.empty();
        c.append(Component.literal("Damage: "));
        c.append(Component.literal("+250").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        TranslationFormat.Encoded enc = fmt.encode(c);
        assertTrue(enc.template().contains("{0}"), "template has placeholder");
        assertTrue(enc.template().contains("<s0>"), "template has tag");
    }

    private static void testSn_StripFormatTags() {
        System.out.print("  SN stripFormatTags ... ");
        SnFormat fmt = new SnFormat();
        assertEq("Hello World", fmt.stripFormatTags("<s0>Hello</s0> <s1>World</s1>"),
                "tags stripped");
        assertEq("", fmt.stripFormatTags("<s0></s0>"), "empty tags");
    }

    // ========== TSP FULL ==========

    private static void testTsp_Id() {
        System.out.print("  TSP id ... ");
        assertEq("TSP", new TspFormat().id(), "id is TSP");
    }

    private static void testTsp_RoundTrip_Plain() {
        System.out.print("  TSP round-trip plain ... ");
        TspFormat fmt = new TspFormat();
        Component c = Component.literal("Hello World");
        TranslationFormat.Encoded enc = fmt.encode(c);
        assertTrue(enc.registryHash() != null, "TSP has registryHash");
        Component decoded = fmt.decode(enc.template(), c, false, enc.registryHash());
        assertEq("Hello World", decoded.getString(), "round-trip");
    }

    private static void testTsp_RoundTrip_Styled() {
        System.out.print("  TSP round-trip styled ... ");
        TspFormat fmt = new TspFormat();
        MutableComponent c = Component.empty();
        c.append(Component.literal("Red").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        TranslationFormat.Encoded enc = fmt.encode(c);
        assertTrue(enc.template().contains("[[0"), "encode has TSP token");
        Component decoded = fmt.decode(enc.template(), c, false, enc.registryHash());
        assertEq("Red", decoded.getString(), "text preserved");
    }

    private static void testTsp_RoundTrip_WithNumbers() {
        System.out.print("  TSP round-trip with numbers ... ");
        TspFormat fmt = new TspFormat();
        MutableComponent c = Component.empty();
        c.append(Component.literal("Damage: "));
        c.append(Component.literal("+250").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        TranslationFormat.Encoded enc = fmt.encode(c);
        assertTrue(enc.template().contains("{0}"), "template has placeholder");
        Component decoded = fmt.decode(enc.template(), c, false, enc.registryHash());
        assertEq("Damage: +250", decoded.getString(), "numbers restored");
    }

    private static void testTsp_StripFormatTags() {
        System.out.print("  TSP stripFormatTags ... ");
        TspFormat fmt = new TspFormat();
        assertEq("Hello World", fmt.stripFormatTags("[[0||Hello]] [[1||World]]"),
                "tags stripped");
        assertEq("", fmt.stripFormatTags("[[0||]]"), "empty tags");
    }

    private static void testTsp_RegistryHash_Match() {
        System.out.print("  TSP registryHash match ... ");
        TspFormat fmt = new TspFormat();
        MutableComponent c = Component.empty();
        c.append(Component.literal("Red").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        TranslationFormat.Encoded enc = fmt.encode(c);
        Component decoded = fmt.decode(enc.template(), c, false, enc.registryHash());
        assertTrue(decoded != null, "matching hash returns non-null");
    }

    private static void testTsp_RegistryHash_Mismatch() {
        System.out.print("  TSP registryHash mismatch ... ");
        TspFormat fmt = new TspFormat();
        MutableComponent c = Component.empty();
        c.append(Component.literal("Red").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        fmt.encode(c);
        Component decoded = fmt.decode("[[0||Red]]", c, false, "deadbeef");
        assertTrue(decoded == null, "mismatched hash returns null");
    }

    private static void testTsp_ChecksumOff_Basic() {
        System.out.print("  TSP checksum off ... ");
        TspFormat fmt = new TspFormat(tsp.TspEncoder.Policy.FULL, false);
        MutableComponent c = Component.empty();
        c.append(Component.literal("Red").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        TranslationFormat.Encoded enc = fmt.encode(c);
        assertTrue(!enc.template().contains(":"), "no hash in template");
        Component decoded = fmt.decode(enc.template(), c, false, enc.registryHash());
        assertEq("Red", decoded.getString(), "round-trip");
    }

    // ========== HYBRID ==========

    private static void testHybrid_Id() {
        System.out.print("  HYBRID id ... ");
        TspFormat fmt = new TspFormat(tsp.TspEncoder.Policy.HYBRID);
        assertEq("HYBRID", fmt.id(), "id is HYBRID");
    }

    private static void testHybrid_RoundTrip_Plain() {
        System.out.print("  HYBRID round-trip plain ... ");
        TspFormat fmt = new TspFormat(tsp.TspEncoder.Policy.HYBRID);
        Component c = Component.literal("Hello World");
        TranslationFormat.Encoded enc = fmt.encode(c);
        assertTrue(enc.registryHash() != null, "HYBRID has registryHash");
        Component decoded = fmt.decode(enc.template(), c, false, enc.registryHash());
        assertEq("Hello World", decoded.getString(), "round-trip");
    }

    private static void testHybrid_RoundTrip_Styled() {
        System.out.print("  HYBRID round-trip styled ... ");
        TspFormat fmt = new TspFormat(tsp.TspEncoder.Policy.HYBRID);
        MutableComponent c = Component.empty();
        c.append(Component.literal("Red").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        TranslationFormat.Encoded enc = fmt.encode(c);
        Component decoded = fmt.decode(enc.template(), c, false, enc.registryHash());
        assertEq("Red", decoded.getString(), "text preserved");
    }

    private static void testHybrid_RoundTrip_WithNumbers() {
        System.out.print("  HYBRID round-trip with numbers ... ");
        TspFormat fmt = new TspFormat(tsp.TspEncoder.Policy.HYBRID);
        MutableComponent c = Component.empty();
        c.append(Component.literal("Price: "));
        c.append(Component.literal("1,234").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFFD700))));
        TranslationFormat.Encoded enc = fmt.encode(c);
        assertTrue(enc.template().contains("{0}"), "template has placeholder");
        Component decoded = fmt.decode(enc.template(), c, false, enc.registryHash());
        assertEq("Price: 1,234", decoded.getString(), "numbers restored");
    }

    // ========== helpers ==========

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
