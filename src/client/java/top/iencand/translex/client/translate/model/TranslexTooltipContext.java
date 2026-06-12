package top.iencand.translex.client.translate.model;

import net.minecraft.item.ItemStack;
import top.iencand.translex.client.translate.cache.TemporaryTooltipCache;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.util.ItemIdExtractor;

import java.util.List;

/**
 * 共享的守卫和缓存查找工具类，供 ScreenTooltipMixin 和 DrawContextTooltipMixin 使用。
 * 返回纯文本 {@code List<String>}，样式在替换时从原始工具提示行中获取。
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

    public static List<String> lookupReplacement(ItemStack stack, String mode) {
        if (stack == null || stack.isEmpty()) return null;
        if ("temporary".equals(mode)) return TemporaryTooltipCache.peek(stack);
        if ("permanent".equals(mode)) {
            String itemId = ItemIdExtractor.extractSkyBlockItemId(stack);
            if (itemId != null) return ItemPresetLibrary.getInstance().getTooltip(itemId);
        }
        return null;
    }
}
