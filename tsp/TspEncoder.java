package tsp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts styled text segments into a TSP-encoded string.
 *
 * <p>Rules (from spec §5):
 * <ul>
 *   <li>Plain-text segments pass through unchanged.</li>
 *   <li>Styled segments become {@code [[ID||TEXT]]} tokens — <em>except</em> the
 *       default body style which is emitted as plain text to reduce token noise.</li>
 *   <li>Uses a local {@link TspRegistry} to allocate IDs on demand.</li>
 *   <li>Deterministic: same input always produces the same output.</li>
 * </ul>
 *
 * <h3>Default style</h3>
 * The most frequent style in the input is treated as the "body" style
 * and is NOT wrapped in tokens. Only semantic highlights (values, names,
 * special terms) get tokens — exactly the colors that matter for translation.
 * This also matches what LLMs naturally do: they drop baseline tokens.
 */
public final class TspEncoder {

    private final TspRegistry registry;
    private final Style defaultStyle; // null = every style gets a token

    /** No default style — every styled segment becomes a token. */
    public TspEncoder(TspRegistry registry) {
        this.registry = registry;
        this.defaultStyle = null;
    }

    /** Explicit default style — segments with this style are emitted as plain text. */
    public TspEncoder(TspRegistry registry, Style defaultStyle) {
        this.registry = registry;
        this.defaultStyle = defaultStyle;
    }

    /**
     * Auto-detect the most frequent style from the segments and treat it as the default.
     * This is the recommended constructor for Hypixel tooltips where gray body text
     * dominates and should not be tokenized.
     */
    public static TspEncoder withAutoDefault(TspRegistry registry, List<StyledSegment> segments) {
        return new TspEncoder(registry, detectDefaultStyle(segments));
    }

    /**
     * Encode a list of styled segments into the TSP wire format.
     *
     * @param segments ordered list of text segments with optional styles
     * @return the TSP-encoded string
     */
    public String encode(List<StyledSegment> segments) {
        StringBuilder out = new StringBuilder();
        StringBuilder plainBuffer = new StringBuilder();

        for (StyledSegment seg : segments) {
            if (seg.isPlain() || isDefault(seg.style())) {
                // Plain text or default body style → emit verbatim (no token)
                plainBuffer.append(seg.text());
            } else {
                flushPlain(plainBuffer, out);
                int id = registry.register(seg.style());
                out.append(new TspToken(id, seg.text()).toWire());
            }
        }
        flushPlain(plainBuffer, out);

        return out.toString();
    }

    /**
     * Encode a single styled segment (convenience wrapper).
     */
    public String encodeOne(StyledSegment segment) {
        if (segment.isPlain() || isDefault(segment.style())) {
            return segment.text();
        }
        int id = registry.register(segment.style());
        return new TspToken(id, segment.text()).toWire();
    }

    /** The style that is treated as implicit body text (not tokenized). */
    public Style defaultStyle() {
        return defaultStyle;
    }

    // ---- internal ----

    private boolean isDefault(Style style) {
        return defaultStyle != null && defaultStyle.equals(style);
    }

    private static void flushPlain(StringBuilder buffer, StringBuilder out) {
        if (buffer.length() > 0) {
            out.append(buffer);
            buffer.setLength(0);
        }
    }

    /**
     * Detect the default body style: the one that appears most frequently.
     * In Hypixel tooltips this is typically gray (#AAAAAA) — the canvas color
     * for stat labels, description prose, and hints. Semantic colors (green
     * for values, aqua for special terms, gold for names) appear less often
     * and should be tokenized.
     *
     * @return the most frequent style, or null if empty
     */
    public static Style detectDefaultStyle(List<StyledSegment> segments) {
        Map<Style, Integer> counts = new LinkedHashMap<>();
        for (StyledSegment seg : segments) {
            if (!seg.isPlain()) {
                counts.merge(seg.style(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
