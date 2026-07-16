package tsp.tests;

import tsp.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Tests for structured ParseError counting + RecoveryStats accumulator + file backup.
 * Run: java tsp.tests.TspStatsTest
 */
public final class TspStatsTest {

    private static int passed = 0;
    private static int failed = 0;
    private static final Style RED = Style.of("#FF5555");

    public static void main(String[] args) throws Exception {
        System.out.println("=== TSP Stats & Backup Tests ===\n");

        testPerParseCounts();
        testRawContentPreservedFully();
        testRecoveryStatsAccumulation();
        testRecoveryStatsSummary();
        testDumpToBackup() ;
        testBackwardCompatErrorsString();
        testStatsIsolation();
        testEmptyResultNoErrors();

        System.out.println("\n---");
        System.out.println("Passed: " + passed + "  Failed: " + failed);
        if (failed > 0) { System.out.println("SOME TESTS FAILED!"); System.exit(1); }
        System.out.println("All stats tests passed.");
    }

    // ================================================================
    // Per-parse counts
    // ================================================================

    private static void testPerParseCounts() {
        System.out.print("  Per-parse counts ... ");
        // Mix: 1 valid, 1 recovered (V1), 2 unrecoverable
        TspParser parser = new TspParser(TspRecovery.Level.V1);
        String input = "[[0||ok]] [[ 1 || recovered ]] [[abc||bad]] [[||empty]]";

        TspParser.ParseResult r = parser.parse(input);

        // recovered: [[ 1 || recovered ]] -> V1 fixes it
        assertEq(1, r.recoveredCount(), "recovered count");
        // unrecoverable: [[abc||bad]] (non-numeric ID) + [[||empty]] (empty ID)
        assertEq(2, r.unrecoverableCount(), "unrecoverable count");
        assertTrue(r.hasErrors(), "has errors");
        pass();
    }

    // ================================================================
    // rawContent preserved fully (not truncated) - critical for backup
    // ================================================================

    private static void testRawContentPreservedFully() {
        System.out.print("  rawContent preserved fully ... ");
        TspParser parser = new TspParser();

        // Long malformed content - must NOT be truncated (old code truncated to 40 chars)
        String longText = "a".repeat(200);
        String input = "[[abc||" + longText + "]]";

        TspParser.ParseResult r = parser.parse(input);
        assertEq(1, r.unrecoverableCount(), "1 unrecoverable");

        ParseError err = r.unrecoverableErrors().get(0);
        assertEq(ParseError.Type.MALFORMED, err.type(), "type MALFORMED");
        assertTrue(err.recovered() == false, "not recovered");
        // Full raw content preserved: "abc||" (5) + 200 'a's = 205 chars
        assertEq(205, err.rawContent().length(), "rawContent full length");
        assertTrue(err.rawContent().startsWith("abc||"), "rawContent starts with id||");
        assertTrue(err.rawContent().endsWith("a".repeat(50)), "rawContent has full text");
        assertTrue(err.position() >= 0, "position recorded");
        pass();
    }

    // ================================================================
    // RecoveryStats accumulates across parses
    // ================================================================

    private static void testRecoveryStatsAccumulation() {
        System.out.print("  RecoveryStats accumulation ... ");
        TspParser v0 = new TspParser(TspRecovery.Level.V0);
        TspParser v1 = new TspParser(TspRecovery.Level.V1);
        RecoveryStats stats = new RecoveryStats();

        // Parse 1: 1 valid, 1 unrecoverable
        stats.record(v0.parse("[[0||ok]] [[bad||x]]"));
        // Parse 2: 1 recovered, 1 unrecoverable
        stats.record(v1.parse("[[ 0 || ok ]] [[||empty]]"));
        // Parse 3: clean, no errors
        stats.record(v0.parse("[[0||clean]]"));

        assertEq(3, stats.parseCount(), "3 parses recorded");
        assertEq(1, stats.recoveredTotal(), "1 recovered total");
        assertEq(2, stats.unrecoverableTotal(), "2 unrecoverable total");
        assertEq(2, stats.unrecoverableSamples().size(), "2 samples collected");
        assertTrue(stats.hasUnrecoverable(), "has unrecoverable");
        pass();
    }

    // ================================================================
    // Summary string
    // ================================================================

