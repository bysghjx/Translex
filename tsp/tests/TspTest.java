package tsp.tests;

import tsp.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone tests for the Translex Style Protocol (TSP) prototype.
 *
 * <p>Run with: {@code java tsp.tests.TspTest}
 * Prints a summary — exits with code 1 on failure.</p>
 */
public final class TspTest {

    private static int passed = 0;
    private static int failed = 0;

    // ---- Shared test data ----

    private static final Style GRAY  = Style.of("#AAAAAA");
    private static final Style GREEN = Style.of("#55FF55");
    private static final Style AQUA  = Style.of("#55FFFF");
    private static final Style GOLD  = Style.of("#FFAA00");

    public static void main(String[] args) {
        System.out.println("=== TSP Draft 1 Test Suite ===\n");

        testRoundTrip();
        testStyleDeduplication();
        testTokenReordering();
        testInvalidToken_NotCrash();
        testInvalidToken_NonNumericId();
        testInvalidToken_NoSeparator();
        testInvalidToken_Unclosed();
        testInvalidToken_Nesting();
        testEmptyInput();
        testPlainText();
        testUnknownStyleId();
        testMultipleTokens();
        testConsecutivePlainText();
        testDeterministicEncoding();
        testEncoderWithEmptyStyle();

        System.out.println("\n---");
        System.out.println("Passed: " + passed + "  Failed: " + failed);
        if (failed > 0) {
            System.out.println("SOME TESTS FAILED!");
            System.exit(1);
        } else {
            System.out.println("All tests passed.");
        }
    }

    // ================================================================
    // Test implementations
    // ================================================================

    /** ✅ Round Trip: Styled Text → Encoder → Decoder → Equivalent Styled Text */
    private static void testRoundTrip() {
        System.out.print("  Round Trip ... ");

        // Build input
        List<StyledSegment> input = List.of(
                StyledSegment.plain("Gain a "),
                StyledSegment.styled("56%", GREEN),
                StyledSegment.plain(" chance to receive "),
                StyledSegment.styled("❄ Cold", AQUA),
                StyledSegment.plain(".")
        );

        // Encode
        TspRegistry encRegistry = new TspRegistry();
        TspEncoder encoder = new TspEncoder(encRegistry);
        String encoded = encoder.encode(input);

        // Decode with same registry
        TspDecoder decoder = new TspDecoder(encRegistry);
        List<StyledSegment> output = decoder.decodeString(encoded);

        // Verify
        assertEq(input.size(), output.size(), "segment count");
        for (int i = 0; i < input.size(); i++) {
            assertEq(input.get(i), output.get(i), "segment[" + i + "]");
        }
        pass();
    }

    /** ✅ Style Deduplication: Repeated styles should reuse IDs */
    private static void testStyleDeduplication() {
        System.out.print("  Style Deduplication ... ");

        List<StyledSegment> input = List.of(
                StyledSegment.styled("hello", GREEN),
                StyledSegment.plain(" "),
                StyledSegment.styled("world", GREEN)   // same style → should reuse ID
        );

        TspRegistry registry = new TspRegistry();
        TspEncoder encoder = new TspEncoder(registry);
        String encoded = encoder.encode(input);

        // Both styled segments should use the same ID (0)
        assertTrue(encoded.contains("[[0||hello]]"), "first token has ID 0");
        assertTrue(encoded.contains("[[0||world]]"), "second token reuses ID 0");
        assertEq(1, registry.size(), "registry should have only 1 entry");

        // Decode and verify
        TspDecoder decoder = new TspDecoder(registry);
        List<StyledSegment> output = decoder.decodeString(encoded);
        assertEq(3, output.size(), "output segment count");
        assertEq(GREEN, output.get(0).style(), "first segment style");
        assertEq(GREEN, output.get(2).style(), "third segment style (same)");

        pass();
    }

