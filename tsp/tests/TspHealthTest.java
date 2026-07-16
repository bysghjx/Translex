package tsp.tests;

import tsp.*;
import java.util.List;

/**
 * Tests for Health Metrics: per-parse tier classification + health score + report.
 * Run: java tsp.tests.TspHealthTest
 */
public final class TspHealthTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== TSP Health Metrics Tests ===\n");

        testTierClassification();
        testHealthScoreCalculation();
        testHealthReportFormat();
        testModelComparison();
        testEmptyStatsHealth();
        testMixedRecoveryAndFailureInOneParse();

        System.out.println("\n---");
        System.out.println("Passed: " + passed + "  Failed: " + failed);
        if (failed > 0) { System.out.println("SOME TESTS FAILED!"); System.exit(1); }
        System.out.println("All health tests passed.");
    }

    // ================================================================
    // Tier classification: each parse -> perfect | recovered | failed
    // ================================================================

    private static void testTierClassification() {
        System.out.print("  Tier classification ... ");
        TspParser v0 = new TspParser(TspRecovery.Level.V0);
        TspParser v1 = new TspParser(TspRecovery.Level.V1);
        RecoveryStats stats = new RecoveryStats();

        // Perfect: clean parse
        stats.record(v0.parse("[[0||ok]] plain [[1||good]]"));
        // Perfect: another clean
        stats.record(v1.parse("[[0||ok]]"));
        // Recovery success: V1 fixes whitespace, no unrecoverable
        stats.record(v1.parse("[[ 0 || ok ]]"));
        // Recovery failed: non-numeric ID
        stats.record(v0.parse("[[abc||bad]]"));
        // Recovery failed: unclosed
        stats.record(v0.parse("[[0||unclosed"));

        assertEq(5L, stats.parseCount(), "5 parses");
        assertEq(2L, stats.perfectParses(), "2 perfect");
        assertEq(1L, stats.recoveredParses(), "1 recovery success");
        assertEq(2L, stats.failedParses(), "2 failed");
        pass();
    }

    // ================================================================
    // Health score = (perfect + recovered) / total
    // ================================================================

    private static void testHealthScoreCalculation() {
        System.out.print("  Health score calculation ... ");
        TspParser v1 = new TspParser(TspRecovery.Level.V1);
        RecoveryStats stats = new RecoveryStats();

        // Simulate the user's example: 10000 requests, 9988 perfect, 11 recovered, 1 failed
        for (int i = 0; i < 9988; i++) stats.record(v1.parse("[[0||ok]]"));      // perfect
        for (int i = 0; i < 11; i++)   stats.record(v1.parse("[[ 0 || ok ]]"));  // recovered
        for (int i = 0; i < 1; i++)    stats.record(v1.parse("[[abc||bad]]"));   // failed

        assertEq(10000L, stats.parseCount(), "10000 requests");
        assertEq(9988L, stats.perfectParses(), "9988 perfect");
        assertEq(11L, stats.recoveredParses(), "11 recovery success");
        assertEq(1L, stats.failedParses(), "1 failed");
        // Health = (9988 + 11) / 10000 = 99.99%
        assertClose(99.99, stats.healthScore(), 0.001, "health score 99.99%");
        pass();
    }

    // ================================================================
    // Report format matches the user's spec
    // ================================================================

    private static void testHealthReportFormat() {
        System.out.print("  Health report format ... ");
        TspParser v1 = new TspParser(TspRecovery.Level.V1);
        RecoveryStats stats = new RecoveryStats("Claude");

        stats.record(v1.parse("[[0||ok]]"));          // perfect
        stats.record(v1.parse("[[ 0 || ok ]]"));      // recovered
        stats.record(v1.parse("[[abc||bad]]"));       // failed

        String report = stats.healthReport();
        System.out.println("\n" + indent(report));
        assertTrue(report.contains("[Claude]"), "report has label");
        assertTrue(report.contains("Requests:"), "report has Requests");
        assertTrue(report.contains("Perfect Decode:"), "report has Perfect Decode");
        assertTrue(report.contains("Recovery Success:"), "report has Recovery Success");
        assertTrue(report.contains("Recovery Failed:"), "report has Recovery Failed");
        assertTrue(report.contains("Health Score:"), "report has Health Score");
        assertTrue(report.contains("66.67%"), "health score 66.67% in report");
        pass();
    }

    // ================================================================
    // Model comparison: side-by-side stats instances
    // ================================================================

    private static void testModelComparison() {
        System.out.print("  Model comparison ... ");
        TspParser v1 = new TspParser(TspRecovery.Level.V1);
        TspParser v0 = new TspParser(TspRecovery.Level.V0);

        // GPT-5.5: 1 failure out of 5000
        RecoveryStats gpt = new RecoveryStats("GPT-5.5");
        for (int i = 0; i < 4999; i++) gpt.record(v1.parse("[[0||ok]]"));
        gpt.record(v0.parse("[[abc||bad]]"));

        // Claude: 0 failures out of 5000
        RecoveryStats claude = new RecoveryStats("Claude");
        for (int i = 0; i < 5000; i++) claude.record(v1.parse("[[0||ok]]"));

        // Gemini: 5 failures out of 5000
        RecoveryStats gemini = new RecoveryStats("Gemini");
        for (int i = 0; i < 4995; i++) gemini.record(v1.parse("[[0||ok]]"));
        for (int i = 0; i < 5; i++) gemini.record(v0.parse("[[abc||bad]]"));

        System.out.println();
        System.out.println(indent(gpt.summary()));
        System.out.println(indent(claude.summary()));
        System.out.println(indent(gemini.summary()));

        // Claude best, GPT close, Gemini worst
        assertTrue(claude.healthScore() > gpt.healthScore(), "Claude > GPT");
        assertTrue(gpt.healthScore() > gemini.healthScore(), "GPT > Gemini");
        assertClose(100.0, claude.healthScore(), 0.001, "Claude 100%");
        assertClose(99.98, gpt.healthScore(), 0.001, "GPT 99.98%");
        assertClose(99.90, gemini.healthScore(), 0.001, "Gemini 99.90%");
        pass();
    }

    // ================================================================
    // Empty stats: health = 100% (no data = no problem)
    // ================================================================

    private static void testEmptyStatsHealth() {
        System.out.print("  Empty stats health ... ");
        RecoveryStats stats = new RecoveryStats();
        assertEq(0L, stats.parseCount(), "0 parses");
        assertClose(100.0, stats.healthScore(), 0.001, "empty -> 100%");
        assertTrue(!stats.hasUnrecoverable(), "no unrecoverable");
        pass();
    }

    // ================================================================
    // One parse with BOTH recovered and unrecoverable -> counts as failed
    // ================================================================

    private static void testMixedRecoveryAndFailureInOneParse() {
        System.out.print("  Mixed recovery+failure in one parse -> failed ... ");
        TspParser v1 = new TspParser(TspRecovery.Level.V1);
        RecoveryStats stats = new RecoveryStats();

        // One parse: [[ 0 || ok ]] (recovered) + [[abc||bad]] (unrecoverable)
        stats.record(v1.parse("[[ 0 || ok ]] [[abc||bad]]"));

        assertEq(1L, stats.parseCount(), "1 parse");
        assertEq(0L, stats.perfectParses(), "0 perfect");
        assertEq(0L, stats.recoveredParses(), "0 recovery-success (has unrecoverable)");
        assertEq(1L, stats.failedParses(), "1 failed");
        // token-level: 1 recovered + 1 unrecoverable
        assertEq(1L, stats.recoveredTotal(), "1 recovered token");
        assertEq(1L, stats.unrecoverableTotal(), "1 unrecoverable token");
        assertClose(0.0, stats.healthScore(), 0.001, "health 0%");
        pass();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static String indent(String s) {
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n", -1)) sb.append("      ").append(line).append("\n");
        return sb.toString();
    }

    private static void assertEq(long expected, long actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void assertClose(double expected, double actual, double tol, String label) {
        if (Math.abs(expected - actual) > tol) {
            throw new AssertionError(label + ": expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) throw new AssertionError(label + ": condition is false");
    }

    private static void pass() { passed++; System.out.println("PASS"); }
    private static void fail(String msg) { failed++; System.out.println("FAIL - " + msg); }
}
