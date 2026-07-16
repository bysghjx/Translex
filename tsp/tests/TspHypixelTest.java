package tsp.tests;

import tsp.*;

import java.util.ArrayList;
import java.util.List;

/**
 * TSP stress test using real Hypixel SkyBlock tooltip data (Mammoth pet).
 * Validates the protocol against the exact scenario that broke the old {@code <sN>} system.
 *
 * <p>Run with: {@code java tsp.tests.TspHypixelTest}</p>
 */
public final class TspHypixelTest {

    private static int passed = 0;
    private static int failed = 0;

    // ---- Real Hypixel colors (from mammoth_request.json) ----
    private static final Style GRAY  = Style.of("#AAAAAA");
    private static final Style GOLD  = Style.of("#FFAA00");
    private static final Style GREEN = Style.of("#55FF55");
    private static final Style AQUA  = Style.of("#55FFFF");
    private static final Style WHITE = Style.of("#FFFFFF");

    public static void main(String[] args) {
        System.out.println("=== TSP Hypixel Real-Data Stress Tests ===\n");

        testMammothSkillParagraph();
        testMammothFullLore();
        testAiReordersContentAcrossLines();
        testAiSplitsTokenContent();        // AI splits "56%" into two tokens
        testAiCreatesFakeToken();          // AI hallucinates [[ on its own
        testStyleCollision();              // Same color used in different contexts
        testManySegmentsSparseStyles();    // 20+ segments, only 3 colors
        testEmptySegmentsBetweenTokens();  // Empty plain text between tokens
        testRegistryIsolation();           // Two encoders don't interfere

        System.out.println("\n---");
        System.out.println("Passed: " + passed + "  Failed: " + failed);
        if (failed > 0) {
            System.out.println("SOME TESTS FAILED!");
            System.exit(1);
        }
        System.out.println("All Hypixel tests passed.");
    }

    // ================================================================
    // 1. Mammoth single paragraph: Wooly Coat (line 4-6 merged)
    //    Old system: 3 lines × 3 styles = 9 global IDs after merge
    //    TSP: 3 unique colors = 3 IDs regardless of how many lines
    // ================================================================
    private static void testMammothSkillParagraph() {
        System.out.print("  Mammoth Skill Paragraph (3 lines merged) ... ");

        // Simulate merged paragraph: 3 consecutive lore lines joined with \n
        // Line 4: "Gain a " (gray) + "56%" (green) + " chance for mobs to not" (gray)
        // Line 5: "inflict " (gray) + "❄ Cold " (aqua) + " when damaging you in" (gray)
        // Line 6: "the " (gray) + "Glacite Mineshafts" (aqua) + "." (gray)
        List<StyledSegment> paragraph = List.of(
                StyledSegment.styled("Gain a ", GRAY),
                StyledSegment.styled("56%", GREEN),
                StyledSegment.styled(" chance for mobs to not", GRAY),
                StyledSegment.plain("\n"),
                StyledSegment.styled("inflict ", GRAY),
                StyledSegment.styled("❄ Cold ", AQUA),
                StyledSegment.styled(" when damaging you in", GRAY),
                StyledSegment.plain("\n"),
                StyledSegment.styled("the ", GRAY),
                StyledSegment.styled("Glacite Mineshafts", AQUA),
                StyledSegment.styled(".", GRAY)
        );

        TspRegistry reg = new TspRegistry();
        TspEncoder encoder = new TspEncoder(reg);
        String encoded = encoder.encode(paragraph);

        // ── Verify: only 3 style IDs, not 9 ──
        assertEq(3, reg.size(), "registry size: only 3 unique colors (GRAY, GREEN, AQUA)");
        // IDs: first-appearance order → GRAY=0, GREEN=1, AQUA=2
        assertTrue(encoded.contains("[[0||Gain a ]]"), "GRAY=0");
        assertTrue(encoded.contains("[[1||56%]]"), "GREEN=1");
        assertTrue(encoded.contains("[[2||❄ Cold ]]"), "AQUA=2");

        // GRAY reused many times — all should be ID 0
        int grayId0Count = countOccurrences(encoded, "[[0||");
        assertTrue(grayId0Count >= 5, "GRAY reused 5+ times: got " + grayId0Count
                + " (dedup working across lines)");

        // ── Round-trip: decode must match ──
        TspDecoder decoder = new TspDecoder(reg);
        List<StyledSegment> decoded = decoder.decodeString(encoded);
        assertEq(paragraph.size(), decoded.size(), "round-trip segment count");

        for (int i = 0; i < paragraph.size(); i++) {
            assertEq(paragraph.get(i).style(), decoded.get(i).style(),
                    "round-trip style[" + i + "]");
            assertEq(paragraph.get(i).text(), decoded.get(i).text(),
                    "round-trip text[" + i + "]");
        }

        pass();
    }

