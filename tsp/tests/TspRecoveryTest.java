package tsp.tests;

import tsp.*;
import java.util.List;

/**
 * Recovery v0/v1 tests - verify parser handles EVERY malformed input without crashing.
 * Run: java tsp.tests.TspRecoveryTest
 */
public final class TspRecoveryTest {

    private static int passed = 0;
    private static int failed = 0;
    private static final Style RED = Style.of("#FF5555");

    public static void main(String[] args) {
        System.out.println("=== TSP Recovery Tests (v0 + v1) ===\n");

        // ===== V0: malformed -> plain text, never crash =====
        testV0("non-numeric ID",          "[[abc||text]]",       false);
        testV0("missing separator",       "[[0]]",               false);
        testV0("single pipe",             "[[0|text]]",          false);
        testV0("unclosed token",          "start [[0||text",     false);
        testV0("nested token",            "[[0||outer [[1||inner]] text]]", false);
        testV0("stray closing",           "hello]] world",       false);
        testV0("empty brackets",          "[[]]",                false);
        testV0("only separator",          "[[||]]",              false);
        testV0("negative ID",             "[[-1||text]]",        false);
        testV0("large ID overflow",       "[[9999999999999||x]]", false);
        testV0("hash instead of ID",      "[[#FF5555||text]]",   false);
        testV0("multiple [[ in one text", "[[0||a]] plain [[bad||token]] [[1||b]]", true);

        // ===== V1: whitespace tolerance =====
        // Design:
        //   strict (V0 default): TEXT preserved verbatim - leading/trailing whitespace is content.
        //   V1 recovery: triggered when idStr has structural whitespace; strips AI-added spaces.
        testV1("strict clean",          "[[0||text]]",          0, "text",  false);
        testV1("strict trailing space", "[[0||text ]]",         0, "text ", false);
        testV1("strict leading space",  "[[0|| text]]",         0, " text", false);
        testV1("space after [[",        "[[ 0||text]]",         0, "text",  true);
        testV1("space around ||",       "[[0 || text]]",        0, "text",  true);
        testV1("all structural ws",     "[[  1  ||  hello ]]",  1, "hello", true);
        testV1("tab whitespace",        "[[\t0\t||\ttext\t]]",  0, "text",  true);
        testV1("newline whitespace",    "[[\n0\n||\ntext\n]]",  0, "text",  true);

        // ===== V1: still unrecoverable =====
        testV1Unrecoverable("space inside ID",   "[[ 1 2 || text]]");
        testV1Unrecoverable("non-numeric ID V1", "[[abc||text]]");
        testV1Unrecoverable("missing separator", "[[0 text]]");

        // ===== Decoder recovery =====
        testDecoderUnknownId();
        testDecoderEmptyToken();

        // ===== Stress: 100 malformed inputs, zero crashes =====
        testStressMalformed();

        System.out.println("\n---");
        System.out.println("Passed: " + passed + "  Failed: " + failed);
        if (failed > 0) { System.out.println("SOME TESTS FAILED!"); System.exit(1); }
        System.out.println("All recovery tests passed.");
    }

    // ================================================================
    // V0: strict mode - malformed -> plain text
    // ================================================================

    private static void testV0(String name, String input, boolean expectSomeValid) {
        System.out.print("  V0 " + name + " ... ");
        TspParser parser = new TspParser(TspRecovery.Level.V0);
        TspParser.ParseResult result;
        try {
            result = parser.parse(input);
        } catch (Exception e) {
            fail("CRASHED: " + e.getMessage());
            return;
        }

        assertTrue(result.elements() != null, "elements not null");

        if (!expectSomeValid) {
            for (TspElement e : result.elements()) {
                if (e instanceof TspToken t) {
                    fail("unexpected valid token in malformed input: " + t);
                    return;
                }
            }
        }

        // Re-parse the output - must not crash
        String allText = elementsToString(result.elements());
        TspParser parser2 = new TspParser(TspRecovery.Level.V0);
        TspParser.ParseResult result2 = parser2.parse(allText);
        assertTrue(result2 != null, "re-parse of output does not crash");

        pass();
    }

    // ================================================================
    // V1: whitespace tolerance (strict path or V1 recovery path)
    // ================================================================

