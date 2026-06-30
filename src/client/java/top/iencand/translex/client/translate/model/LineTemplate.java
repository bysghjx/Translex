package top.iencand.translex.client.translate.model;

import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 双模式翻译辅助类，用于带样式的逐行物品工具提示。
 *
 * <h3>数字变量保护</h3>
 * 数字段被替换为 {@code {0}} / {@code {1}} / … 标记，防止 AI 幻觉产生错误的数值。
 * 原始数字在 {@link #buildText(String)} 中恢复。
 *
 * <h3>样式保留（委托给 {@link StyleCodec}）</h3>
 * 非数字的样式文本被包裹为 {@code <sN>…</sN>} 标签，这些标签在 AI 往返中保留。
 * 可序列化的 {@link StyleCodec.StyleSnapshot} 嵌入在缓存值中，使样式能跨会话保留。
 *
 * <h3>缓存感知工作流</h3>
 * <pre>{@code
 *   // 首次翻译
 *   LineTemplate tmpl = LineTemplate.fromText(original);
 *   String aiResult = ...;                     // AI 响应
 *   String cacheVal = tmpl.toCacheEntry(aiResult);
 *   cache.put(key, cacheVal);
 *
 *   // 缓存命中（可能跨会话）
 *   LineTemplate tmpl = LineTemplate.fromText(original);
 *   String cacheVal = cache.get(key);
 *   Component styled = tmpl.buildFromCache(cacheVal);
 *   // 数字段使用当前物品的样式；
 *   // 非数字段使用缓存的快照。
 * }</pre>
 */
public class LineTemplate {

    /** 模板标签：{@code <sN>content</sN>}。 */
    private static final Pattern STYLE_TAG = Pattern.compile(
            "<s(\\d+)>(.*?)</s\\1>", Pattern.DOTALL);

    /** 至少一个 ASCII 数字。涵盖 (+30)、-5、2.5s、+250% 等 SkyBlock 常见数值格式。 */
    private static final Pattern NUMBER = Pattern.compile(
            "[\\d,+.%kmb\\-\\s()s]+", Pattern.CASE_INSENSITIVE);

    /** {@code {0}}、{@code {1}}、… 标记。 */
    private static final Pattern MARKER = Pattern.compile("\\{(\\d+)\\}");

    // -------- 实例字段 --------------------------------------------

    private final StyleCodec.ExtractionResult styles; // 实时样式引用
    private final String template;           // "<s0>Damage: </s0><s1>{0}</s1>…" — 发送给 AI 的模板
    private final String[] values;           // 例如 ["110", "+30%"]
    private final int[] valueStyleIds;       // 每个 {i} 对应的 <sN> id

    // -------- 构造方法 -----------------------------------------------

    private LineTemplate(StyleCodec.ExtractionResult styles,
                         String template, String[] values, int[] valueStyleIds) {
        this.styles = styles;
        this.template = template;
        this.values = values;
        this.valueStyleIds = valueStyleIds;
    }

    /**
     * 从 Minecraft {@link Component} 行构建 LineTemplate。
     * <ol>
     *   <li>通过 {@link StyleCodec} 提取样式 → {@code <sN>} 标签</li>
     *   <li>扫描标记文本中的数字段 → 替换为 {@code {i}}</li>
     * </ol>
     */
    public static LineTemplate fromText(Component text) {
        StyleCodec.ExtractionResult r = StyleCodec.extract(text);

        StringBuilder tmpl = new StringBuilder();
        List<String> vals = new ArrayList<>();
        List<Integer> sIds = new ArrayList<>();

        Matcher m = STYLE_TAG.matcher(r.markedText());
        int lastEnd = 0;

        while (m.find()) {
            // Plain text between tags
            tmpl.append(r.markedText(), lastEnd, m.start());

            int id = Integer.parseInt(m.group(1));
            String content = m.group(2);

            if (NUMBER.matcher(content).matches()) {
                // Numeric → protect with {i} marker, keep the <sN> wrapper
                tmpl.append("<s").append(id).append(">");
                tmpl.append("{").append(vals.size()).append("}");
                tmpl.append("</s").append(id).append(">");
                vals.add(content);
                sIds.add(id);
            } else {
                // Non-numeric → pass through unchanged
                tmpl.append(m.group());
            }

            lastEnd = m.end();
        }

        // Trailing plain text
        tmpl.append(r.markedText(), lastEnd, r.markedText().length());

        return new LineTemplate(r, tmpl.toString(),
                vals.toArray(new String[0]),
                sIds.stream().mapToInt(i -> i).toArray());
    }

    // ---- public getters ---------------------------------------------

    /** 发送给 AI 的模板字符串（{@code <sN>} 标签 + {@code {i}} 标记）。 */
    public String getTemplate() { return template; }

    /** 此行是否包含任何数字变量。 */
    public boolean hasVariables() { return values.length > 0; }

    /** 调试用：返回原始样式提取结果。 */
    public StyleCodec.ExtractionResult extractionResult() { return styles; }

    // ---- build translated Component --------------------------------------

    /**
     * 从 AI 返回的模板构建带样式的 {@link Component}。
     *
     * <p>所有样式来自<em>当前</em>提取（实时会话）。
     * 对于缓存命中，请优先使用 {@link #buildFromCache(String)}，
     * 它合并了缓存的非数字快照和当前的数字样式。</p>
     */
    public Component buildText(String translatedTemplate) {
        return buildWithStyleMap(translatedTemplate, buildLiveStyleMap());
    }

    /**
     * 从缓存条目构建带样式的 {@link Component}，合并缓存的非数字快照
     * 和当前物品的数字段样式。
     *
     * @param cacheJson 之前由 {@link #toCacheEntry(String)} 返回的值
     */
    public Component buildFromCache(String cacheJson) {
        if (cacheJson == null || cacheJson.isBlank()) {
            return apply(translatedFallback());
        }

        StyleCodec.ExtractionResult cached;
        try {
            cached = StyleCodec.ExtractionResult.fromCacheJson(cacheJson);
        } catch (Exception e) {
            // Legacy plain-text cache entry — use current styles for everything
            return buildText(cacheJson);
        }

        if (cached.markedText() == null || cached.markedText().isBlank()) {
            return buildText(cacheJson);
        }

        // Merge: non-number styles from cache, number styles from current item
        Set<Integer> numberIdSet = new HashSet<>();
        for (int id : valueStyleIds) numberIdSet.add(id);

        Map<Integer, Style> merged = new HashMap<>();
        Map<Integer, StyleCodec.StyleSnapshot> cachedSnapshots = cached.snapshots();

        for (var entry : styles.styleMap().entrySet()) {
            int id = entry.getKey();
            if (numberIdSet.contains(id)) {
                // 数字段 → 使用当前实时样式
                merged.put(id, entry.getValue());
            } else if (cachedSnapshots.containsKey(id)) {
                // 非数字、有缓存 → 使用缓存的快照
                merged.put(id, cachedSnapshots.get(id).toStyle());
            } else {
                // 回退
                merged.put(id, entry.getValue());
            }
        }

        // Also include any cached style ids not in current extraction
        for (var entry : cachedSnapshots.entrySet()) {
            int id = entry.getKey();
            if (!merged.containsKey(id)) {
                merged.put(id, entry.getValue().toStyle());
            }
        }

        return buildWithStyleMap(cached.markedText(), merged);
    }

    // ---- cache serialization ----------------------------------------

    /**
     * 生成可供缓存使用的 JSON 值。返回的字符串可在后续缓存命中时
     * 传递给 {@link #buildFromCache(String)}。
     */
    public String toCacheEntry(String translatedTemplate) {
        // Use the snapshots from the original extraction — they're already serializable
        return new StyleCodec.ExtractionResult(
                translatedTemplate,
                Map.of(),   // 实时映射表不需要缓存
                styles.snapshots()
        ).toCacheJson();
    }

    // ---- fallback: apply the primary colour to the whole line --------

    /**
     * 快速回退：从原始文本中取第一个颜色，应用到整个翻译字符串。
     * 在没有更好的样式信息时使用。
     */
    public Component apply(String translated) {
        Style first = extractFirstColor();
        if (hasObfuscatedStyle()) {
            return net.minecraft.network.chat.Component.literal("✦ " + translated + " ✦").setStyle(first);
        }
        return net.minecraft.network.chat.Component.literal(translated).setStyle(first);
    }

    // -------- 内部辅助方法 -------------------------------------------

    /** 用当前值填充 {@code {i}} 占位符。 */
    private String fillNumbers(String template) {
        String result = template;
        for (int i = 0; i < values.length; i++) {
            result = result.replace("{" + i + "}", values[i]);
        }
        return result;
    }

    /** 仅从实时提取结果构建样式映射表。 */
    private Map<Integer, Style> buildLiveStyleMap() {
        return new HashMap<>(styles.styleMap());
    }

    /** 核心构建：填充数字，重新应用样式。 */
    private Component buildWithStyleMap(String translatedTemplate, Map<Integer, Style> styleMap) {
        String filled = fillNumbers(translatedTemplate);
        return StyleCodec.reapply(filled, styleMap);
    }

    /** 模板为空时的可翻译内容。 */
    private String translatedFallback() {
        return template.replaceAll("\\{\\d+\\}", "");
    }

    private Style extractFirstColor() {
        for (var entry : styles.styleMap().entrySet()) {
            if (entry.getValue().getColor() != null) {
                return net.minecraft.network.chat.Style.EMPTY.withColor(entry.getValue().getColor());
            }
        }
        return net.minecraft.network.chat.Style.EMPTY;
    }

    /** 检查原始文本是否包含混淆标志（Hypixel 特殊升级标记）。 */
    private boolean hasObfuscatedStyle() {
        for (Style s : styles.styleMap().values()) {
            if (s.isObfuscated()) return true;
        }
        return false;
    }
}
