package top.iencand.translex.client.cache;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import top.iencand.translex.client.util.ItemIdExtractor;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds translated tooltip lines as plain {@code List<String>} for
 * "temporary" output mode. Styles are applied at replacement time
 * from the original tooltip lines, not stored here.
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
