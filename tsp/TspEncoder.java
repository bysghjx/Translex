package tsp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts styled text segments into a TSP-encoded string.
 *
 * <p>Rules (from spec §5):
 * <ul>
 *   <li>Plain-text segments pass through unchanged.</li>
 *   <li>Styled segments become {@code [[ID||TEXT]]} tokens - <em>except</em> the
 *       default body style which is emitted as plain text to reduce token noise.</li>
 *   <li>Uses a local {@link TspRegistry} to allocate IDs on demand.</li>
 *   <li>Deterministic: same input always produces the same output.</li>
 * </ul>
 *
 * <h3>Default style</h3>
 * The most frequent style in the input is treated as the "body" style
 * and is NOT wrapped in tokens. Only semantic highlights (values, names,
 * special terms) get tokens - exactly the colors that matter for translation.
 * This also matches what LLMs naturally do: they drop baseline tokens.
 *
 * <h3>v1.1 checksum</h3>
 * With {@code withChecksum=true}, tokens carry a content hash: {@code [[ID:HASH||TEXT]]}.
 * Decoder verifies (ID, HASH) to detect AI moving content across color-IDs, and can
 * auto-repair ID via HASH lookup (Level 1/2 recovery).
 */
public final class TspEncoder {

    private final TspRegistry registry;
    private final Style defaultStyle; // null = every style gets a token
    private final boolean withChecksum;  // v1.1: 生成 [[ID:HASH||TEXT]]

    /** No default style, no checksum (v1.0). */
    public TspEncoder(TspRegistry registry) { this(registry, null, false); }

    /** Explicit default style, no checksum. */
    public TspEncoder(TspRegistry registry, Style defaultStyle) { this(registry, defaultStyle, false); }

    /** No default style, optional checksum (v1.1). */
    public TspEncoder(TspRegistry registry, boolean withChecksum) { this(registry, null, withChecksum); }

    /** Full constructor: default style + checksum mode. */
    public TspEncoder(TspRegistry registry, Style defaultStyle, boolean withChecksum) {
        this.registry = registry;
        this.defaultStyle = defaultStyle;
        this.withChecksum = withChecksum;
    }

    /** Auto-detect default style (no checksum). */
    public static TspEncoder withAutoDefault(TspRegistry registry, List<StyledSegment> segments) {
        return new TspEncoder(registry, detectDefaultStyle(segments), false);
    }

    public String encode(List<StyledSegment> segments) {
        StringBuilder out = new StringBuilder();
        StringBuilder plainBuffer = new StringBuilder();
        for (StyledSegment seg : segments) {
            if (seg.isPlain() || isDefault(seg.style())) {
                plainBuffer.append(seg.text());
            } else {
                flushPlain(plainBuffer, out);
                int id = registry.register(seg.style());
                out.append(new TspToken(id, seg.text(), withChecksum ? sha4(seg.text()) : null).toWire());
            }
        }
        flushPlain(plainBuffer, out);
        return out.toString();
    }

    public String encodeOne(StyledSegment segment) {
        if (segment.isPlain() || isDefault(segment.style())) return segment.text();
        int id = registry.register(segment.style());
        return new TspToken(id, segment.text(), withChecksum ? sha4(segment.text()) : null).toWire();
    }

    public Style defaultStyle() { return defaultStyle; }
    public boolean withChecksum() { return withChecksum; }

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

    /** SHA-256 前 4 hex（v1.1 checksum，16-bit，每段 <20 token 碰撞概率 <1%）。 */
    public static String sha4(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return String.format("%04x", ((d[0] & 0xff) << 8 | (d[1] & 0xff)) & 0xffff);
        } catch (Exception e) {
            return "0000";
        }
    }
}
