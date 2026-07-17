package top.iencand.translex.client.translate.model;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tsp.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TSP 格式（{@code [[ID||TEXT]]}，颜色 dedup ID）。
 *
 * <p>encode：Component -> StyleCodec.extract -> {@code <sN>} 段 -> 数字保护 {@code {0}} ->
 * {@link StyledSegment} -> {@link TspEncoder}（Full TSP）-> tspText + registry fingerprint。</p>
 *
 * <p>decode：从 original 重建 registry（deterministic）-> fingerprint 校验缓存 registryHash
 * （颜色结构变 -> null 触发 miss）-> {@link TspParser}(V1) + {@link TspDecoder} -> fillNumbers
 * -> Component。段落模式 \n->空格 喂 Font.split。</p>
 *
 * <p>Phase 1 只传颜色（tsp.Style 仅 colorHex）。bold/italic 等格式标志待协议扩展后加进
 * toTspStyle/toMcStyle + fingerprint。</p>
 */
public final class TspFormat implements TranslationFormat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Translex/TspFormat");

    /** 数字段正则（与 LineTemplate.NUMBER 一致），用于 {0} 占位符保护。 */
    private static final Pattern NUMBER = Pattern.compile(
            "[\\d.,+%kmb\\-s()]*\\d[\\d.,+%kmb\\-s()]*", Pattern.CASE_INSENSITIVE);

    /** {@code <sN>content</sN>} 标签（与 StyleCodec 一致），用于解析 extract 结果。 */
    private static final Pattern STYLE_TAG = Pattern.compile(
            "<s(\\d+)>(.*?)</s\\1>", Pattern.DOTALL);

    /** {@code [[ID||TEXT]]} token，用于 stripFormatTags（缓存键）。 */
    private static final Pattern TSP_TAG = Pattern.compile("\\[\\[\\d+\\|\\|(.*?)\\]\\]", Pattern.DOTALL);

    @Override
    public String id() { return "TSP"; }

    @Override
    public Encoded encode(Component component) {
        List<StyledSegment> segs = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        TspRegistry registry = new TspRegistry();
        extractSegments(component, segs, vals, registry);
        TspEncoder encoder = new TspEncoder(registry, true);  // v1.1: Full TSP + checksum
        String tsp = encoder.encode(segs);
        return new Encoded(tsp, registry.fingerprint());
    }

    @Override
    public Component decode(String template, Component original, boolean isParagraph, String registryHash) {
        return decode(template, original, isParagraph, registryHash, null);
    }

    @Override
    public Component decode(String template, Component original, boolean isParagraph, String registryHash, RecoveryStats stats) {
        // 从 original 重建 registry（deterministic，跟 encode 时一致）
        List<StyledSegment> origSegs = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        TspRegistry registry = new TspRegistry();
        extractSegments(original, origSegs, vals, registry);

        // registryHash 校验：颜色结构变（Hypixel 改 lore / 染色变体）-> 返回 null 触发 cache miss
        if (registryHash != null && !registryHash.isEmpty()
                && !registry.fingerprint().equals(registryHash)) {
            return null;
        }

        // v1.1: 建 idHashSet + hashToIds（从 origSegs，跟 encode 一致）
        Set<String> idHashSet = new HashSet<>();
        Map<String, List<Integer>> hashToIds = new HashMap<>();
        for (StyledSegment seg : origSegs) {
            if (!seg.isPlain()) {
                int id = registry.register(seg.style());  // 幂等，返回已有 ID
                String hash = TspEncoder.sha4(seg.text());
                idHashSet.add(id + ":" + hash);
                hashToIds.computeIfAbsent(hash, k -> new ArrayList<>()).add(id);
            }
        }

        // TSP decode + Level 1/2/3 校验（checksum 检测 + 确定性修复 + ambiguous 回退）
        TspParser parser = new TspParser(TspRecovery.Level.V1);
        TspDecoder decoder = new TspDecoder(registry, idHashSet, hashToIds);
        List<StyledSegment> decoded = decoder.decode(parser.parse(template));

        // v1.1 Metrics: 记 recovery 事件到 RecoveryStats
        if (stats != null) {
            stats.recordTspDecode(decoder.getRepairedCount(), decoder.getAmbiguousCount(),
                    decoder.getInvalidCount(), decoder.getLevel3Count() > 0);
        }
        // Level 3: ambiguous + invalid > 0（多匹配不猜 / HASH 不合法）-> 整段回退原文
        if (decoder.getLevel3Count() > 0) {
            LOGGER.info("TSP fallback: ambiguous={} invalid={}", decoder.getAmbiguousCount(), decoder.getInvalidCount());
            return null;
        }
        if (decoder.getRepairedCount() > 0) {
            LOGGER.info("TSP repaired: {} token(s) (Level 2)", decoder.getRepairedCount());
        }

        // fillNumbers：{0} -> vals[0]（数字从 original 提取，跟 encode 一致）
        List<StyledSegment> filled = new ArrayList<>();
        for (StyledSegment seg : decoded) {
            filled.add(new StyledSegment(fillNumbers(seg.text(), vals), seg.style()));
        }

        return toComponent(filled, isParagraph);
    }

    @Override
    public String stripFormatTags(String template) {
        return TSP_TAG.matcher(template).replaceAll("$1");
    }

    /**
     * Component -> List<StyledSegment> + 数字 vals + 注册 registry。
     * 复用 StyleCodec.extract 提取 {@code <sN>} 标签 + styleMap，再转 StyledSegment。
     * encode/decode 共用，保证 registry 重建一致。
     */
    private static void extractSegments(Component component, List<StyledSegment> segs,
                                         List<String> vals, TspRegistry registry) {
        StyleCodec.ExtractionResult r = StyleCodec.extract(component);
        String marked = r.markedText();
        Matcher m = STYLE_TAG.matcher(marked);
        int lastEnd = 0;
        while (m.find()) {
            if (m.start() > lastEnd) {
                // 标签外裸文本 -> plain
                segs.add(StyledSegment.plain(marked.substring(lastEnd, m.start())));
            }
            int id = Integer.parseInt(m.group(1));
            String content = m.group(2);
            tsp.Style tspStyle = toTspStyle(r.styleMap().get(id));
            // 数字保护：纯数字段 -> {N} 占位符（防 AI 改数值）
            if (NUMBER.matcher(content).matches()) {
                segs.add(new StyledSegment("{" + vals.size() + "}", tspStyle));
                vals.add(content);
            } else {
                segs.add(new StyledSegment(content, tspStyle));
            }
            registry.register(tspStyle);  // 注册（dedup 同色，ID 按首次出现）
            lastEnd = m.end();
        }
        if (lastEnd < marked.length()) {
            segs.add(StyledSegment.plain(marked.substring(lastEnd)));
        }
    }

    /** Minecraft Style -> TSP Style（Phase 1 只颜色）。 */
    private static tsp.Style toTspStyle(net.minecraft.network.chat.Style mc) {
        if (mc == null || mc.getColor() == null) return tsp.Style.EMPTY;
        return tsp.Style.of(String.format("#%06X", mc.getColor().getValue()));
    }

    /** TSP Style -> Minecraft Style（Phase 1 只颜色）。 */
    private static net.minecraft.network.chat.Style toMcStyle(tsp.Style tspStyle) {
        if (tspStyle == null || tspStyle.isEmpty()) return net.minecraft.network.chat.Style.EMPTY;
        try {
            int rgb = Integer.parseInt(tspStyle.colorHex().substring(1), 16);
            return net.minecraft.network.chat.Style.EMPTY.withColor(TextColor.fromRgb(rgb));
        } catch (Exception e) {
            return net.minecraft.network.chat.Style.EMPTY;
        }
    }

    /** StyledSegment 列表 -> Minecraft Component。段落模式 \n->空格（喂 Font.split wrap）。 */
    private static Component toComponent(List<StyledSegment> segs, boolean isParagraph) {
        MutableComponent result = Component.empty();
        for (StyledSegment seg : segs) {
            String text = seg.text();
            if (isParagraph) {
                text = text.replace("\n", " ").replaceAll("\\s{2,}", " ");
            }
            result.append(Component.literal(text).setStyle(toMcStyle(seg.style())));
        }
        return result;
    }

    /** {N} -> vals[N]。 */
    private static String fillNumbers(String text, List<String> vals) {
        String r = text;
        for (int i = 0; i < vals.size(); i++) r = r.replace("{" + i + "}", vals.get(i));
        return r;
    }
}
