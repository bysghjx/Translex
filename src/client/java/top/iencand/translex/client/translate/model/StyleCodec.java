package top.iencand.translex.client.translate.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.web.ConsoleBroadcaster;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 {@link Component} 对象中提取 Minecraft {@link Style} 元数据，并在 AI 翻译后重新应用，
 * 确保颜色、格式和交互事件在通过纯文本 LLM 的往返过程中得以保留。
 *
 * <h3>缓存感知工作流</h3>
 * <pre>{@code
 * // 首次翻译 — 提取 + 序列化以供缓存
 * ExtractionResult r = StyleCodec.extract(originalText);
 * String cacheValue = r.toCacheJson();   // {"markedText":"<s0>…</s0>","snapshots":{…}}
 * // … AI 翻译 r.markedText() …
 * // 存储 cacheValue（而非仅翻译文本）
 *
 * // 缓存命中 — 反序列化 + 重新应用
 * ExtractionResult r = ExtractionResult.fromCacheJson(cachedJson);
 * Component result = StyleCodec.reapply(translatedText, r.snapshots());
 * }</pre>
 *
 * <h3>标签格式</h3>
 * AI 看到的是 {@code <sN>…</sN>} 标签。提示词应指导模型精确保留这些标签
 *（相同的 ID、相同的顺序、标签内无多余空格）。
 *
 * <h3>缓存往返中保留的内容</h3>
 * <ul>
 *   <li>✅ 颜色（RGB 保留）</li>
 *   <li>✅ 粗体 / 斜体 / 下划线 / 删除线 / 混淆</li>
 *   <li>❌ 自定义字体（Minecraft 资源引用，稳定性不保证）</li>
 *   <li>❌ ClickEvent / HoverEvent（可能包含 lambda 或上下文相关数据）</li>
 * </ul>
 */
public final class StyleCodec {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final Logger LOGGER = LoggerFactory.getLogger("Translex/StyleCodec");
    /** 样式回退诊断节流：避免 tooltip 每帧调用时灌爆 SSE 把网页卡死。 */
    private static volatile long lastFallbackLogMs = 0L;
    private static final long FALLBACK_LOG_THROTTLE_MS = 2000L;

    private StyleCodec() {}

    // ================================================================
    // 调试：完整样式颜色转储
    // ================================================================

    /**
     * 将一条物品 tooltip 行的原始样式信息格式化为多行可读字符串，
     * 用于调试多行属性行的颜色错位问题。
     *
     * @param lineIndex  行号（0 = 物品名）
     * @param styleMap   从 {@link #extract(Component)} 得到的样式表
     * @param taggedText 带 {@code <sN>} 标签的文本（提取结果）
     * @return 人类可读的样式信息字符串
     */
    public static String formatLineStyle(int lineIndex, Map<Integer, Style> styleMap, String taggedText) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  Line %2d: ", lineIndex));

