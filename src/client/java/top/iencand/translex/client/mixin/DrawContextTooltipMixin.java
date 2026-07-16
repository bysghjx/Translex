package top.iencand.translex.client.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
import top.iencand.translex.client.translate.model.TranslationCacheEntry;
import top.iencand.translex.client.translate.model.TranslationFormat;
import top.iencand.translex.client.translate.model.TranslexTooltipContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 次要工具提示替换 Mixin（针对非原版物品提示框，如 REI 等 Mod）。
 * 检查共享的屏幕处理标志，并使用 {@link OrderedTextTooltipComponentAccessor}
 * 在原地替换文本，同时保留原始行的样式。
 *
 * <p>26.x 重构：{@code DrawContext.drawTooltip(...)} 已被
 * {@link GuiGraphicsExtractor#tooltip(Font, List, int, int, ClientTooltipPositioner, Identifier)}
 * 取代，工具提示组件类型为 {@link ClientTooltipComponent}，文本组件具体类为
 * {@link ClientTextTooltip}（旧 {@code OrderedTextTooltipComponent}）。
 *
 * <p>与 ScreenTooltipMixin 的关系：
 * <ul>
 *   <li>ScreenTooltipMixin 处理 {@code Screen.getTooltipFromItem} 级别的替换</li>
 *   <li>本 Mixin 处理 {@link GuiGraphicsExtractor#tooltip} 时各组件级别的替换</li>
 *   <li>通过 TranslexTooltipContext.consumeScreenHandled() 避免重复处理</li>
 * </ul>
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class DrawContextTooltipMixin {
    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("Translex/DrawContextMixin");

    @Unique
    private static final ThreadLocal<Boolean> PROCESSING = ThreadLocal.withInitial(() -> false);

    @Inject(
            method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;II"
                    + "Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;"
                    + "Lnet/minecraft/resources/Identifier;)V",
            at = @At("HEAD")
    )
    private void translex$translateTooltipComponents(
            Font font,
            List<ClientTooltipComponent> components,
            int x,
            int y,
            ClientTooltipPositioner positioner,
            Identifier texture,
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

        // 收集所有可替换的 ClientTextTooltip
        record Slot(int index, OrderedTextTooltipComponentAccessor accessor, Component originalText) {}
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
            if (components.get(i) instanceof ClientTextTooltip ordered) {
                OrderedTextTooltipComponentAccessor acc =
                        (OrderedTextTooltipComponentAccessor) ordered;
                // 将 FormattedCharSequence 转回 Component 以提取原始 Style
                Component originalText = orderedTextToText(acc.getText());
                slots.add(new Slot(i, acc, originalText));
            }
        }
        if (slots.isEmpty()) return;

        // 收集当前 tooltip 行用于 Slot 门控的 loreHash 校验（与 lookupReplacement 同源）
        List<Component> currentLines = new ArrayList<>(slots.size());
        for (Slot s : slots) currentLines.add(s.originalText());
        List<String> replacement = TranslexTooltipContext.lookupReplacement(stack, mode, currentLines);
        if (replacement == null || replacement.isEmpty()) return;

        PROCESSING.set(true);
        try {
            // Slot 0 = 物品名称（保留原文），slots 1..N = 说明行（按缓存 format decode 1:1 替换）
            for (int si = 1; si < slots.size() && si < replacement.size(); si++) {
                Slot slot = slots.get(si);
                String repl = replacement.get(si);
                TranslationCacheEntry entry = TranslationCacheEntry.parse(repl);
                Component result = slot.originalText();  // 默认原文（parse 失败/decode null 回退）
                if (entry != null) {
                    TranslationFormat fmt = TranslationFormat.forId(entry.format());
                    Component decoded = fmt.decode(entry.template(), slot.originalText(), false, entry.registryHash());
                    if (decoded != null) result = decoded;
                }
                slot.accessor().setText(result.getVisualOrderText());
            }
        } catch (Exception e) {
            LOGGER.error("翻译 GuiGraphicsExtractor 工具提示组件时失败", e);
        } finally {
            PROCESSING.set(false);
        }
    }

    /** 将 {@link FormattedCharSequence} 转换回 {@link Component}，保留样式结构。 */
    @Unique
    private static Component orderedTextToText(FormattedCharSequence ordered) {
        net.minecraft.network.chat.MutableComponent result = Component.empty();
        StringBuilder segment = new StringBuilder();
        Style[] currentStyle = {Style.EMPTY};

        ordered.accept((index, style, codePoint) -> {
            if (!style.equals(currentStyle[0]) && segment.length() > 0) {
                result.append(Component.literal(segment.toString()).setStyle(currentStyle[0]));
                segment.setLength(0);
            }
            currentStyle[0] = style;
            segment.appendCodePoint(codePoint);
            return true;
        });

        if (segment.length() > 0) {
            result.append(Component.literal(segment.toString()).setStyle(currentStyle[0]));
        }
        return result;
    }
}