    /** ✅ Token Reordering: Simulate LLM moving tokens (style follows content) */
    private static void testTokenReordering() {
        System.out.print("  Token Reordering ... ");

        // Original:  Gain [[1||56%]] chance [[2||❄ Cold]]
        // After LLM reorder (Chinese word order):
        //             有 [[1||56%]] 的概率不施加 [[2||❄ Cold]]

        TspRegistry registry = new TspRegistry();
        registry.register(GREEN);  // ID 0
        registry.register(AQUA);   // ID 1

        // Wait — we want specific IDs. Let's use the encoder first to register them properly.
        // Actually, for reordering test, we simulate LLM output directly.
        // The point is: the LLM moved tokens, but each token's ID stays with its content.

        TspRegistry reg = new TspRegistry();
        reg.register(GREEN);  // ID 0
        reg.register(AQUA);   // ID 1

        // Simulated LLM output (Chinese translation with tokens preserved)
        String llmOutput = "有 [[0||56%]] 的概率不施加 [[1||❄ Cold]]";

        TspDecoder decoder = new TspDecoder(reg);
        List<StyledSegment> output = decoder.decodeString(llmOutput);

        assertEq(4, output.size(), "segment count");

        // "有 " should be plain
        assertTrue(output.get(0).isPlain(), "segment 0 is plain");
        assertEq("有 ", output.get(0).text(), "segment 0 text");

        // "56%" should be GREEN (ID 0)
        assertEq(GREEN, output.get(1).style(), "segment 1 style=GREEN");
        assertEq("56%", output.get(1).text(), "segment 1 text");

        // " 的概率不施加 " should be plain
        assertTrue(output.get(2).isPlain(), "segment 2 is plain");

        // "❄ Cold" should be AQUA (ID 1) — style followed content!
        assertEq(AQUA, output.get(3).style(), "segment 3 style=AQUA (followed content!)");
        assertEq("❄ Cold", output.get(3).text(), "segment 3 text");

        pass();
    }

    /** ✅ Invalid Token: Parser should not crash */
    private static void testInvalidToken_NotCrash() {
        System.out.print("  Invalid Token (not crash) ... ");

        TspParser parser = new TspParser();
        String input = "Hello [[bad||token world";

        TspParser.ParseResult result;
        try {
            result = parser.parse(input);
        } catch (Exception e) {
            fail("parser threw: " + e.getMessage());
            return;
        }

        // Should have errors and continue parsing
        assertTrue(result.hasErrors(), "has errors");
        // The malformed token text should appear as plain text
        String allText = result.elements().stream()
                .filter(e -> e instanceof TspText)
                .map(e -> ((TspText) e).text())
                .reduce("", String::concat);
        assertTrue(allText.contains("Hello"), "contains 'Hello'");
        // The [[bad portion should be treated as plain text
        assertTrue(allText.contains("[["), "contains malformed brackets");

        pass();
    }

    /** ✅ Invalid Token: Non-numeric ID */
    private static void testInvalidToken_NonNumericId() {
        System.out.print("  Invalid Token (non-numeric ID) ... ");

        TspParser parser = new TspParser();
        TspParser.ParseResult result = parser.parse("[[abc||text]]");

        assertTrue(result.hasErrors(), "has errors");
        assertTrue(result.errors().get(0).toLowerCase().contains("malformed")
                        || result.errors().get(0).toLowerCase().contains("id"),
                "error mentions malformed/ID issue");
        // Should be treated as plain text
        assertTrue(result.tokens().isEmpty(), "no valid tokens");

        pass();
    }

    /** ✅ Invalid Token: Missing separator */
    private static void testInvalidToken_NoSeparator() {
        System.out.print("  Invalid Token (no separator) ... ");

        TspParser parser = new TspParser();
        TspParser.ParseResult result = parser.parse("[[0]]");

        assertTrue(result.hasErrors(), "has errors");
        assertTrue(result.tokens().isEmpty(), "no valid tokens (no || separator)");

        pass();
    }

    /** ✅ Invalid Token: Unclosed */
    private static void testInvalidToken_Unclosed() {
        System.out.print("  Invalid Token (unclosed) ... ");

        TspParser parser = new TspParser();
        TspParser.ParseResult result = parser.parse("start [[0||text end");

        assertTrue(result.hasErrors(), "has errors");
        assertTrue(result.tokens().isEmpty(), "no valid tokens");

        pass();
    }

    /** ✅ Invalid Token: Nesting detected */
    private static void testInvalidToken_Nesting() {
        System.out.print("  Invalid Token (nesting) ... ");

        TspParser parser = new TspParser();
        TspParser.ParseResult result = parser.parse("[[0||outer [[1||inner]] text]]");

        assertTrue(result.hasErrors(), "has errors");
        // Nesting is rejected (spec §7: no nested tokens) - no valid token produced
        assertTrue(result.tokens().isEmpty(), "nested token rejected, no valid tokens");

        pass();
    }

    /** ✅ Empty Input */
    private static void testEmptyInput() {
        System.out.print("  Empty Input ... ");

        TspParser parser = new TspParser();
        TspParser.ParseResult result = parser.parse("");
        assertTrue(result.elements().isEmpty(), "empty elements");

        result = parser.parse(null);
        assertTrue(result.elements().isEmpty(), "null input → empty elements");

        pass();
    }

