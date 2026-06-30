package top.iencand.translex.client.translate.cache;

import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import top.iencand.translex.client.util.TooltipKeyUtil;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 临时工具提示缓存，用于 "temporary" 输出模式。
 * 以纯文本 {@code List<String>} 存储翻译后的物品说明行。
 * 样式在替换时从原始工具提示行中读取，不在此处存储。
 *
 * <p>缓存键为 {@link TooltipKeyUtil} 生成的 {@code itemId#loreHash} 组合键，
 * 因此同 ID 但 lore 不同的物品不再串台。调用方需提供当前 tooltip 行
 * 以参与哈希计算。
 */
public class TemporaryTooltipCache {
    private static final ConcurrentHashMap<String, List<String>> CACHE = new ConcurrentHashMap<>();

    /**
     * 计算物品 + 当前 tooltip 行的组合缓存键。
     * @param stack        物品
     * @param tooltipLines 当前完整 tooltip 行（含第 0 行物品名）
     */
    public static String keyOf(ItemStack stack, List<Component> tooltipLines) {
        return TooltipKeyUtil.buildKey(stack, tooltipLines);
    }

    public static void put(ItemStack stack, List<Component> tooltipLines, List<String> translatedLines) {
        String key = keyOf(stack, tooltipLines);
        if (key == null || translatedLines == null || translatedLines.isEmpty()) return;
        CACHE.put(key, translatedLines);
    }

    public static List<String> peek(ItemStack stack, List<Component> tooltipLines) {
        String key = keyOf(stack, tooltipLines);
        if (key == null) return null;
        return CACHE.get(key);
    }

    public static void remove(ItemStack stack, List<Component> tooltipLines) {
        String key = keyOf(stack, tooltipLines);
        if (key == null) return;
        CACHE.remove(key);
    }

    /** 按组合键直接移除（调用方已持有键）。 */
    public static void removeByKey(String key) {
        if (key != null) CACHE.remove(key);
    }

    public static void clear() { CACHE.clear(); }
    public static int size() { return CACHE.size(); }
}