    private static void testV1(String name, String input, int expectedId,
                                String expectedText, boolean expectRecovery) {
        System.out.print("  V1 " + name + " ... ");
        TspParser parser = new TspParser(TspRecovery.Level.V1);
        TspParser.ParseResult result;
        try {
            result = parser.parse(input);
        } catch (Exception e) {
            fail("CRASHED: " + e.getMessage());
            return;
        }

        List<TspToken> tokens = result.tokens();
        assertTrue(tokens.size() == 1, "exactly 1 token, got " + tokens.size()
                + " - elements: " + result.elements());

        TspToken t = tokens.get(0);
        assertEq(expectedId, t.id(), "ID");
        assertEq(expectedText, t.text(), "TEXT");

        boolean hasRecoveryNote = result.errors().stream()
                .anyMatch(e -> e.toLowerCase().contains("recover"));
        assertEq(expectRecovery, hasRecoveryNote, "recovery note expectation");

        pass();
    }

    private static void testV1Unrecoverable(String name, String input) {
        System.out.print("  V1 unrecoverable: " + name + " ... ");
        TspParser parser = new TspParser(TspRecovery.Level.V1);
        TspParser.ParseResult result;
        try {
            result = parser.parse(input);
        } catch (Exception e) {
            fail("CRASHED: " + e.getMessage());
            return;
        }

        assertTrue(result.tokens().isEmpty(), "no valid tokens recovered from '" + input + "'");
        assertTrue(result.hasErrors(), "error recorded");
        pass();
    }

    // ================================================================
    // Decoder recovery
    // ================================================================

    private static void testDecoderUnknownId() {
        System.out.print("  Decoder unknown ID -> EMPTY ... ");
        TspRegistry reg = new TspRegistry();
        reg.register(RED); // ID 0
        TspDecoder decoder = new TspDecoder(reg);
        List<StyledSegment> result = decoder.decodeString("[[99||ghost]]");

        assertEq(1, result.size(), "1 segment");
        assertEq(Style.EMPTY, result.get(0).style(), "unknown ID -> EMPTY");
        assertEq("ghost", result.get(0).text(), "text preserved");
        pass();
    }

    private static void testDecoderEmptyToken() {
        System.out.print("  Decoder empty token text ... ");
        TspRegistry reg = new TspRegistry();
        reg.register(RED);
        TspDecoder decoder = new TspDecoder(reg);
        List<StyledSegment> result = decoder.decodeString("[[0||]]");

        assertEq(1, result.size(), "1 segment");
        assertEq(RED, result.get(0).style(), "style applied");
        assertEq("", result.get(0).text(), "empty text");
        pass();
    }

    // ================================================================
    // Stress: 100 random malformed inputs, zero crashes
    // ================================================================

    private static void testStressMalformed() {
        System.out.print("  Stress: 100 malformed inputs ... ");

        String[] malformedPool = {
            "[[||]]", "[[abc||def]]", "[[0]]", "[[0|text]]",
            "[[0||text", "text]]", "[[", "]]", "[[0||[[1||x]]]]",
            "[[ 0 || text ]]", "[[\n1\n||\nhello\n]]", "[[9,999||x]]",
            "[[0x1||hex]]", "[[-5||neg]]", "[[0||", "||]]",
            "[[9999999999999999||big]]", "[[0||a]] [[||]] [[1||b]]",
            "[[0||\0]]", "[[0||robot]]", "[[    ||    ]]"
        };

        for (int i = 0; i < 100; i++) {
            StringBuilder sb = new StringBuilder();
            int parts = 2 + (i % 4);
            for (int j = 0; j < parts; j++) {
                sb.append(malformedPool[(i + j * 7) % malformedPool.length]);
                sb.append(" plain").append(i).append("_").append(j).append(" ");
            }

            TspParser parserV0 = new TspParser(TspRecovery.Level.V0);
            TspParser parserV1 = new TspParser(TspRecovery.Level.V1);
            try {
                parserV0.parse(sb.toString());
                parserV1.parse(sb.toString());
            } catch (Exception e) {
                fail("Stress input #" + i + " CRASHED: " + e.getMessage() + "\n  input: " + sb);
                return;
            }
        }
        pass();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static String elementsToString(List<TspElement> elements) {
        StringBuilder sb = new StringBuilder();
        for (TspElement e : elements) {
            if (e instanceof TspToken t) sb.append(t.toWire());
            else if (e instanceof TspText t) sb.append(t.text());
        }
        return sb.toString();
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
