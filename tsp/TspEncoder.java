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

    /** 编码策略：决定哪些 segment token 化、哪些裸文本。 */
    public enum Policy {
        /** Full TSP：所有非 plain segment 都 token（默认色也保护）。 */
        FULL,
        /** auto-default：默认色裸文本，其余 token（旧策略，已验证有吸文本问题）。 */
        AUTO_DEFAULT,
        /** Hybrid：只保护高风险内容（非默认色 + 数字 + 符号 + placeholder）。 */
        HYBRID
    }

    private final TspRegistry registry;
    private final Style defaultStyle;       // AUTO_DEFAULT 用
    private final Policy policy;            // HYBRID 用（defaultStyle 自动从 segments 算）
    private final boolean withChecksum;     // v1.1: 生成 [[ID:HASH||TEXT]]

    /** Full TSP + 可选 checksum（推荐 v1.1 用）。 */
    public TspEncoder(TspRegistry registry, boolean withChecksum) {
        this(registry, null, Policy.FULL, withChecksum);
    }

    /** No default style, no checksum (v1.0)。 */
    public TspEncoder(TspRegistry registry) { this(registry, null, Policy.FULL, false); }

    /** Explicit default style, no checksum (AUTO_DEFAULT)。 */
    public TspEncoder(TspRegistry registry, Style defaultStyle) {
        this(registry, defaultStyle, Policy.AUTO_DEFAULT, false);
    }

    /** 指定策略（FULL / HYBRID）+ checksum。 */
    public TspEncoder(TspRegistry registry, Policy policy, boolean withChecksum) {
        this(registry, null, policy, withChecksum);
    }

    /** Full constructor。 */
    private TspEncoder(TspRegistry registry, Style defaultStyle, Policy policy, boolean withChecksum) {
        this.registry = registry;
        this.defaultStyle = defaultStyle;
        this.policy = policy;
        this.withChecksum = withChecksum;
    }

    /** Auto-detect default style, no checksum (AUTO_DEFAULT)。 */
    public static TspEncoder withAutoDefault(TspRegistry registry, List<StyledSegment> segments) {
        return new TspEncoder(registry, detectDefaultStyle(segments), Policy.AUTO_DEFAULT, false);
    }

    /** Hybrid 策略 + checksum：用 Hybrid 默认色判定（plain 计票，按字符加权）。 */
    public static TspEncoder withHybrid(TspRegistry registry, List<StyledSegment> segments, boolean withChecksum) {
        return new TspEncoder(registry, HybridPolicy.detectHybridDefault(segments), Policy.HYBRID, withChecksum);
    }

    public String encode(List<StyledSegment> segments) {
        // HYBRID: 预算 policy（默认色可能为 null -> EMPTY）
        HybridPolicy hybrid = policy == Policy.HYBRID
                ? new HybridPolicy(defaultStyle) : null;

        StringBuilder out = new StringBuilder();
        StringBuilder plainBuffer = new StringBuilder();
        for (StyledSegment seg : segments) {
            boolean protect;
            if (policy == Policy.HYBRID) {
                protect = hybrid.shouldProtect(seg);
            } else if (policy == Policy.AUTO_DEFAULT) {
                // 旧策略：默认色裸文本，其余 token
                protect = !seg.isPlain() && !isDefault(seg.style());
            } else {
                // FULL：所有非 plain 都 token
                protect = !seg.isPlain();
            }

            if (!protect) {
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
        if (segment.isPlain()) return segment.text();
        if (policy == Policy.AUTO_DEFAULT && isDefault(segment.style())) return segment.text();
        int id = registry.register(segment.style());
        return new TspToken(id, segment.text(), withChecksum ? sha4(segment.text()) : null).toWire();
    }

    public Style defaultStyle() { return defaultStyle; }
    public Policy policy() { return policy; }
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