    /** ✅ Plain Text: No tokens generated */
    private static void testPlainText() {
        System.out.print("  Plain Text ... ");

        List<StyledSegment> input = List.of(
                StyledSegment.plain("This is just plain text, no styles at all.")
        );

        TspRegistry registry = new TspRegistry();
        TspEncoder encoder = new TspEncoder(registry);
        String encoded = encoder.encode(input);

        // Should contain no [[ tokens
        assertTrue(!encoded.contains("[[") && !encoded.contains("]]"),
                "plain text has no token brackets");
        assertEq("This is just plain text, no styles at all.", encoded, "encoded = original");
        assertEq(0, registry.size(), "registry is empty");

        pass();
    }

    /** ✅ Unknown Style ID: Fallback to Style.EMPTY */
    private static void testUnknownStyleId() {
        System.out.print("  Unknown Style ID ... ");

        TspRegistry registry = new TspRegistry();
        // Register only ID 0
        registry.register(GRAY);

        // Decode a string with ID 99 (not in registry)
        TspDecoder decoder = new TspDecoder(registry);
        List<StyledSegment> output = decoder.decodeString("Value: [[99||mystery]]");

        assertEq(2, output.size(), "segment count");
        assertEq("Value: ", output.get(0).text(), "plain prefix");

        // Unknown ID → Style.EMPTY (plain text)
        assertTrue(output.get(1).style().isEmpty(), "unknown ID → EMPTY style");
        assertEq("mystery", output.get(1).text(), "text preserved");

        pass();
    }

    /** Multiple valid tokens in sequence */
    private static void testMultipleTokens() {
        System.out.print("  Multiple Tokens ... ");

        TspRegistry registry = new TspRegistry();
        registry.register(GOLD);   // ID 0
        registry.register(GREEN);  // ID 1
        registry.register(AQUA);   // ID 2

        String input = "[[0||Wooly Coat]]\n[[1||+100]] Damage\n[[2||Glacite]]";

        TspDecoder decoder = new TspDecoder(registry);
        List<StyledSegment> output = decoder.decodeString(input);

        assertTrue(output.size() >= 5, "multiple segments");

        // First token → GOLD
        TspToken firstToken = null;
        for (var e : output) {
            // We check by content
        }
        assertEq(GOLD, output.get(0).style(), "Wooly Coat → GOLD");
        assertEq("Wooly Coat", output.get(0).text(), "text");

        pass();
    }

    /** Consecutive plain text segments should merge */
    private static void testConsecutivePlainText() {
        System.out.print("  Consecutive Plain Text ... ");

        List<StyledSegment> input = List.of(
                StyledSegment.plain("Hello "),
                StyledSegment.plain("World"),
                StyledSegment.styled("!!!", GOLD)
        );

        TspRegistry registry = new TspRegistry();
        TspEncoder encoder = new TspEncoder(registry);
        String encoded = encoder.encode(input);

        // "Hello " + "World" should merge into "Hello World"
        assertTrue(encoded.startsWith("Hello World"), "plain text merged: " + encoded);

        pass();
    }

    /** ✅ Determinism: Same input → same output */
    private static void testDeterministicEncoding() {
        System.out.print("  Deterministic Encoding ... ");

        List<StyledSegment> input = List.of(
                StyledSegment.styled("A", GRAY),
                StyledSegment.plain(" "),
                StyledSegment.styled("B", GREEN),
                StyledSegment.plain(" "),
                StyledSegment.styled("C", AQUA)
        );

        TspRegistry reg1 = new TspRegistry();
        TspEncoder enc1 = new TspEncoder(reg1);
        String out1 = enc1.encode(input);

        TspRegistry reg2 = new TspRegistry();
        TspEncoder enc2 = new TspEncoder(reg2);
        String out2 = enc2.encode(input);

        assertEq(out1, out2, "identical outputs");

        // IDs should be in first-appearance order: GRAY→0, GREEN→1, AQUA→2
        assertTrue(out1.contains("[[0||A]]"), "GRAY = ID 0");
        assertTrue(out1.contains("[[1||B]]"), "GREEN = ID 1");
        assertTrue(out1.contains("[[2||C]]"), "AQUA = ID 2");

        pass();
    }

    /** Style.EMPTY segments should not generate tokens */
    private static void testEncoderWithEmptyStyle() {
        System.out.print("  Encoder with Style.EMPTY ... ");

        List<StyledSegment> input = List.of(
                new StyledSegment("plain", Style.EMPTY),
                StyledSegment.styled("colored", GRAY)
        );

        TspRegistry registry = new TspRegistry();
        TspEncoder encoder = new TspEncoder(registry);
        String encoded = encoder.encode(input);

        assertTrue(encoded.startsWith("plain"), "starts with plain text");
        assertTrue(encoded.contains("[[0||colored]]"), "styled segment becomes token");
        assertEq(1, registry.size(), "only one style registered");

        pass();
    }

    // ================================================================
    // Assertion helpers
    // ================================================================

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
