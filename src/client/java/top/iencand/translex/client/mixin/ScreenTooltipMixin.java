package top.iencand.translex.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.translate.model.LineTemplate;
import top.iencand.translex.client.translate.model.TranslexTooltipContext;

import java.util.List;

/**
 * 主工具提示替换 Mixin。注入到 {@link Screen#getTooltipFromItem} 的 RETURN 点，
 * 直接接收 {@link ItemStack}，并用缓存的翻译结果替换物品说明行，
 * 同时保留每行的原始 {@link net.minecraft.network.chat.Style}。
 *
 * <p>工作流程：
 * <ol>
 *   <li>检查输出模式（chat 模式跳过）</li>
 *   <li>从 TranslexTooltipContext 查找缓存翻译</li>
 *   <li>跳过第 0 行（物品名称），从第 1 行开始替换说明行</li>
 *   <li>标记屏幕已处理，避免 DrawContextTooltipMixin 重复替换</li>
 * </ol>
 */
@Mixin(Screen.class)
public abstract class ScreenTooltipMixin {

    /**
     * 防止递归构建的保护标志（避免 getTooltipFromItem 被重复调用时的死循环）
     */
    @Unique
    private static final ThreadLocal<Boolean> BUILDING = ThreadLocal.withInitial(() -> false);
    @Unique private static volatile long lastDumpMs = 0L;
    @Unique private static final long DUMP_THROTTLE_MS = 2000L;

    @Inject(
            method = "getTooltipFromItem(Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void translex$replaceItemTooltip(
            Minecraft client,
            ItemStack stack,
            CallbackInfoReturnable<List<Component>> cir
    ) {
        if (BUILDING.get()) return;
        if (stack == null || stack.isEmpty()) return;

        // chat 模式不替换工具提示
        String mode = ModConfig.get().outputMode;
        if ("chat".equals(mode)) return;

        List<Component> original = cir.getReturnValue();
        List<String> replacement = TranslexTooltipContext.lookupReplacement(stack, mode, original);
        if (replacement == null || replacement.isEmpty()) return;

        // 诊断：输出原 tooltip 每行内容 + 对应 replacement（节流 2 秒）
        long now = System.currentTimeMillis();
        boolean doDump = now - lastDumpMs >= DUMP_THROTTLE_MS;
        if (doDump) lastDumpMs = now;
        if (doDump) {
        System.err.println("[Translex] === Tooltip dump (original -> replacement) ===");
        for (int d = 0; d < original.size(); d++) {
            String origLine = original.get(d).getString();
            String replLine = d < replacement.size() ? replacement.get(d) : "<none>";
            String replPreview = replLine != null && replLine.length() > 80
                    ? replLine.substring(0, 80) + "..." : replLine;
            System.err.println("[Translex] line " + d + " | orig='" + origLine + "' | repl='" + replPreview + "'");
        }
        }

        BUILDING.set(true);
        try {
            // 跳过第 0 行（物品名称），只替换后续的说明行（lore）
            int i = 1;
            while (i < original.size() && i < replacement.size()) {
                String repl = replacement.get(i);
                // 段落首行：含 <s 标签 且 后续有空标记行（段落剩余行，orig 非空）
                // 注意：空分隔行（orig='' + repl=''）不是段落空标记，要排除
                boolean isParaStart = repl != null && !repl.isEmpty() && repl.contains("<s")
                        && i + 1 < replacement.size()
                        && (replacement.get(i + 1) == null || replacement.get(i + 1).isEmpty())
                        && i + 1 < original.size()
                        && !original.get(i + 1).getString().isEmpty();  // 下一行原 tooltip 非空（排除分隔行）
                if (isParaStart) {
                    // 段落首行：整段渲染成一个 Component，用 Font.split 按宽度 wrap
                    // 收集后续空标记行（段落剩余行）
                    int paraEnd = i + 1;
                    while (paraEnd < replacement.size()
                            && (replacement.get(paraEnd) == null || replacement.get(paraEnd).isEmpty())
                            && paraEnd < original.size()
                            && !original.get(paraEnd).getString().isEmpty()) {  // 排除空分隔行
                        paraEnd++;
                    }
                    int paraLineCount = paraEnd - i;  // 段落占的原行数
                    // 限制不超过 original 剩余行数（防止 IndexOutOfBounds）
                    int maxLines = original.size() - i;
                    if (paraLineCount > maxLines) paraLineCount = maxLines;
                    if (paraLineCount <= 0) { i = paraEnd; continue; }

                    // 渲染整段 Component（去标签 + 主样式单色，避免 AI 重组标签导致颜色乱）
                    Component paraComponent = LineTemplate.fromText(joinComponents(original, i, Math.min(paraEnd, original.size()))).buildParagraphComponent(repl);

                    // Font.split wrap，动态调 wrapWidth 直到行数 = paraLineCount
                    net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
                    List<Component> wrapped = wrapToLineCount(font, paraComponent, paraLineCount, 400);

                    if (wrapped != null && wrapped.size() == paraLineCount) {
                        // 行数匹配：逐行替换
                        for (int j = 0; j < paraLineCount; j++) {
                            original.set(i + j, wrapped.get(j));
                        }
                    } else {
                        // 行数不匹配：保留原文（不替换，避免整段重复渲染导致"一堆。"）
                        if (doDump) {
                        System.err.println("[Translex] Paragraph wrap mismatch at line " + i
                                + ", target " + paraLineCount + " lines, wrapped="
                                + (wrapped == null ? "null" : wrapped.size()) + ", fallback to original");
                        }
                    }
                    i = paraEnd;
                } else if (repl != null && !repl.isEmpty()) {
                    // 单行：原逻辑
                    original.set(i, LineTemplate.fromText(original.get(i)).buildText(repl));
                    i++;
                } else {
                    // 空标记行（段落剩余行已被首行处理）：跳过
                    i++;
                }
            }
            // 诊断：输出替换后的 tooltip 每行内容（节流）
            if (doDump) {
            System.err.println("[Translex] === Tooltip result ===");
            for (int d = 0; d < original.size(); d++) {
                System.err.println("[Translex] result line " + d + " = '" + original.get(d).getString() + "'");
            }
            }
            cir.setReturnValue(original);
            TranslexTooltipContext.markScreenHandled();
        } finally {
            BUILDING.set(false);
        }
    }

