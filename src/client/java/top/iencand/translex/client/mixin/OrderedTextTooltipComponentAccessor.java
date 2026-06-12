package top.iencand.translex.client.mixin;

import net.minecraft.client.gui.tooltip.OrderedTextTooltipComponent;
import net.minecraft.text.OrderedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问器 Mixin，提供对 {@link OrderedTextTooltipComponent#text} 字段的读写访问。
 * 在 DrawContextTooltipMixin 中用于读取和替换悬浮提示框中的文本内容，
 * 同时保留原始行的样式信息。
 */
@Mixin(OrderedTextTooltipComponent.class)
public interface OrderedTextTooltipComponentAccessor {

    @Accessor("text")
    OrderedText getText();

    @Accessor("text")
    @Mutable
    void setText(OrderedText text);
}
