package top.iencand.translex.client.mixin;

import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问器 Mixin，提供对 {@link ClientTextTooltip#text} 字段的读写访问。
 * 在 DrawContextTooltipMixin 中用于读取和替换悬浮提示框中的文本内容，
 * 同时保留原始行的样式信息。
 *
 * <p>26.x 重构：{@code OrderedTextTooltipComponent} 已更名为 {@link ClientTextTooltip}，
 * 其内部字段 {@code text} 类型由 {@code OrderedText} 变为 {@link FormattedCharSequence}。
 */
@Mixin(ClientTextTooltip.class)
public interface OrderedTextTooltipComponentAccessor {

    @Accessor("text")
    FormattedCharSequence getText();

    @Accessor("text")
    @Mutable
    void setText(FormattedCharSequence text);
}
