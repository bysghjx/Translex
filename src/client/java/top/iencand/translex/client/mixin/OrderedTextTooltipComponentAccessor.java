package top.iencand.translex.client.mixin;

import net.minecraft.client.gui.tooltip.OrderedTextTooltipComponent;
import net.minecraft.text.OrderedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for {@link OrderedTextTooltipComponent#text} so the tooltip
 * replacement mixin can read and replace the {@link OrderedText}.
 */
@Mixin(OrderedTextTooltipComponent.class)
public interface OrderedTextTooltipComponentAccessor {

    @Accessor("text")
    OrderedText getText();

    @Accessor("text")
    @Mutable
    void setText(OrderedText text);
}
