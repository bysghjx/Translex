package top.iencand.translex.client.translate.cache;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import top.iencand.translex.client.util.ItemIdExtractor;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 临时工具提示缓存，用于 "temporary" 输出模式。
 * 以纯文本 {@code List<String>} 存储翻译后的物品说明行。
 * 样式在替换时从原始工具提示行中读取，不在此处存储。
 */
public class TemporaryTooltipCache {
    private static final ConcurrentHashMap<String, List<String>> CACHE = new ConcurrentHashMap<>();

    public static String keyOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String sbId = ItemIdExtractor.extractSkyBlockItemId(stack);
        if (sbId != null) return sbId;
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    public static void put(ItemStack stack, List<String> translatedLines) {
        String key = keyOf(stack);
        if (key == null || translatedLines == null || translatedLines.isEmpty()) return;
        CACHE.put(key, translatedLines);
    }

    public static List<String> peek(ItemStack stack) {
        String key = keyOf(stack);
        if (key == null) return null;
        return CACHE.get(key);
    }

    public static void remove(ItemStack stack) {
        String key = keyOf(stack);
        if (key == null) return;
        CACHE.remove(key);
    }

    public static List<String> get(ItemStack stack) {
        String key = keyOf(stack);
        if (key == null) return null;
        return CACHE.remove(key);
    }

    public static void clear() { CACHE.clear(); }
    public static int size() { return CACHE.size(); }
}
