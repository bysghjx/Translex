package top.iencand.translex.client.translate.model;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import tsp.StyledSegment;

import java.util.List;

/**
 * StyledText 鍥炲綊娴嬭瘯锛氬垱寤恒€佸線杩斻€佹暟瀛椾繚鎶ゃ€佽竟鐣屻€? */
public final class StyledTextTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== StyledText Tests ===\n");

        testOf_PlainText();
        testOf_StyledText();
        testOf_MultiStyle();
        testOf_NullAndEmpty();
        testOf_NumericProtection_Integer();
        testOf_NumericProtection_Percent();
        testOf_NumericProtection_Price();
        testOf_SnTemplate_Content();
        testOf_TspSegments_Content();
        testOf_RestoreNumbers();
        testOf_RenderSn_Basic();
        testOf_RenderSn_Paragraph();
        testOf_RenderTsp_Basic();
        testOf_ToMcStyle_Valid();
        testOf_ToMcStyle_Empty();

        System.out.println("\n---");
        System.out.println("Passed: " + passed + "  Failed: " + failed);
        if (failed > 0) System.exit(1);
        System.out.println("All StyledText tests passed.");
    }

    private static void testOf_PlainText() {
        System.out.print("  of() plain text ... ");
        Component c = Component.literal("Hello World");
        StyledText st = StyledText.of(c);
        assertEq("Hello World", st.plainText(), "plain text");
        assertEq("Hello World", st.snTemplate(), "sn template = plain");
        assertEq(0, st.numericValues().size(), "no numeric values");
        assertEq(1, st.tspSegments().size(), "one tsp segment");
        assertEq("Hello World", st.tspSegments().get(0).text(), "tsp segment text");
        assertTrue(st.tspSegments().get(0).isPlain(), "tsp segment is plain");
    }

    private static void testOf_StyledText() {
        System.out.print("  of() styled text ... ");
        MutableComponent c = Component.empty();
        c.append(Component.literal("Red").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        StyledText st = StyledText.of(c);
        assertEq("Red", st.plainText(), "plain text");
        assertTrue(st.snTemplate().contains("<s0>"), "sn template has tag");
        assertTrue(st.snTemplate().contains("</s0>"), "sn template has close tag");
        assertEq(0, st.numericValues().size(), "no numeric values");
        assertEq(1, st.tspSegments().size(), "one tsp segment");
        assertTrue(!st.tspSegments().get(0).isPlain(), "tsp segment styled");
    }

    private static void testOf_MultiStyle() {
        System.out.print("  of() multi-style ... ");
        MutableComponent c = Component.empty();
        c.append(Component.literal("Red").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        c.append(Component.literal("Blue").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0x5555FF))));
        StyledText st = StyledText.of(c);
        assertEq("RedBlue", st.plainText(), "plain text");
        assertTrue(st.snTemplate().contains("<s0>"), "has s0");
        assertTrue(st.snTemplate().contains("<s1>"), "has s1");
        assertEq(2, st.tspSegments().size(), "two tsp segments");
    }

    private static void testOf_NullAndEmpty() {
        System.out.print("  of() null/empty ... ");
        StyledText stNull = StyledText.of(null);
        assertEq("", stNull.plainText(), "null -> empty plainText");
        assertEq("", stNull.snTemplate(), "null -> empty sn");
        assertEq(0, stNull.numericValues().size(), "null -> zero vals");
        assertEq(0, stNull.tspSegments().size(), "null -> zero segments");

        StyledText stEmpty = StyledText.of(Component.empty());
        assertEq("", stEmpty.plainText(), "empty -> empty plainText");
        assertEq("", stEmpty.snTemplate(), "empty -> empty sn");
    }

    private static void testOf_NumericProtection_Integer() {
        System.out.print("  of() numeric protection (integer) ... ");
        Component c = Component.literal("Damage: ")
                .append(Component.literal("+250").setStyle(
                        Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        StyledText st = StyledText.of(c);
        assertEq("Damage: +250", st.plainText(), "plain text");
        assertEq(1, st.numericValues().size(), "one numeric value");
        assertEq("+250", st.numericValues().get(0), "numeric value");
        assertTrue(st.snTemplate().contains("{0}"), "sn has placeholder {0}");
        String tspText = st.tspSegments().get(1).text();
        assertEq("{0}", tspText, "tsp placeholder");
    }

    private static void testOf_NumericProtection_Percent() {
        System.out.print("  of() numeric protection (percent) ... ");
        Component c = Component.literal("+100%")
                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x55FF55)));
        StyledText st = StyledText.of(c);
        assertEq(1, st.numericValues().size(), "one numeric");
        assertEq("+100%", st.numericValues().get(0), "percent value");
        assertTrue(st.snTemplate().contains("{0}"), "sn has placeholder");
    }

    private static void testOf_NumericProtection_Price() {
        System.out.print("  of() numeric protection (price-like) ... ");
        Component c = Component.literal("1,234.56")
                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFD700)));
        StyledText st = StyledText.of(c);
        assertEq(1, st.numericValues().size(), "one numeric");
        assertEq("1,234.56", st.numericValues().get(0), "price-like value");
        assertTrue(st.snTemplate().contains("{0}"), "sn has placeholder");
    }

    private static void testOf_SnTemplate_Content() {
        System.out.print("  snTemplate content ... ");
        MutableComponent c = Component.empty();
        c.append(Component.literal("Prefix "));
        c.append(Component.literal("+100").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        c.append(Component.literal(" suffix"));
        StyledText st = StyledText.of(c);
        String sn = st.snTemplate();
        assertTrue(sn.startsWith("Prefix "), "starts with plain prefix");
        assertTrue(sn.contains("<s0>"), "has s0 tag");
        assertTrue(sn.contains("{0}"), "has numeric placeholder");
        assertTrue(sn.endsWith(" suffix"), "ends with plain suffix");
    }

    private static void testOf_TspSegments_Content() {
        System.out.print("  tspSegments content ... ");
        MutableComponent c = Component.empty();
        c.append(Component.literal("Prefix "));
        c.append(Component.literal("+100").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        c.append(Component.literal(" suffix"));
        StyledText st = StyledText.of(c);
        List<StyledSegment> segs = st.tspSegments();
        assertEq(3, segs.size(), "three segments");
        assertEq("Prefix ", segs.get(0).text(), "prefix text");
        assertTrue(segs.get(0).isPlain(), "prefix plain");
        assertEq("{0}", segs.get(1).text(), "numeric placeholder");
        assertTrue(!segs.get(1).isPlain(), "styled segment");
        assertEq(" suffix", segs.get(2).text(), "suffix text");
    }

    private static void testOf_RestoreNumbers() {
        System.out.print("  restoreNumbers ... ");
        MutableComponent c = Component.empty();
        c.append(Component.literal("Damage: "));
        c.append(Component.literal("+250").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        c.append(Component.literal(" ("));
        c.append(Component.literal("+100%").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0x55FF55))));
        c.append(Component.literal(")"));
        StyledText st = StyledText.of(c);
        assertEq(2, st.numericValues().size(), "two numeric values");
        assertEq("+250", st.numericValues().get(0), "first value");
        assertEq("+100%", st.numericValues().get(1), "second value");

        String restored = st.restoreNumbers("Damage: {0} ({1})");
        assertEq("Damage: +250 (+100%)", restored, "restored numbers");
    }

    private static void testOf_RenderSn_Basic() {
        System.out.print("  renderSn basic ... ");
        MutableComponent c = Component.empty();
        c.append(Component.literal("Red").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        StyledText st = StyledText.of(c);
        Component rendered = st.renderSn("<s0>Red</s0>", false);
        String legacy = StyleCodec.toLegacyString(rendered);
        assertTrue(legacy.contains("Red"), "rendered text");
    }

    private static void testOf_RenderSn_Paragraph() {
        System.out.print("  renderSn paragraph ... ");
        MutableComponent c = Component.empty();
        c.append(Component.literal("Line 1\nLine 2").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        StyledText st = StyledText.of(c);
        Component rendered = st.renderSn(st.snTemplate(), true);
        String legacy = StyleCodec.toLegacyString(rendered);
        assertTrue(!legacy.contains("\n"), "paragraph flattened");
    }

    private static void testOf_RenderTsp_Basic() {
        System.out.print("  renderTsp basic ... ");
        MutableComponent c = Component.empty();
        c.append(Component.literal("Red").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))));
        StyledText st = StyledText.of(c);
        Component rendered = st.renderTsp(st.tspSegments(), false);
        String legacy = StyleCodec.toLegacyString(rendered);
        assertTrue(legacy.contains("Red"), "rendered text");
    }

    private static void testOf_ToMcStyle_Valid() {
        System.out.print("  toMcStyle valid ... ");
        tsp.Style tspStyle = tsp.Style.of("#FF5555");
        Style mcStyle = StyledText.toMcStyle(tspStyle);
        assertTrue(mcStyle.getColor() != null, "has color");
        assertEq(0xFF5555, mcStyle.getColor().getValue(), "color value");
    }

    private static void testOf_ToMcStyle_Empty() {
        System.out.print("  toMcStyle empty/null ... ");
        assertEq(Style.EMPTY, StyledText.toMcStyle(null), "null -> EMPTY");
        assertEq(Style.EMPTY, StyledText.toMcStyle(tsp.Style.EMPTY), "EMPTY -> EMPTY");
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
