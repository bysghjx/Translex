package top.iencand.translex.client.translate.model;

import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import top.iencand.translex.client.translate.cache.TemporaryTooltipCache;
import top.iencand.translex.client.util.TooltipKeyUtil;

import java.util.List;

/**
 * 共享的守卫和缓存查找工具类，供 ScreenTooltipMixin 和 DrawContextTooltipMixin 使用。
 * 返回纯文本 {@code List<String>}，样式在替换时从原始工具提示行中获取。
 *
 * <p>缓存查找使用 {@link TooltipKeyUtil} 的 {@code itemId#loreHash} 组合键，
 * 因此调用方必须传入当前完整 tooltip 行以参与哈希计算。
 */
public final class TranslexTooltipContext {

    private TranslexTooltipContext() {}

    private static final ThreadLocal<Boolean> SCREEN_HANDLED = ThreadLocal.withInitial(() -> false);

    public static void markScreenHandled() { SCREEN_HANDLED.set(true); }

    public static boolean consumeScreenHandled() {
        boolean handled = SCREEN_HANDLED.get();
        SCREEN_HANDLED.set(false);
        return handled;
    }

    /**
     * 查找物品的替换文本。
     *
     * @param stack              物品
     * @param mode               输出模式（temporary / permanent / chat）
     * @param currentTooltipLines 当前完整 tooltip 行（含第 0 行物品名），参与组合键哈希
     */
    public static List<String> lookupReplacement(ItemStack stack, String mode, List<Component> currentTooltipLines) {
        if (stack == null || stack.isEmpty()) return null;
        String key = TooltipKeyUtil.buildKey(stack, currentTooltipLines);
        if (key == null) return null;
        if ("temporary".equals(mode)) {
            // #3：temporary 译文只在"按 P 翻译时悬停的那个 Slot"上显示。
            // Slot 门控未激活（鼠标已离开该槽位）→ 返回 null 显原文，移回需重按 P。
            String loreHash = TooltipKeyUtil.loreHash(currentTooltipLines);
            if (!top.iencand.translex.client.listener.HoverSlotTracker.isActiveForCurrentHover(loreHash)) {
                return null;
            }
            return TemporaryTooltipCache.peek(stack, currentTooltipLines);
        }
        if ("permanent".equals(mode)) {
            return ItemPresetLibrary.getInstance().getTooltip(key);
        }
        return null;
    }
}
