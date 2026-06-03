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
import top.iencand.translex.client.translate.TranslexTooltipContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Secondary tooltip replacement for non-item tooltips (REI etc.).
 * Checks the shared guard and uses {@link OrderedTextTooltipComponentAccessor}
 * to replace text in-place while preserving original line styles.
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

        if (TranslexTooltipContext.consumeScreenHandled()) return;

        String mode = ModConfig.get().outputMode;
        if ("chat".equals(mode)) return;

        ItemStack stack = ClientStateManager.getLastHoveredItem();
        if (stack == null || stack.isEmpty()) return;

        List<String> replacement = TranslexTooltipContext.lookupReplacement(stack, mode);
        if (replacement == null || replacement.isEmpty()) return;

        // Collect OrderedTextTooltipComponent accessors
        record Slot(int index, OrderedTextTooltipComponentAccessor accessor, Text originalText) {}
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
            if (components.get(i) instanceof OrderedTextTooltipComponent ordered) {
                OrderedTextTooltipComponentAccessor acc =
                        (OrderedTextTooltipComponentAccessor) ordered;
                // Convert OrderedText → Text to extract the original Style
                Text originalText = orderedTextToText(acc.getText());
                slots.add(new Slot(i, acc, originalText));
            }
        }
        if (slots.isEmpty()) return;

        PROCESSING.set(true);
        try {
            // Slot 0 = item name (keep original), slots 1..N = lore (replace 1:1)
            for (int si = 1; si < slots.size() && si < replacement.size(); si++) {
                Slot slot = slots.get(si);
                Style originalStyle = slot.originalText().getStyle();
                slot.accessor().setText(
                        Text.literal(replacement.get(si)).setStyle(originalStyle).asOrderedText()
                );
            }
        } catch (Exception e) {
            LOGGER.error("Failed to translate DrawContext tooltip components", e);
        } finally {
            PROCESSING.set(false);
        }
    }

    /** Convert OrderedText back to Text, preserving style structure. */
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
