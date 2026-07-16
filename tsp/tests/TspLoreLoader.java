package tsp.tests;

import tsp.Style;
import tsp.StyledSegment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 加载 harvest 采集的 lore.jsonl，转成 TSP 可用的 {@link StyledSegment} 结构。
 *
 * <p>这是"Component -> TSP"桥接的纯 Java 半边：segment JSON（含颜色+格式标志）
 * -> {@link StyledSegment}。Phase 1 Draft 1 协议只支持 color，bold/italic 等格式
 * 标志暂不还原（spec §11 Future Extensions），round-trip 只验证 text + color。</p>
 */
public final class TspLoreLoader {

    public record ItemData(String itemId, String loreHash, String displayName,
                           List<List<StyledSegment>> lines) {}

    private TspLoreLoader() {}

    /** 加载 lore.jsonl，每行一个物品。 */
    public static List<ItemData> load(Path file) throws IOException {
        List<ItemData> items = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            items.add(parseLine(line));
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private static ItemData parseLine(String json) {
        Map<String, Object> obj = TspJson.parseObject(json);
        String itemId = (String) obj.get("itemId");
        String loreHash = (String) obj.get("loreHash");
        String displayName = (String) obj.get("displayName");

        List<List<StyledSegment>> lines = new ArrayList<>();
        List<Object> rawLines = (List<Object>) obj.get("lines");
        for (Object rawLine : rawLines) {
            List<StyledSegment> segs = new ArrayList<>();
            for (Object rawSeg : (List<Object>) rawLine) {
                Map<String, Object> seg = (Map<String, Object>) rawSeg;
                String text = (String) seg.get("t");
                String color = (String) seg.get("c");
                Style style = (color != null) ? Style.of(color) : Style.EMPTY;
                // bold/italic/underlined/strikethrough/obfuscated 暂不还原（Phase 1 只 color）
                segs.add(new StyledSegment(text, style));
            }
            lines.add(segs);
        }
        return new ItemData(itemId, loreHash, displayName, lines);
    }
}