    // ================================================================
    // 2. Full Mammoth lore: 23 lines, mix of paragraphs + single lines
    // ================================================================
    private static void testMammothFullLore() {
        System.out.print("  Mammoth Full Lore (23 lines, encode all) ... ");

        TspRegistry reg = new TspRegistry();
        TspEncoder encoder = new TspEncoder(reg);

        // Encode each line independently (simulating real pipeline per-line encoding)
        List<String> encodedLines = new ArrayList<>();

        // Line 0: "[Lvl 56] " (gray) + "Mammoth" (gold) — item name, single line
        encodedLines.add(encodeLine(reg, encoder,
                seg(GRAY, "[Lvl 56] "), seg(GOLD, "Mammoth")));

        // Line 1: "Combat Pet" (gray) — single line
        encodedLines.add(encodeLine(reg, encoder,
                seg(GRAY, "Combat Pet")));

        // Line 2: "Cold Resistance: " (gray) + "{0}" (white) — stat line, single
        encodedLines.add(encodeLine(reg, encoder,
                seg(GRAY, "Cold Resistance: "), seg(WHITE, "{0}")));

        // Line 3: "Wooly Coat" (gold) — skill name, single
        encodedLines.add(encodeLine(reg, encoder,
                seg(GOLD, "Wooly Coat")));

        // Lines 4-6: paragraph (already tested above, encoded as one merged line)
        encodedLines.add(encodeLine(reg, encoder,
                seg(GRAY, "Gain a "), seg(GREEN, "56% "), seg(GRAY, "chance for mobs to not"),
                seg(GRAY, "inflict "), seg(AQUA, "❄ Cold "), seg(GRAY, "when damaging you in"),
                seg(GRAY, "the "), seg(AQUA, "Glacite Mineshafts"), seg(GRAY, ".")));

        // Line 7: "Tusk Luck" (gold) — skill name, single
        encodedLines.add(encodeLine(reg, encoder,
                seg(GOLD, "Tusk Luck")));

        // Lines 8-11: paragraph
        encodedLines.add(encodeLine(reg, encoder,
                seg(GRAY, "Gain "), seg(GREEN, "+0.28 Magic Find "), seg(GRAY, "for every"),
                seg(GRAY, "100 "), seg(AQUA, "Mining Fortune"), seg(GRAY, ", doubled in the"),
                seg(GRAY, "Glacite Tunnels "), seg(GRAY, "and "), seg(AQUA, "Glacite"),
                seg(GRAY, "Mineshafts"), seg(GRAY, ".")));

        // Line 12: "Corpse Crusher" (gold) — skill name
        encodedLines.add(encodeLine(reg, encoder,
                seg(GOLD, "Corpse Crusher")));

        // Lines 13-15: paragraph
        encodedLines.add(encodeLine(reg, encoder,
                seg(GRAY, "Gain "), seg(GREEN, "+16.8 Mining Fortune "), seg(GRAY, "for each"),
                seg(GRAY, "Frozen Corpse "), seg(GRAY, "looted in your"),
                seg(GRAY, "current "), seg(AQUA, "Glacite Mineshaft"), seg(GRAY, ".")));

        // Line 16: "Held Item: " (gray) + "Mining Exp Boost" (gold) — stat-like
        encodedLines.add(encodeLine(reg, encoder,
                seg(GRAY, "Held Item: "), seg(GOLD, "Mining Exp Boost")));

        // Line 17: paragraph (single sentence)
        encodedLines.add(encodeLine(reg, encoder,
                seg(GRAY, "Gives "), seg(GREEN, "+40% "), seg(GRAY, "pet exp for Mining.")));

        // Line 18: "Progress to Level 57: " (gray) + "{0}" (white)
        encodedLines.add(encodeLine(reg, encoder,
                seg(GRAY, "Progress to Level 57: "), seg(WHITE, "{0}")));

        // Lines 19-21: interaction hint lines, each plain gray
        encodedLines.add(encodeLine(reg, encoder, seg(GRAY, "Left-click to summon!")));
        encodedLines.add(encodeLine(reg, encoder, seg(GRAY, "Shift Left-click to toggle as favorite!")));
        encodedLines.add(encodeLine(reg, encoder, seg(GRAY, "Right-click to convert to an item!")));

        // Line 22: "skyblock:PET" (gray)
        encodedLines.add(encodeLine(reg, encoder, seg(GRAY, "skyblock:PET")));

        // ── Verify ──
        assertEq(5, reg.size(), "only 5 unique styles total: GRAY, GOLD, GREEN, AQUA, WHITE");
        assertTrue(reg.getStyle(0).equals(GRAY), "ID 0 = GRAY (first encountered)");

        // Every encoded line must decode correctly
        TspDecoder decoder = new TspDecoder(reg);
        for (String enc : encodedLines) {
            List<StyledSegment> decoded = decoder.decodeString(enc);
            // Re-encode with a fresh registry to check determinism
            TspRegistry reg2 = new TspRegistry();
            TspEncoder enc2 = new TspEncoder(reg2);
            String roundTripped = enc2.encode(decoded);
            // The round-tripped text should decode to the same segments
            List<StyledSegment> reDecoded = decoder.decodeString(roundTripped);
            assertEq(decoded.size(), reDecoded.size(), "line: " + enc.substring(0, Math.min(40, enc.length())));
        }

        pass();
    }

