package top.iencand.translex.client.mixin;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.OrderedTextTooltipComponent;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.gui.tooltip.TooltipPositioner;
import net.minecraft.item.ItemStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.listener.ClientStateManager;
import top.iencand.translex.client.translate.model.LineTemplate;
import top.iencand.translex.client.translate.model.TranslexTooltipContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 次要工具提示替换 Mixin（针对非原版物品提示框，如 REI 等 Mod）。
 * 检查共享的屏幕处理标志，并使用 {@link OrderedTextTooltipComponentAccessor}
 * 在原地替换文本，同时保留原始行的样式。
 *
 * <p>与 ScreenTooltipMixin 的关系：
 * <ul>
 *   <li>ScreenTooltipMixin 处理 {@link Screen#getTooltipFromItem} 级别的替换</li>
 *   <li>本 Mixin 处理 {@link DrawContext#drawTooltip} 时各组件级别的替换</li>
 *   <li>通过 TranslexTooltipContext.consumeScreenHandled() 避免重复处理</li>
 * </ul>
 */
@Mixin(DrawContext.class)
public abstract class DrawContextTooltipMixin {
    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("Translex/DrawContextMixin");

    @Unique
    private static final ThreadLocal<Boolean> PROCESSING = ThreadLocal.withInitial(() -> false);

    @Inject(
            method = "drawTooltip(Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;II"
                    + "Lnet/minecraft/client/gui/tooltip/TooltipPositioner;Lnet/minecraft/util/Identifier;Z)V",
            at = @At("HEAD")
    )
    private void translex$translateTooltipComponents(
            TextRenderer textRenderer,
            List<TooltipComponent> components,
            int x,
            int y,
            TooltipPositioner positioner,
            Identifier texture,
            boolean recalculateWidth,
            CallbackInfo ci
    ) {
        if (PROCESSING.get()) return;
        if (components == null || components.isEmpty()) return;

        // 如果 ScreenTooltipMixin 已经处理过，则跳过
        if (TranslexTooltipContext.consumeScreenHandled()) return;

        // chat 模式不替换工具提示
        String mode = ModConfig.get().outputMode;
        if ("chat".equals(mode)) return;

        ItemStack stack = ClientStateManager.getLastHoveredItem();
        if (stack == null || stack.isEmpty()) return;

        List<String> replacement = TranslexTooltipContext.lookupReplacement(stack, mode);
        if (replacement == null || replacement.isEmpty()) return;

        // 收集所有可替换的 OrderedTextTooltipComponent
        record Slot(int index, OrderedTextTooltipComponentAccessor accessor, Text originalText) {}
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
            if (components.get(i) instanceof OrderedTextTooltipComponent ordered) {
                OrderedTextTooltipComponentAccessor acc =
                        (OrderedTextTooltipComponentAccessor) ordered;
                // 将 OrderedText 转回 Text 以提取原始 Style
                Text originalText = orderedTextToText(acc.getText());
                slots.add(new Slot(i, acc, originalText));
            }
        }
        if (slots.isEmpty()) return;

        PROCESSING.set(true);
        try {
            // Slot 0 = 物品名称（保留原文），slots 1..N = 说明行（1:1 替换）
            for (int si = 1; si < slots.size() && si < replacement.size(); si++) {
                Slot slot = slots.get(si);
                slot.accessor().setText(
                        LineTemplate.fromText(slot.originalText()).buildText(replacement.get(si)).asOrderedText()
                );
            }
        } catch (Exception e) {
            LOGGER.error("翻译 DrawContext 工具提示组件时失败", e);
        } finally {
            PROCESSING.set(false);
        }
    }

    /** 将 {@link OrderedText} 转换回 {@link Text}，保留样式结构。 */
    @Unique
    private static Text orderedTextToText(OrderedText ordered) {
        net.minecraft.text.MutableText result = Text.empty();
        StringBuilder segment = new StringBuilder();
        Style[] currentStyle = {Style.EMPTY};

        ordered.accept((index, style, codePoint) -> {
            if (!style.equals(currentStyle[0]) && segment.length() > 0) {
                result.append(Text.literal(segment.toString()).setStyle(currentStyle[0]));
                segment.setLength(0);
            }
            currentStyle[0] = style;
            segment.appendCodePoint(codePoint);
            return true;
        });

        if (segment.length() > 0) {
            result.append(Text.literal(segment.toString()).setStyle(currentStyle[0]));
        }
        return result;
    }
}