        // 解析 taggedText 中的每个 <sN> 段
        Matcher m = STYLE_TAG.matcher(taggedText);
        boolean first = true;
        while (m.find()) {
            int id = Integer.parseInt(m.group(1));
            String content = m.group(2);
            Style style = styleMap.get(id);
            if (!first) sb.append("\n           ");
            first = false;
            sb.append(String.format("s%d=%-20s → \"%s\"",
                    id, formatStyle(style), content));
        }
        return sb.toString();
    }

    /** 将提取后的样式和模板序列化为完整的调试字符串（JSON 格式，便于搜索）。 */
    public static String dumpExtraction(String label, Map<Integer, Style> styleMap, String taggedText) {
        StringBuilder sb = new StringBuilder();
        sb.append("[StyleDump] ").append(label).append(" | ");
        for (Map.Entry<Integer, Style> e : styleMap.entrySet()) {
            if (e.getKey() > 0) sb.append(", ");
            sb.append("s").append(e.getKey()).append("=").append(formatStyle(e.getValue()));
        }
        sb.append(" | raw=").append(taggedText);
        return sb.toString();
    }

    /** 格式化单个 Style 为 "#RRGGBB|B|I|U|S|O..." 的紧凑形式。 */
    private static String formatStyle(Style style) {
        if (style == null || style.isEmpty()) return "EMPTY";
        StringBuilder sb = new StringBuilder();
        if (style.getColor() != null) {
            sb.append(String.format("#%06X", style.getColor().getValue()));
        } else {
            sb.append("NOCOLOR");
        }
        if (style.isBold())          sb.append("|B");
        if (style.isItalic())        sb.append("|I");
        if (style.isUnderlined())    sb.append("|U");
        if (style.isStrikethrough()) sb.append("|S");
        if (style.isObfuscated())    sb.append("|O");
        return sb.toString();
    }

    // ---- Tag markers ---------------------------------------------------

    /** 匹配 {@code <sN>...</sN>}，其中 N 是一位或多位数字。 */
    private static final Pattern STYLE_TAG = Pattern.compile(
            "<s(\\d+)>(.*?)</s\\1>", Pattern.DOTALL);

    /** 匹配任何打开或关闭的样式标签。 */
    private static final Pattern ANY_STYLE_TAG = Pattern.compile("</?s\\d+>");

    // ================================================================
    // 公开 API：基于标签的往返
    // ================================================================

    /**
     * 遍历 {@link Component} 树，将每个带样式的段替换为编号标签，
     * 例如 {@code <s0>segment</s0>}。无样式的段保持为纯文本。
     */
    public static ExtractionResult extract(Component text) {
        StringBuilder out = new StringBuilder();
        Map<Integer, Style> styleMap = new HashMap<>();
        Map<Integer, StyleSnapshot> snapshots = new HashMap<>();
        AtomicInteger counter = new AtomicInteger(0);

        StringBuilder pending = new StringBuilder();
        Style[] pendingStyle = { null };
        Integer[] pendingId = { null };

        text.visit((style, string) -> {
            if (string.isEmpty()) return Optional.empty();

            if (style.isEmpty()) {
                // Hypixel quirk: §-codes embedded in TEXT content (not in Style).
                // e.g. {"text":"§9Gemstone Fuel Tank"} → Style.EMPTY, text has literal §9.
                // Parse as legacy string to recover proper colors, then process sub-segments.
                if (string.indexOf('§') >= 0) {
                    flushPending(pending, pendingStyle, pendingId, out, styleMap, snapshots, counter);
                    extractLegacyInline(string, out, styleMap, snapshots, counter,
                            pending, pendingStyle, pendingId);
                } else {
                    flushPending(pending, pendingStyle, pendingId, out, styleMap, snapshots, counter);
                    out.append(string);
                }
            } else {
                if (pendingStyle[0] != null && !pendingStyle[0].equals(style)) {
                    flushPending(pending, pendingStyle, pendingId, out, styleMap, snapshots, counter);
                }
                if (pendingStyle[0] == null) {
                    pendingStyle[0] = style;
                    pendingId[0] = counter.getAndIncrement();
                }
                pending.append(string);
            }
            return Optional.empty();
        }, Style.EMPTY);

        flushPending(pending, pendingStyle, pendingId, out, styleMap, snapshots, counter);

        return new ExtractionResult(
                out.toString(),
                Collections.unmodifiableMap(styleMap),
                Collections.unmodifiableMap(snapshots));
    }

    private static void flushPending(
            StringBuilder pending, Style[] pendingStyle, Integer[] pendingId,
            StringBuilder out, Map<Integer, Style> styleMap,
            Map<Integer, StyleSnapshot> snapshots, AtomicInteger counter) {
        if (pending.length() == 0 || pendingStyle[0] == null) return;

        styleMap.put(pendingId[0], pendingStyle[0]);
        snapshots.put(pendingId[0], StyleSnapshot.from(pendingStyle[0]));
        out.append("<s").append(pendingId[0]).append(">");
        out.append(pending);
        out.append("</s").append(pendingId[0]).append(">");

        pending.setLength(0);
        pendingStyle[0] = null;
        pendingId[0] = null;
    }

    /**
     * Handle text segments that contain embedded §-codes (Hypixel quirk:
     * the § character is in the TEXT string, not in the Style object).
     * Parses as legacy text and recursively processes styled sub-segments.
     */
    private static void extractLegacyInline(
            String legacyText,
            StringBuilder out,
            Map<Integer, Style> styleMap,
            Map<Integer, StyleSnapshot> snapshots,
            AtomicInteger counter,
            StringBuilder pending, Style[] pendingStyle, Integer[] pendingId) {
        Component parsed = fromLegacyString(legacyText);
        parsed.visit((subStyle, subString) -> {
            if (subString.isEmpty()) return Optional.empty();
            if (subStyle.isEmpty()) {
                flushPending(pending, pendingStyle, pendingId, out, styleMap, snapshots, counter);
                out.append(subString);
            } else {
                if (pendingStyle[0] != null && !pendingStyle[0].equals(subStyle)) {
                    flushPending(pending, pendingStyle, pendingId, out, styleMap, snapshots, counter);
                }
                if (pendingStyle[0] == null) {
                    pendingStyle[0] = subStyle;
                    pendingId[0] = counter.getAndIncrement();
                }
                pending.append(subString);
            }
            return Optional.empty();
        }, Style.EMPTY);
    }

    /** 从完整的样式映射表重新应用样式（内存中，同一会话）。 */
    public static Component reapply(String translated, Map<Integer, Style> styleMap) {
        return reapplyInternal(translated, styleMap);
    }

    /** 从可序列化的快照重新应用样式（缓存命中，跨会话）。 */
    public static Component reapplyFromSnapshots(String translated, Map<Integer, StyleSnapshot> snapshots) {
        Map<Integer, Style> map = new HashMap<>();
        for (var e : snapshots.entrySet()) {
            map.put(e.getKey(), e.getValue().toStyle());
        }
        return reapplyInternal(translated, map);
    }

    private static Component reapplyInternal(String translated, Map<Integer, Style> styleMap) {
        if (translated == null || translated.isEmpty()) {
            return Component.empty();
        }

        MutableComponent result = Component.empty();
        Matcher m = STYLE_TAG.matcher(translated);
        int lastEnd = 0;
        int bareSegments = 0;       // 标签外的裸文本片段数（会渲染成无样式=白色）
        int missingTagIds = 0;      // 引用了 styleMap 里不存在的标签 id 的次数

        while (m.find()) {
            if (m.start() > lastEnd) {
                String bare = translated.substring(lastEnd, m.start());
                if (!bare.isBlank()) bareSegments++;
                result.append(Component.literal(bare));
            }

            int id = Integer.parseInt(m.group(1));
            String content = m.group(2);
            if (!styleMap.containsKey(id)) missingTagIds++;
            Style style = styleMap.getOrDefault(id, Style.EMPTY);
            if (style == Style.EMPTY && !styleMap.isEmpty()) {
                // 译文标签 id 不在实时 styleMap 中（AI 拆分/重组了样式段）。
                // 用 ID 距离最近的已有样式回退：AI 创建的 &lt;s4&gt; 通常应该跟 &lt;s3&gt; 同色，
                // 比取任意第一个样式更合理。
                int nearestId = -1;
                int bestDist = Integer.MAX_VALUE;
                for (int validId : styleMap.keySet()) {
                    int dist = Math.abs(validId - id);
                    if (dist < bestDist) {
                        bestDist = dist;
                        nearestId = validId;
                    }
                }
                if (nearestId >= 0) {
                    style = styleMap.get(nearestId);
                }
            }
            result.append(Component.literal(content).setStyle(style));

            lastEnd = m.end();
        }

        if (lastEnd < translated.length()) {
            String bare = translated.substring(lastEnd);
            if (!bare.isBlank()) bareSegments++;
            result.append(Component.literal(bare));
        }

        // ⑤ 物品 lore "部分变白" 诊断：标签外裸文本或缺失标签 id 都会导致该片段无样式（白色）。
        // 注意：本方法被 tooltip Mixin 每帧调用，必须节流 + 走日志文件（而非每帧 SSE 广播），
        // 否则会瞬间灌爆 SSE 把 Web 控制台页面卡死。
        if ((bareSegments > 0 || missingTagIds > 0) && ModConfig.get().debug) {
            long now = System.currentTimeMillis();
            if (now - lastFallbackLogMs >= FALLBACK_LOG_THROTTLE_MS) {
                lastFallbackLogMs = now;
                String msg = "[StyleCodec] 样式回退(白色) — bareSegments=" + bareSegments
                        + ", missingTagIds=" + missingTagIds
                        + ", styleMapIds=" + styleMap.keySet()
                        + ", text=" + translated;
                LOGGER.info(msg);                 // 写日志文件，便于事后排查
                ConsoleBroadcaster.broadcast("DEBUG", msg);  // 节流后才广播一条，不再每帧刷
            }
        }

        return result;
    }

    /** 从字符串中去除所有 {@code <sN>} 标签。用于缓存键生成。 */
    public static String stripTags(String taggedText) {
        if (taggedText == null) return "";
        return ANY_STYLE_TAG.matcher(taggedText).replaceAll("");
    }

    // ================================================================
    // 旧版 §-码辅助方法（独立于标签往返）
    // ================================================================

    /** 将旧版 § 格式的字符串转换为 Minecraft {@link Component}。 */
    public static Component fromLegacyString(String text) {
        if (text == null || text.isEmpty()) return Component.empty();

        MutableComponent result = Component.empty();
        MutableComponent current = Component.literal("");
        Style currentStyle = Style.EMPTY;
        int i = 0;

        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                if (!current.getString().isEmpty()) {
                    result.append(current.setStyle(currentStyle));
                    current = Component.literal("");
                }
                char fc = Character.toLowerCase(text.charAt(i + 1));
                ChatFormatting f = ChatFormatting.getByCode(fc);
                if (f != null) {
                    if (f.isColor() || f == ChatFormatting.RESET) {
                        currentStyle = Style.EMPTY.applyFormat(f);
                    } else {
                        currentStyle = currentStyle.applyFormat(f);
                    }
                }
                i += 2;
            } else {
                current.append(String.valueOf(c));
                i++;
            }
        }

        if (!current.getString().isEmpty()) {
            result.append(current.setStyle(currentStyle));
        }
        return result;
    }

    /** 将 {@link Component} 树转换回旧版 § 格式的字符串。 */
    public static String toLegacyString(Component text) {
        if (text == null) return "";

        StringBuilder sb = new StringBuilder();
        text.visit((style, string) -> {
            if (!string.isEmpty()) {
                sb.append(styleToSectionCodes(style));
                sb.append(string);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return sb.toString();
    }

    private static String styleToSectionCodes(Style style) {
        if (style.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        if (style.getColor() != null) {
            for (ChatFormatting f : ChatFormatting.values()) {
                if (f.isColor() && f.getColor() != null
                        && f.getColor().equals(style.getColor().getValue())) {
                    sb.append('§').append(f.getChar());
                    break;
                }
            }
        }
        if (style.isBold())          sb.append("§l");
        if (style.isItalic())        sb.append("§o");
        if (style.isUnderlined())    sb.append("§n");
        if (style.isStrikethrough()) sb.append("§m");
        if (style.isObfuscated())    sb.append("§k");
        return sb.toString();
    }

    // ================================================================
    // 可序列化的样式快照
    // ================================================================

    /**
     * Minecraft {@link Style} 的 JSON 可序列化子集。
     * 捕获 §-码能表达的所有内容（颜色 + 格式标志）。
     * 字体、点击事件和悬停事件被有意排除，
     * 因为它们无法可靠地序列化。
     */
    public static final class StyleSnapshot {
        @SerializedName("c") private final String colorHex;
        @SerializedName("b") private final boolean bold;
        @SerializedName("i") private final boolean italic;
        @SerializedName("u") private final boolean underlined;
        @SerializedName("s") private final boolean strikethrough;
        @SerializedName("o") private final boolean obfuscated;

        private StyleSnapshot(String colorHex, boolean bold, boolean italic,
                              boolean underlined, boolean strikethrough, boolean obfuscated) {
            this.colorHex = colorHex;
            this.bold = bold;
            this.italic = italic;
            this.underlined = underlined;
            this.strikethrough = strikethrough;
            this.obfuscated = obfuscated;
        }

        /** 从 Minecraft Style 构建快照。 */
        static StyleSnapshot from(Style style) {
            if (style == null || style.isEmpty()) return EMPTY;
            return new StyleSnapshot(
                    style.getColor() != null ? String.format("#%06X", style.getColor().getValue()) : null,
                    style.isBold(),
                    style.isItalic(),
                    style.isUnderlined(),
                    style.isStrikethrough(),
                    style.isObfuscated());
        }

        /** 从此快照重建 Minecraft Style。 */
        Style toStyle() {
            if (this == EMPTY) return Style.EMPTY;
            Style s = Style.EMPTY
                    .withBold(bold)
                    .withItalic(italic)
                    .withUnderlined(underlined)
                    .withStrikethrough(strikethrough)
                    .withObfuscated(obfuscated);
            if (colorHex != null && colorHex.length() == 7) {
                try {
                    int rgb = Integer.parseInt(colorHex.substring(1), 16);
                    s = s.withColor(TextColor.fromRgb(rgb));
                } catch (NumberFormatException ignored) {}
            }
            return s;
        }

        private static final StyleSnapshot EMPTY = new StyleSnapshot(null, false, false, false, false, false);
    }

    // ================================================================
    // 结果类型（缓存感知）
    // ================================================================

    /**
     * {@link #extract(Component)} 的结果。
     *
     * @param markedText 包含 {@code <sN>…</sN>} 标签的文本，可直接发给 AI
     * @param styleMap   实时 Style 对象（同一会话使用，不可序列化）
     * @param snapshots  可序列化的样式快照（适合缓存）
     */
    public record ExtractionResult(
            String markedText,
            Map<Integer, Style> styleMap,
            Map<Integer, StyleSnapshot> snapshots
    ) {
        /**
         * 序列化为适合缓存的 JSON 字符串。
         * 存储标记文本加上可序列化的样式快照 —
         * 缓存命中后重新应用样式所需的一切。
         */
        public String toCacheJson() {
            return GSON.toJson(new CacheEntry(markedText, snapshots));
        }

        /**
         * 从缓存条目重建 ExtractionResult。
         * {@code styleMap} 由快照填充 — 适用于 {@link StyleCodec#reapply}。
         * 不会恢复不可序列化的属性（字体、事件）。
         */
        public static ExtractionResult fromCacheJson(String json) {
            if (json == null || json.isBlank()) {
                return new ExtractionResult("", Map.of(), Map.of());
            }
            CacheEntry entry = GSON.fromJson(json, CacheEntry.class);
            if (entry == null || entry.markedText == null) {
                return new ExtractionResult("", Map.of(), Map.of());
            }
            Map<Integer, StyleSnapshot> snapshots = entry.snapshots != null
                    ? entry.snapshots : Map.of();
            Map<Integer, Style> styleMap = new HashMap<>();
            for (var e : snapshots.entrySet()) {
                styleMap.put(e.getKey(), e.getValue().toStyle());
            }
            return new ExtractionResult(
                    entry.markedText,
                    Collections.unmodifiableMap(styleMap),
                    Collections.unmodifiableMap(snapshots));
        }
    }

    /** JSON 序列化的内部 POJO。 */
    private static final class CacheEntry {
        @SerializedName("v") String markedText;       // "value" — 标记文本
        @SerializedName("s") Map<Integer, StyleSnapshot> snapshots;  // "styles"

        CacheEntry() {}
        CacheEntry(String markedText, Map<Integer, StyleSnapshot> snapshots) {
            this.markedText = markedText;
            this.snapshots = snapshots;
        }
    }
}
