package top.iencand.translex.client.translate.model;

import net.minecraft.network.chat.Component;

/**
 * 翻译格式协议抽象：{@code Component <-> 模板字符串} 的编码/解码。
 *
 * <p>两套实现并存，Pipeline 持有一个实例按配置选择，<b>零 if/else</b>：
 * <ul>
 *   <li>{@link SnFormat} -- legacy {@code <sN>} 位置 ID（现有 LineTemplate 包装）</li>
 *   <li>{@link TspFormat} -- {@code [[ID||TEXT]]} 颜色 dedup（新协议）</li>
 * </ul>
 *
 * <p>未来扩展（TSP-v2 / Compact）只需新增实现，Pipeline 不动。后期移除 sN
 * 只删 SnFormat。</p>
 *
 * <p>数字保护 {@code {0}} 占位符在 encode 时生成（防 AI 改数值），decode 时
 * 从 original 提取原始数字填回。占位符逻辑各实现自理。</p>
 */
public interface TranslationFormat {

    /** 协议 id，用于缓存 {@code format} 字段："SN" / "TSP"。 */
    String id();

    /**
     * 编码结果：模板字符串 + registryHash。
     *
     * @param template   发给 AI 的模板（含 {@code {0}} 占位符），sN 为 {@code <sN>...</sN>}，
     *                   TSP 为 {@code [[ID||TEXT]]}
     * @param registryHash registry 指纹（TSP 用于缓存校验；sN 为 null）
     */
    record Encoded(String template, String registryHash) {}

    /**
     * Component -> 模板字符串（编码）。
     *
     * @param component 原文（单行或段落合并后的 Component）
     * @return Encoded（template + registryHash）
     */
    Encoded encode(Component component);

    /**
     * 译文模板 + 原文 -> 带 styles 的 Component（解码）。
     *
     * @param template    译文模板（AI 返回或缓存命中）
     * @param original    原文 Component（用于重建 registry/styleMap + 提取数字 vals）
     * @param isParagraph 段落模式：sN 用 {@code buildParagraphComponent}（\n->空格），
     *                    单行用 {@code buildText}；TSP 段落 \n->空格 喂 Font.split
     * @param registryHash 缓存的 registryHash（TSP 校验颜色结构是否变化；sN 忽略；
     *                    null 表示不校验）
     * @return 带 styles 的 Component，或 {@code null}（registryHash 不匹配 -> cache miss）
     */
    Component decode(String template, Component original, boolean isParagraph, String registryHash);

    /**
     * 去格式标签，保留纯文本 + {@code {0}} 占位符（用于缓存键）。
     * sN 去 {@code <sN>}，TSP 去 {@code [[ID||]]}，结果一致（同原文 -> 同键）。
     * 切换协议后同原文缓存键相同，value 按 format 区分解码。
     */
    String stripFormatTags(String template);

    /** 按 id 选 format 实例（"TSP" -> TspFormat，否则 SnFormat）。 */
    static TranslationFormat forId(String id) {
        return "TSP".equalsIgnoreCase(id) ? new TspFormat() : new SnFormat();
    }
}
