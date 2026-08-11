package top.iencand.translex.client.translate.model;

import net.minecraft.network.chat.Component;

/**
 * Legacy {@code <sN>} 格式（位置 ID），由 {@link StyledText} 提供协议无关的样式数据。
 *
 * <p>encode 使用 {@link StyledText#snTemplate()}，
 * decode 使用 {@link StyledText#renderSn(String, boolean)}。
 * 无 registryHash（位置 ID 不需要颜色结构校验）。</p>
 *
 * <p>这是 Translex 现有逻辑的零改动包装，作为 TSP 的 fallback 保留。</p>
 */
public final class SnFormat implements TranslationFormat {

    @Override
    public String id() { return "SN"; }

    @Override
    public Encoded encode(StyledText text) {
        return new Encoded(text.snTemplate(), null);
    }

    @Override
    public Component decode(String template, StyledText original, boolean isParagraph, String registryHash) {
        // sN ignores registryHash because it uses positional identifiers.
        return original.renderSn(template, isParagraph);
    }

    @Override
    public String stripFormatTags(String template) {
        return StyleCodec.stripTags(template);
    }
}