    // ================================================================
    // 3. ★ THE KEY TEST: AI reorders content across lines ★
    //    Old <sN> system: style by position → color was WRONG
    //    TSP: style by content → color stays CORRECT
    // ================================================================
    private static void testAiReordersContentAcrossLines() {
        System.out.print("  AI Reorders Content Across Lines ... ");

        TspRegistry reg = new TspRegistry();
        reg.register(GRAY);
        reg.register(GREEN);
        reg.register(AQUA);

        // Original paragraph encoding:
        // "Gain a [[1||56%]] chance for mobs to not
        //  inflict [[2||❄ Cold]] when damaging you in
        //  the [[2||Glacite Mineshafts]]."
        //
        // AI translates to Chinese and moves "❄ Cold" to different position:
        // "有 [[1||56%]] 的概率在[[2||Glacite Mineshafts]]中
        //  受到[[2||❄ Cold]]伤害时不被施加。"

        // Simulated Chinese translation (tokens may be in different order)
        String aiOutput =
            "有 [[1||56%]] 的概率在[[2||Glacite Mineshafts]]中受到[[2||❄ Cold]]伤害时不被施加。";

        TspDecoder decoder = new TspDecoder(reg);
        List<StyledSegment> result = decoder.decodeString(aiOutput);

        // Extract all styled segments
        record Colored(String text, Style style) {}
        List<Colored> colored = new ArrayList<>();
        for (StyledSegment seg : result) {
            if (!seg.isPlain()) {
                colored.add(new Colored(seg.text(), seg.style()));
            }
        }

        assertEq(3, colored.size(), "3 styled tokens");
        // "56%" → GREEN (ID 1) — CORRECT, style followed content
        assertEq(GREEN, colored.get(0).style(), "56% → GREEN ✅");
        assertEq("56%", colored.get(0).text(), "56% text");
        // "Glacite Mineshafts" → AQUA (ID 2) — CORRECT
        assertEq(AQUA, colored.get(1).style(), "Glacite Mineshafts → AQUA ✅");
        // "❄ Cold" → AQUA (ID 2) — CORRECT, even though moved to different position!
        assertEq(AQUA, colored.get(2).style(), "❄ Cold → AQUA ✅ (even though moved!)");

        // ── Compare with old <sN> system ──
        // Old system: ID = position-based, s1 binding to line4 position1=GREEN,
        // s3 binding to line5 position1=AQUA, ...
        // If AI moved "❄ Cold" from s4 to s2 position → GREEN was applied to "❄ Cold" = WRONG ❌
        // TSP: [[2||❄ Cold]] uses ID 2 = AQUA regardless of position → CORRECT ✅

        pass();
    }

