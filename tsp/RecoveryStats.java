package tsp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates recovery statistics across multiple parse calls, classifies each parse
 * by health tier, and optionally backs up unrecoverable input to disk for analysis.
 *
 * <p><b>Not a static registry</b> - callers own an instance (e.g. one per model,
 * per prompt version, or per translation session). Thread-safe via synchronization.</p>
 *
 * <h3>Health tiers (per parse)</h3>
 * Every {@link TspParser.ParseResult} is classified into exactly one tier:
 * <ul>
 *   <li><b>Perfect</b> - no errors at all (clean decode)</li>
 *   <li><b>Recovery Success</b> - had malformed tokens but V1 recovered them all
 *       (no unrecoverable)</li>
 *   <li><b>Recovery Failed</b> - at least one unrecoverable token (became plain text)</li>
 * </ul>
 *
 * <h3>Health Score</h3>
 * {@code (perfect + recoverySuccess) / total * 100} - the fraction of parses that
 * produced a fully usable result. Use this to compare models / prompts / protocol
 * versions with hard numbers instead of vibes.
 */
public final class RecoveryStats {

    private final String label;  // optional tag for comparison (e.g. "Claude", "v2 prompt")

    // ---- per-parse tier counts ----
    private long parseCount = 0;
    private long perfectParses = 0;
    private long recoveredParses = 0;  // had recovery, all succeeded
    private long failedParses = 0;      // had >= 1 unrecoverable

    // ---- per-token cumulative counts (finer detail) ----
    private long recoveredTotal = 0;
    private long unrecoverableTotal = 0;

    // ---- unrecoverable sample cache (for backup / analysis) ----
    private final List<ParseError> unrecoverableSamples;
    private final int maxSamples;
    private int dumpedCount = 0;  // samples already written by dumpTo (avoid duplicates)

    /** Default collector: no label, up to 1000 in-memory samples. */
    public RecoveryStats() { this("", 1000); }

    /** Collector with a label (model name / prompt version) for comparison reports. */
    public RecoveryStats(String label) { this(label, 1000); }

    /** Collector with a custom in-memory sample cap. */
    public RecoveryStats(int maxSamples) { this("", maxSamples); }

    /** Collector with label + custom sample cap. */
    public RecoveryStats(String label, int maxSamples) {
        this.label = label;
        this.maxSamples = maxSamples;
        this.unrecoverableSamples = new ArrayList<>();
    }

    /**
     * Record a single parse result. Classifies the parse into a health tier and
     * accumulates token-level counts + unrecoverable samples.
     */
    public synchronized void record(TspParser.ParseResult result) {
        parseCount++;
        int unrec = result.unrecoverableCount();
        int rec = result.recoveredCount();
        if (unrec > 0) {
            failedParses++;
        } else if (rec > 0) {
            recoveredParses++;
        } else {
            perfectParses++;
        }
        for (ParseError e : result.parseErrors()) {
            if (e.recovered()) {
                recoveredTotal++;
            } else {
                unrecoverableTotal++;
                if (unrecoverableSamples.size() < maxSamples) {
                    unrecoverableSamples.add(e);
                }
            }
        }
    }

    // ---- label ----
    public String label() { return label; }

    // ---- per-parse tier getters ----
    public synchronized long parseCount() { return parseCount; }
    public synchronized long perfectParses() { return perfectParses; }
    public synchronized long recoveredParses() { return recoveredParses; }
    public synchronized long failedParses() { return failedParses; }

    // ---- per-token cumulative getters ----
    public synchronized long recoveredTotal() { return recoveredTotal; }
    public synchronized long unrecoverableTotal() { return unrecoverableTotal; }

    /** Snapshot of collected unrecoverable samples (defensive copy). */
    public synchronized List<ParseError> unrecoverableSamples() {
        return List.copyOf(unrecoverableSamples);
    }

    /** True if at least one unrecoverable token was ever seen. */
    public synchronized boolean hasUnrecoverable() {
        return unrecoverableTotal > 0;
    }

    /**
     * Health score: percentage of parses that produced a fully usable result
     * (perfect + recovery success). Range 0-100. Empty stats return 100.0.
     */
    public synchronized double healthScore() {
        if (parseCount == 0) return 100.0;
        return 100.0 * (perfectParses + recoveredParses) / parseCount;
    }

    /**
     * Multi-line health report suitable for logging / side-by-side comparison.
     * Example:
     * <pre>
     * [Claude] Requests:           10000
     * Perfect Decode:               9988
     * Recovery Success:               11
     * Recovery Failed:                 1
     * Health Score:             99.99%
     * </pre>
     */
    public synchronized String healthReport() {
        String prefix = label == null || label.isEmpty() ? "" : "[" + label + "] ";
        return String.format(
                "%sRequests:           %d%n" +
                "Perfect Decode:     %d%n" +
                "Recovery Success:   %d%n" +
                "Recovery Failed:    %d%n" +
                "Health Score:       %.2f%%%n",
                prefix, parseCount, perfectParses, recoveredParses, failedParses, healthScore());
    }

    /**
     * Dump unrecoverable samples collected SINCE the last dump to a file as JSON-lines,
     * for later analysis. Each line: {@code {"position":N,"type":"MALFORMED","raw":"..."}}.
     *
     * <p>Append mode, incremental - only newly collected samples since the previous
     * dump are written, so repeated calls do not duplicate entries.</p>
     *
     * @param file target path (created/appended)
     * @return number of new samples written this call
     */
    public synchronized int dumpTo(Path file) throws IOException {
        if (dumpedCount >= unrecoverableSamples.size()) return 0;
        StringBuilder sb = new StringBuilder();
        int start = dumpedCount;
        for (int i = start; i < unrecoverableSamples.size(); i++) {
            ParseError e = unrecoverableSamples.get(i);
            sb.append("{\"position\":").append(e.position())
              .append(",\"type\":\"").append(e.type()).append("\"")
              .append(",\"raw\":").append(jsonStr(e.rawContent()))
              .append("}\n");
        }
        int written = unrecoverableSamples.size() - start;
        dumpedCount = unrecoverableSamples.size();
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return written;
    }

    /** Reset all counters and samples. */
    public synchronized void clear() {
        parseCount = 0;
        perfectParses = 0;
        recoveredParses = 0;
        failedParses = 0;
        recoveredTotal = 0;
        unrecoverableTotal = 0;
        unrecoverableSamples.clear();
        dumpedCount = 0;
    }

    /** One-line summary suitable for logging / metrics. */
    public synchronized String summary() {
        String prefix = label == null || label.isEmpty() ? "" : "[" + label + "] ";
        return String.format(
                "%sparses=%d, perfect=%d, recovered=%d, failed=%d, health=%.2f%%, samples=%d",
                prefix, parseCount, perfectParses, recoveredParses, failedParses,
                healthScore(), unrecoverableSamples.size());
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append("\"").toString();
    }
}
