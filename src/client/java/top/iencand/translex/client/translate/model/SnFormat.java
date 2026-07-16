package top.iencand.translex.client.translate.model;

import net.minecraft.network.chat.Component;

/**
 * Legacy {@code <sN>} 格式（位置 ID），包装现有 {@link LineTemplate}。
 *
 * <p>encode = {@code LineTemplate.fromText(component).getTemplate()}，
 * decode = {@code buildText}（单行）/ {@code buildParagraphComponent}（段落）。
 * 无 registryHash（位置 ID 不需要颜色结构校验）。</p>
 *
 * <p>这是 Translex 现有逻辑的零改动包装，作为 TSP 的 fallback 保留。</p>
 */
public final class SnFormat implements TranslationFormat {

    @Override
    public String id() { return "SN"; }

    @Override
    public Encoded encode(Component component) {
        return new Encoded(LineTemplate.fromText(component).getTemplate(), null);
    }

    @Override
    public Component decode(String template, Component original, boolean isParagraph, String registryHash) {
        // sN 忽略 registryHash（位置 ID，无颜色结构校验）
        LineTemplate tmpl = LineTemplate.fromText(original);
        return isParagraph ? tmpl.buildParagraphComponent(template) : tmpl.buildText(template);
    }

    @Override
    public String stripFormatTags(String template) {
        return StyleCodec.stripTags(template);
    }
}