    // ================================================================
    // 4. AI maliciously splits token content (should NOT happen per spec,
    //    but parser should handle gracefully)
    // ================================================================
    private static void testAiSplitsTokenContent() {
        System.out.print("  AI Splits Token Content ... ");

        TspRegistry reg = new TspRegistry();
        reg.register(GREEN);

        // Original: [[0||56%]]
        // AI "translated" it badly:  [[0||56]]  %  (split the token)
        // Actually, let's test: AI outputs [[0||56]]% (token is valid but incomplete)
        String badAi = "[[0||56]]% 的概率";

        TspDecoder decoder = new TspDecoder(reg);
        List<StyledSegment> result = decoder.decodeString(badAi);

        // Parser correctly parses [[0||56]] as a valid token
        // "% 的概率" should be plain text
        assertTrue(result.size() >= 2, "at least 2 segments");
        // "56" should be GREEN
        assertEq(GREEN, result.get(0).style(), "56 → GREEN");
        assertEq("56", result.get(0).text(), "token text is 56");

        pass();
    }

    // ================================================================
    // 5. AI hallucinates fake [[ tokens
    // ================================================================
    private static void testAiCreatesFakeToken() {
        System.out.print("  AI Hallucinates Fake [[ Token ... ");

        TspRegistry reg = new TspRegistry();
        reg.register(GRAY);

        // AI puts a fake [[ in the output
        String ai = "概率是 [[not a real token]] 这样";

        TspParser parser = new TspParser();
        TspParser.ParseResult result = parser.parse(ai);

        assertTrue(result.hasErrors(), "fake token detected as error");
        // Should not crash, malformed text becomes plain text
        TspDecoder decoder = new TspDecoder(reg);
        List<StyledSegment> decoded = decoder.decode(result);
        // All should be plain (the fake token is malformed → TspText)
        for (StyledSegment seg : decoded) {
            assertTrue(seg.style().isEmpty() || seg.style().equals(GRAY),
                    "no hallucinated colors applied to fake tokens");
        }

        pass();
    }

    // ================================================================
    // 6. Style collision: same hex used intentionally for different concepts
    //    (e.g., GRAY for both stat labels AND ability descriptions)
    //    This is CORRECT behavior — TSP treats identical styles as same.
    // ================================================================
    private static void testStyleCollision() {
        System.out.print("  Style Collision (GRAY reused for stat + description) ... ");

        List<StyledSegment> input = List.of(
                seg(GRAY, "Damage: "),       // stat label — gray
                seg(WHITE, "150"),           // stat value — white
                seg(GRAY, "Gain a "),        // description — gray (same color!)
                seg(GREEN, "56%"),           // value — green
                seg(GRAY, " chance.")        // description — gray
        );

        TspRegistry reg = new TspRegistry();
        TspEncoder encoder = new TspEncoder(reg);
        String encoded = encoder.encode(input);

        // GRAY should be ID 0, and used for ALL gray segments
        assertEq(3, reg.size(), "3 styles: GRAY, WHITE, GREEN");
        int grayCount = countOccurrences(encoded, "[[0||");
        assertEq(3, grayCount, "GRAY (ID 0) used 3 times — stat label AND description");

        // Round-trip
        TspDecoder decoder = new TspDecoder(reg);
        List<StyledSegment> decoded = decoder.decodeString(encoded);
        for (int i = 0; i < input.size(); i++) {
            assertEq(input.get(i).style(), decoded.get(i).style(), "style[" + i + "]");
        }

        pass();
    }

