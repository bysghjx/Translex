package top.iencand.translex.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 物品 lore 数据采集器：抓取当前打开 GUI 所有 slot 的 tooltip（含完整样式），
 * 序列化成 segment JSON 存本地，按 {@code itemId#loreHash} 去重。
 *
 * <p>用途：为 TSP Full + 强 prompt 大规模测试提供真实 Hypixel lore 数据。
 * 存储格式（JSONL，每行一个物品）：
 * <pre>
 * {"itemId":"PET_MAMMOTH","loreHash":"abc...","displayName":"[Lvl 56] Mammoth",
 *  "lines":[[{"t":"[Lvl 56] ","c":"#AAAAAA"},{"t":"Mammoth","c":"#FFAA00"}],...]}
 * </pre>
 * segment：t=text, c=colorHex(可空), b/i/u/s/o=bold/italic/underlined/strikethrough/obfuscated(仅 true 时写)。
 *
 * <p>关键：调用 {@link Screen#getTooltipFromItem} 时设 harvesting 标志，
 * {@link ScreenTooltipMixin} 检测到则跳过翻译替换，保留原文 tooltip。
 */
public final class LoreHarvester {
    private static final Logger LOGGER = LoggerFactory.getLogger("Translex/Harvester");

    private LoreHarvester() {}

    /** 采集结果。error 非 null 表示失败（如未打开 GUI）。 */
    public record HarvestResult(int total, int added, int skipped, String error) {
        public boolean success() { return error == null; }
    }

    /**
     * 抓取当前打开 GUI 的所有 slot tooltip，去重后追加写入 harvest 文件。
     * 必须在 MC 主线程调用。
     */
    public static HarvestResult harvestAll() {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (!(screen instanceof AbstractContainerScreen<?> acs)) {
            return new HarvestResult(0, 0, 0, "no_screen");
        }

        File file = getHarvestFile();
        Set<String> existing = loadExistingHashes(file);

        int total = 0, added = 0, skipped = 0;
        List<String> newLines = new ArrayList<>();

        for (Slot slot : acs.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) continue;
            total++;

            // 拿原文 tooltip：设 harvesting 标志绕过 ScreenTooltipMixin 替换
            List<Component> tooltip;
            HarvestFlag.setHarvesting(true);
            try {
                tooltip = Screen.getTooltipFromItem(mc, stack);
            } finally {
                HarvestFlag.setHarvesting(false);
            }
            if (tooltip == null || tooltip.isEmpty()) continue;

            String key = TooltipKeyUtil.buildKey(stack, tooltip);
            if (key == null) continue;
            String loreHash = key.substring(key.indexOf('#') + 1);
            if (existing.contains(loreHash)) {
                skipped++;
                continue;
            }

            newLines.add(serialize(stack, tooltip, key));
            existing.add(loreHash);
            added++;
        }

        if (!newLines.isEmpty()) {
            file.getParentFile().mkdirs();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                for (String line : newLines) w.write(line + "\n");
            } catch (IOException e) {
                LOGGER.error("Failed to write harvest file: {}", file, e);
                return new HarvestResult(total, 0, skipped, "write_error");
            }
        }
        return new HarvestResult(total, added, skipped, null);
    }

    /** harvest 文件路径：{@code <configDir>/translex/harvest/lore.jsonl} */
    public static File getHarvestFile() {
        File dir = new File(FabricLoader.getInstance().getConfigDir().toFile(), "translex/harvest");
        return new File(dir, "lore.jsonl");
    }

    // ================================================================
    // 序列化
    // ================================================================

    /** 序列化一个物品为 JSON 行。 */
    private static String serialize(ItemStack stack, List<Component> tooltip, String key) {
        int hashIdx = key.indexOf('#');
        String itemId = key.substring(0, hashIdx);
        String loreHash = key.substring(hashIdx + 1);
        String displayName = stack.getHoverName() != null ? stack.getHoverName().getString() : "";

        StringBuilder sb = new StringBuilder();
        sb.append("{\"itemId\":").append(jsonStr(itemId));
        sb.append(",\"loreHash\":").append(jsonStr(loreHash));
        sb.append(",\"displayName\":").append(jsonStr(displayName));
        sb.append(",\"lines\":[");
        for (int i = 0; i < tooltip.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(segmentsToJson(componentToSegments(tooltip.get(i))));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String segmentsToJson(List<Segment> segs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < segs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(segs.get(i).toJson());
        }
        return sb.append("]").toString();
    }

    /** 一个带样式片段：text + 可选颜色 + 5 个格式标志。 */
    public record Segment(String text, String colorHex, boolean bold, boolean italic,
                          boolean underlined, boolean strikethrough, boolean obfuscated) {
        String toJson() {
            StringBuilder sb = new StringBuilder("{\"t\":").append(jsonStr(text));
            if (colorHex != null) sb.append(",\"c\":").append(jsonStr(colorHex));
            if (bold) sb.append(",\"b\":true");
            if (italic) sb.append(",\"i\":true");
            if (underlined) sb.append(",\"u\":true");
            if (strikethrough) sb.append(",\"s\":true");
            if (obfuscated) sb.append(",\"o\":true");
            return sb.append("}").toString();
        }
    }

    /** 遍历 Component 树，每个带样式段 -> Segment。 */
    private static List<Segment> componentToSegments(Component line) {
        List<Segment> segs = new ArrayList<>();
        if (line == null) return segs;
        line.visit((style, str) -> {
            if (str != null && !str.isEmpty()) {
                segs.add(toSegment(str, style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return segs;
    }

    private static Segment toSegment(String text, Style style) {
        String colorHex = null;
        if (style.getColor() != null) {
            colorHex = String.format("#%06X", style.getColor().getValue());
        }
        return new Segment(text, colorHex,
                style.isBold(), style.isItalic(), style.isUnderlined(),
                style.isStrikethrough(), style.isObfuscated());
    }

    // ================================================================
    // 去重：读已有 loreHash 集合
    // ================================================================

    private static Set<String> loadExistingHashes(File file) {
        Set<String> hashes = new HashSet<>();
        if (!file.exists()) return hashes;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int h = line.indexOf("\"loreHash\":");
                if (h < 0) continue;
                int q1 = line.indexOf('"', h + 11);
                int q2 = q1 >= 0 ? line.indexOf('"', q1 + 1) : -1;
                if (q1 >= 0 && q2 > q1) hashes.add(line.substring(q1 + 1, q2));
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read existing harvest file: {}", file, e);
        }
        return hashes;
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append("\"").toString();
    }
}