    private static void testRecoveryStatsSummary() {
        System.out.print("  RecoveryStats summary ... ");
        TspParser parser = new TspParser();
        RecoveryStats stats = new RecoveryStats();
        stats.record(parser.parse("[[0||ok]] [[bad||x]] [[||e]]"));

        String s = stats.summary();
        assertTrue(s.contains("parses=1"), "summary has parseCount: " + s);
        assertTrue(s.contains("failed=1"), "summary has failed tier: " + s);
        assertTrue(s.contains("samples=2"), "summary has sample count: " + s);
        assertTrue(s.contains("health="), "summary has health score: " + s);
        System.out.println("\n    summary: " + s);
        pass();
    }

    // ================================================================
    // File backup (dumpTo)
    // ================================================================

    private static void testDumpToBackup() throws Exception {
        System.out.print("  dumpTo file backup ... ");
        TspParser parser = new TspParser();
        RecoveryStats stats = new RecoveryStats();
        stats.record(parser.parse("[[abc||bad1]] [[||bad2]] [[-1||bad3]]"));

        Path tmp = Files.createTempFile("tsp-recovery-backup", ".jsonl");
        try {
            int written = stats.dumpTo(tmp);
            assertEq(3, written, "3 samples written");

            List<String> lines = Files.readAllLines(tmp);
            assertEq(3, lines.size(), "3 lines in file");
            // Each line is valid JSON-lines with raw field
            for (String line : lines) {
                assertTrue(line.startsWith("{"), "line is JSON object");
                assertTrue(line.contains("\"raw\":"), "line has raw field");
                assertTrue(line.contains("\"type\":\"MALFORMED\""), "line has type");
            }
            // Verify content of first sample
            assertTrue(lines.get(0).contains("abc||bad1"), "first sample raw content backed up");

            // Append mode: dump again should add 3 more lines
            stats.record(parser.parse("[[xyz||bad4]]"));
            stats.dumpTo(tmp);
            List<String> lines2 = Files.readAllLines(tmp);
            assertEq(4, lines2.size(), "append mode adds lines");
        } finally {
            Files.deleteIfExists(tmp);
        }
        pass();
    }

    // ================================================================
    // Backward compatibility: errors() still returns List<String>
    // ================================================================

    private static void testBackwardCompatErrorsString() {
        System.out.print("  Backward compat errors() ... ");
        TspParser parser = new TspParser();
        TspParser.ParseResult r = parser.parse("[[abc||bad]]");

        // Old API still works
        List<String> errors = r.errors();
        assertTrue(!errors.isEmpty(), "errors() non-empty");
        assertTrue(r.hasErrors(), "hasErrors true");
        // errors() derived from parseErrors
        assertEq(r.parseErrors().size(), errors.size(), "errors() == parseErrors size");
        pass();
    }

    // ================================================================
    // Stats isolation (per-instance, not static)
    // ================================================================

    private static void testStatsIsolation() {
        System.out.print("  Stats isolation (per-instance) ... ");
        TspParser parser = new TspParser();
        RecoveryStats s1 = new RecoveryStats();
        RecoveryStats s2 = new RecoveryStats();

        s1.record(parser.parse("[[bad||x]]"));
        // s2 untouched
        assertEq(1, s1.unrecoverableTotal(), "s1 has 1");
        assertEq(0, s2.unrecoverableTotal(), "s2 has 0 (isolated)");
        assertEq(0, s2.parseCount(), "s2 parseCount 0");
        pass();
    }

    // ================================================================
    // Empty/clean parse has no errors
    // ================================================================

    private static void testEmptyResultNoErrors() {
        System.out.print("  Clean parse no errors ... ");
        TspParser parser = new TspParser();
        TspParser.ParseResult r = parser.parse("[[0||ok]] plain text [[1||good]]");

        assertEq(0, r.recoveredCount(), "0 recovered");
        assertEq(0, r.unrecoverableCount(), "0 unrecoverable");
        assertTrue(!r.hasErrors(), "no errors");
        assertTrue(r.errors().isEmpty(), "errors() empty");
        pass();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static void assertEq(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void assertEq(long expected, long actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) throw new AssertionError(label + ": condition is false");
    }

    private static void pass() { passed++; System.out.println("PASS"); }
    private static void fail(String msg) { failed++; System.out.println("FAIL - " + msg); }
}