    // ================================================================
    // 7. Many text segments but only a few distinct styles
    // ================================================================
    private static void testManySegmentsSparseStyles() {
        System.out.print("  Many Segments, Sparse Styles (25 segments, 3 colors) ... ");

        Style[] palette = {GRAY, GREEN, AQUA};
        List<StyledSegment> input = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String word = "word" + i;
            if (i % 7 == 0) {
                input.add(seg(palette[i % 3], word));
            } else if (i % 5 == 0) {
                input.add(seg(palette[(i + 1) % 3], word));
            } else {
                input.add(seg(palette[(i + 2) % 3], word));
            }
        }

        TspRegistry reg = new TspRegistry();
        TspEncoder encoder = new TspEncoder(reg);
        String encoded = encoder.encode(input);

        assertEq(3, reg.size(), "still only 3 styles dedup'd");
        assertTrue(encoded.length() > 0, "encoded non-empty");

        // Every token ID must be 0, 1, or 2
        TspParser parser = new TspParser();
        TspParser.ParseResult parsed = parser.parse(encoded);
        for (TspElement e : parsed.elements()) {
            if (e instanceof TspToken t) {
                assertTrue(t.id() >= 0 && t.id() <= 2, "token ID " + t.id() + " in [0,2]");
            }
        }

        pass();
    }

    // ================================================================
    // 8. Empty plain text segments between tokens
    // ================================================================
    private static void testEmptySegmentsBetweenTokens() {
        System.out.print("  Empty Segments Between Tokens ... ");

        List<StyledSegment> input = List.of(
                seg(GRAY, "A"),
                StyledSegment.plain(""),   // empty plain
                seg(GREEN, "B"),
                StyledSegment.plain(""),   // empty plain
                seg(AQUA, "C")
        );

        TspRegistry reg = new TspRegistry();
        TspEncoder encoder = new TspEncoder(reg);
        String encoded = encoder.encode(input);

        // Styled segments produce tokens, empty plains should not add garbage
        // Count tokens: should be exactly 3 (for A, B, C), no malformed extras
        TspParser checkParser = new TspParser();
        TspParser.ParseResult checkResult = checkParser.parse(encoded);
        assertEq(3, checkResult.tokens().size(), "exactly 3 valid tokens, no garbage from empty plains");
        assertTrue(!checkResult.hasErrors(), "no parse errors from empty segments");

        TspDecoder decoder = new TspDecoder(reg);
        List<StyledSegment> decoded = decoder.decodeString(encoded);

        // Should decode to same styled segments (empty plains collapse to nothing)
        assertTrue(decoded.size() >= 3, "at least 3 segments");
        // The styled ones must be correct
        assertEq(GRAY, decoded.get(0).style(), "A style");
        assertEq(GREEN, decoded.get(1).style(), "B style");
        assertEq(AQUA, decoded.get(2).style(), "C style");

        pass();
    }

    // ================================================================
    // 9. Two independent encoders must not share state
    // ================================================================
    private static void testRegistryIsolation() {
        System.out.print("  Registry Isolation ... ");

        TspRegistry reg1 = new TspRegistry();
        TspEncoder enc1 = new TspEncoder(reg1);
        enc1.encode(List.of(seg(GRAY, "hello"), seg(GREEN, "world")));
        assertEq(2, reg1.size(), "reg1 has 2 styles");

        TspRegistry reg2 = new TspRegistry();
        TspEncoder enc2 = new TspEncoder(reg2);
        // Same styles but via a different registry — should start from ID 0
        String out2 = enc2.encode(List.of(seg(GRAY, "hello")));
        assertEq(1, reg2.size(), "reg2 has 1 style (independent)");
        assertTrue(out2.contains("[[0||hello]]"), "reg2 starts IDs from 0: " + out2);

        pass();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static StyledSegment seg(Style s, String text) {
        return StyledSegment.styled(text, s);
    }

    private static String encodeLine(TspRegistry reg, TspEncoder enc, StyledSegment... segments) {
        return enc.encode(List.of(segments));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static void assertEq(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label + ": condition is false");
        }
    }

    private static void pass() {
        passed++;
        System.out.println("PASS");
    }

    private static void fail(String msg) {
        failed++;
        System.out.println("FAIL — " + msg);
    }
}
