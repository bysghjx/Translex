package tsp;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hybrid 编码策略：选择性 token 化。
 *
 * <p>跟 Full TSP（所有非默认色都 token）和 auto-default（默认色裸文本，其余 token）不同，
 * Hybrid 主动判断每个 segment 的"风险等级"：高风险内容强制 token（防 AI 误处理），
 * 低风险默认色描述裸文本（省 token + 减少结构噪声）。</p>
 *
 * <h3>设计动机</h3>
 * auto-default 的问题是：默认色裸文本紧邻高亮 token 时，AI 会把裸文本吸进 token，
 * 导致颜色错位。Hybrid 反向操作 - 不是"默认色裸露其余保护"，而是"只保护高风险边界"，
 * 把该保护的（数字、符号、语义色）都护住，其余大胆裸露，让结构噪声降到最低。</p>
 *
 * <h3>保护规则（命中任一即 token 化）</h3>
 * <ol>
 *   <li><b>非默认色</b> - 任何不是 body 颜色的 styled segment</li>
 *   <li><b>数字</b> - 纯数字段（含 + - % . , 等），AI 极易重组漂移</li>
 *   <li><b>MC 特殊符号</b> - ❄ ♦ ✦ ★ ❤ 等属性图标/rarity 标记</li>
 *   <li><b>Placeholder</b> - {0} {1} 等数字占位符（encode 时由 TspFormat 生成）</li>
 *   <li><b>小段非默认色</b> - 即使无数字也保护（语义高亮，如 Hyperion 名字）</li>
 * </ol>
 *
 * <p>规则 1 实际上已覆盖规则 5（非默认色即保护），规则 5 单列是为了说明意图。
 * 规则 2-4 针对默认色 segment 中混入的高风险内容（如灰色描述行里的数字）。</p>
 *
 * <p>未保护段 -> 裸文本，不进 registry。这样 token 数和 registry 大小同步下降。</p>
 */
public final class HybridPolicy {

    /** 数字段（与 TspFormat.NUMBER 一致）：纯数字 + 常见后缀。 */
    private static final Pattern NUMBER = Pattern.compile(
            "[\\d.,+%kmb\\-s()]*\\d[\\d.,+%kmb\\-s()]*", Pattern.CASE_INSENSITIVE);

    /** {0} {1} 等占位符。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+\\}");

    /** MC/Hypixel 特殊符号：属性图标、rarity、装饰符。 */
    private static final Set<String> SPECIAL_SYMBOLS = Set.of(
            "❄", "♦", "✦", "★", "❤", "✿", "♥", "♣", "♠", "✧", "☀", "☁", "⚔", "⛏"
    );

    /**
     * 默认色白名单：只有这些颜色才当"body 色"裸文本，其他颜色一律保护。
     *
     * <p>Hypixel lore 的主体描述用 GRAY(#AAAAAA) 或 DARK_GRAY(#555555)。
     * 其他颜色（金/紫/蓝/绿等）即使频率高也是语义高亮（附魔名、数值、rarity），
     * 必须保护，不能因为"出现多"就当默认色裸文本。</p>
     *
     * <p>历史教训：旧 {@code detectHybridDefault} 按频率判定，在 Midas 附魔段落
     * 把金色(#FFAA00, 占 55%)误判为默认色，导致 Champion/Bane 等金色附魔名
     * 全部裸文本丢色。改成固定白名单彻底避免。</p>
     */
    private static final Set<String> DEFAULT_COLOR_HEXES = Set.of(
            "#AAAAAA",  // GRAY - lore 主体描述
            "#555555"   // DARK_GRAY - 次要描述
    );

    private final Style defaultStyle;

    public HybridPolicy(Style defaultStyle) {
        this.defaultStyle = defaultStyle != null ? defaultStyle : Style.EMPTY;
    }

    /** 兼容旧 API，defaultStyle 现在只用于 isDefault 判定（白名单内的色）。 */
    public static HybridPolicy withAutoDefault(List<StyledSegment> segments) {
        return new HybridPolicy(detectHybridDefault(segments));
    }

    /**
     * 默认色判定：有白名单色（GRAY/DARK_GRAY）就设默认色。
     *
     * <p>不要求占比阈值：stat 行标签（{@code "Damage: "}）灰色占比低（如 32%），
     * 但也该裸文本省 token。灰色段裸文本染灰色，颜色不丢。</p>
     *
     * <p>非白名单色（金/紫/蓝等）不会误判 - Midas 附魔段无灰色 -> defaultStyle 空
     * -> 全保护，金色附魔名保留。</p>
     */
    public static Style detectHybridDefault(List<StyledSegment> segments) {
        for (StyledSegment seg : segments) {
            if (!seg.isPlain() && isDefaultColor(seg.style())) {
                return seg.style();
            }
        }
        return Style.EMPTY;
    }

    /** 判断颜色是否在默认色白名单内（GRAY / DARK_GRAY）。 */
    private static boolean isDefaultColor(Style style) {
        if (style == null || style.isEmpty() || style.colorHex() == null) return false;
        return DEFAULT_COLOR_HEXES.contains(style.colorHex().toUpperCase());
    }

    public Style defaultStyle() { return defaultStyle; }

    /**
     * 合并相邻同 style 的 segment（Hybrid 独有优化）。
     *
     * <p>StyleCodec.extract 把每个 Component 拆成独立 segment，相邻同色段
     *（如附魔段的 {@code "Champion 10, "} + {@code "Bane of Arthropods 7"} 同金色）
     * 会生成多个 token。Hybrid 在 encode 前合并它们，减少 token 数。</p>
     *
     * <p>规则：相邻且 {@link Style#equals(Object)} 的 segment 合并 text。
     * plain（Style.EMPTY）段也合并（减少段数，不影响 token）。
     * plain 与 styled 不合并（style 不同）。</p>
     *
     * <p>encode/decode 必须用相同合并逻辑，保证 registry 重建一致。</p>
     */
    public static List<StyledSegment> mergeAdjacentSameColor(List<StyledSegment> segs) {
        if (segs == null || segs.size() <= 1) return segs;
        List<StyledSegment> merged = new java.util.ArrayList<>(segs.size());
        StyledSegment current = segs.get(0);
        for (int i = 1; i < segs.size(); i++) {
            StyledSegment next = segs.get(i);
            if (current.style().equals(next.style())) {
                current = new StyledSegment(current.text() + next.text(), current.style());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    /**
     * 判断 segment 是否需要 token 化保护。
     *
     * <p>plain segment（Style.EMPTY）本身无颜色，按内容判断（数字/符号/placeholder）。
     * styled segment：默认色按内容判断，非默认色一律保护。</p>
     */
    public boolean shouldProtect(StyledSegment seg) {
        if (seg == null) return false;
        String text = seg.text();
        if (text == null || text.isEmpty()) return false;

        // 规则 1 + 5：非默认色 -> 保护（无论内容）
        if (!seg.isPlain() && !isDefault(seg.style())) {
            return true;
        }

        // 默认色 / plain segment：按内容判断高风险
        // 规则 4：placeholder
        if (PLACEHOLDER.matcher(text).matches()) return true;
        // 规则 2：数字
        if (NUMBER.matcher(text).matches()) return true;
        // 规则 3：特殊符号（单字符或全是符号）
        if (isSpecialSymbol(text)) return true;

        return false;
    }

    private boolean isDefault(Style style) {
        return defaultStyle != null && defaultStyle.equals(style);
    }

    /** 判断文本是否由特殊符号构成（单个或连续符号串）。 */
    private static boolean isSpecialSymbol(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            if (!SPECIAL_SYMBOLS.contains(ch)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }
}