    /** 合并 original[i..end) 的多个 Component 为一个（\n 分隔），用于段落 fromText。 */
    private static Component joinComponents(List<Component> original, int start, int end) {
        net.minecraft.network.chat.MutableComponent combined = net.minecraft.network.chat.Component.literal("");
        for (int j = start; j < end && j < original.size(); j++) {
            if (j > start) combined.append(net.minecraft.network.chat.Component.literal("\n"));
            combined.append(original.get(j));
        }
        return combined;
    }

    /** 用 Font.split 按宽度 wrap，二分找最接近 targetLines 的宽度。
     *  行数 > target：合并尾行对齐。行数 < target：返回 null（文本太短回退）。 */
    private static List<Component> wrapToLineCount(net.minecraft.client.gui.Font font, Component text, int targetLines, int maxWidth) {
        int lo = 50, hi = maxWidth;
        int bestWidth = -1, bestDiff = Integer.MAX_VALUE;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            int lines = font.split(text, mid).size();
            int diff = Math.abs(lines - targetLines);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestWidth = mid;
                if (diff == 0) break;
            }
            if (lines > targetLines) lo = mid + 1;  // 行多 -> 加宽
            else hi = mid - 1;  // 行少 -> 减宽
        }
        if (bestWidth < 0) return null;
        List<net.minecraft.util.FormattedCharSequence> wrapped = font.split(text, bestWidth);
        // 诊断日志（节流）
        if (System.currentTimeMillis() - lastDumpMs < DUMP_THROTTLE_MS) {
        String textStr = text.getString();
        System.err.println("[Translex] wrapDiag: target=" + targetLines + " textLen=" + textStr.length()
                + " bestWidth=" + bestWidth + " lines=" + wrapped.size()
                + " text='" + (textStr.length() > 60 ? textStr.substring(0, 60) + "..." : textStr) + "'");
        }
        List<Component> components = new java.util.ArrayList<>(wrapped.size());
        for (net.minecraft.util.FormattedCharSequence fcs : wrapped) {
            components.add(formattedCharSequenceToComponent(fcs));
        }
        // 行数 > target：合并尾行对齐（前 target-1 行各自，第 target 行 = 剩余合并）
        if (components.size() > targetLines) {
            List<Component> aligned = new java.util.ArrayList<>(targetLines);
            for (int j = 0; j < targetLines - 1; j++) aligned.add(components.get(j));
            net.minecraft.network.chat.MutableComponent last = net.minecraft.network.chat.Component.empty();
            for (int j = targetLines - 1; j < components.size(); j++) last.append(components.get(j));
            aligned.add(last);
            return aligned;
        }
        // 行数 < target：文本太短，无法填满 target 行 -> 回退
        if (components.size() < targetLines) return null;
        return components;
    }

    /** FormattedCharSequence -> Component（保留样式），复用 DrawContextTooltipMixin 的逻辑。 */
    private static Component formattedCharSequenceToComponent(net.minecraft.util.FormattedCharSequence ordered) {
        net.minecraft.network.chat.MutableComponent result = net.minecraft.network.chat.Component.empty();
        StringBuilder segment = new StringBuilder();
        net.minecraft.network.chat.Style[] currentStyle = {net.minecraft.network.chat.Style.EMPTY};
        ordered.accept((index, style, codePoint) -> {
            if (!style.equals(currentStyle[0]) && segment.length() > 0) {
                result.append(net.minecraft.network.chat.Component.literal(segment.toString()).setStyle(currentStyle[0]));
                segment.setLength(0);
            }
            currentStyle[0] = style;
            segment.appendCodePoint(codePoint);
            return true;
        });
        if (segment.length() > 0) {
            result.append(net.minecraft.network.chat.Component.literal(segment.toString()).setStyle(currentStyle[0]));
        }
        return result;
    }
}
