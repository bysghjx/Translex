package top.iencand.translex.client.translate.model;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tsp.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 编码策略：FULL（所有非默认色 token）/ HYBRID（只保护高风险内容）。 */
    private final TspEncoder.Policy policy;

    /** 是否启用 [[ID:HASH||TEXT]] 校验（开关：vs [[ID||TEXT]]）。由构造传入，不再直读 ModConfig。 */
    private final boolean withChecksum;

    /** 默认 Full TSP。Hybrid 用 TspFormat(TspEncoder.Policy.HYBRID)。 */
    public TspFormat() { this(TspEncoder.Policy.FULL, true); }

    public TspFormat(TspEncoder.Policy policy) { this(policy, true); }

    public TspFormat(TspEncoder.Policy policy, boolean withChecksum) {
        this.policy = policy;
        this.withChecksum = withChecksum;
    }

    /** {@code [[ID||TEXT]]} token，用于 stripFormatTags（缓存键）。 */
    private static final Pattern TSP_TAG = Pattern.compile("\\[\\[\\d+\\|\\|(.*?)\\]\\]", Pattern.DOTALL);

    @Override
    public String id() {
        return policy == TspEncoder.Policy.HYBRID ? "HYBRID" : "TSP";
    }

    @Override
    public boolean usesTspSyntax() {
        return true;
    }

    @Override
    public Encoded encode(StyledText text) {
        List<StyledSegment> segs = new ArrayList<>(text.tspSegments());
        TspRegistry registry = new TspRegistry();
        // Hybrid: 合并相邻同色段（减少 token，如附魔段同色合并）
        if (policy == TspEncoder.Policy.HYBRID) {
            segs = HybridPolicy.mergeAdjacentSameColor(segs);
        }
        // 按策略构建 registry（Hybrid 只 register 保护的段，FULL 全 register）
        // withChecksum 由构造传入（开关：[[ID:HASH||TEXT]] vs [[ID||TEXT]]）
        TspEncoder encoder = (policy == TspEncoder.Policy.HYBRID)
                ? TspEncoder.withHybrid(registry, segs, withChecksum)
                : new TspEncoder(registry, withChecksum);
        String tsp = encoder.encode(segs);
        return new Encoded(tsp, registry.fingerprint());
    }

    @Override
    public Component decode(String template, StyledText original, boolean isParagraph, String registryHash) {
        return decode(template, original, isParagraph, registryHash, null);
    }

    @Override
    public Component decode(String template, StyledText original, boolean isParagraph, String registryHash, RecoveryStats stats) {
        // 从 original 重建 registry（deterministic，跟 encode 时一致）
        List<StyledSegment> origSegs = new ArrayList<>(original.tspSegments());
        TspRegistry registry = new TspRegistry();
        // Hybrid: 合并相邻同色段（必须跟 encode 一致，否则 registry ID 错）
        if (policy == TspEncoder.Policy.HYBRID) {
            origSegs = HybridPolicy.mergeAdjacentSameColor(origSegs);
        }

        // 按策略构建 registry + 默认色（必须跟 encode 一致，否则 ID 映射错）
        tsp.Style defaultStyle = (policy == TspEncoder.Policy.HYBRID)
                ? HybridPolicy.detectHybridDefault(origSegs) : null;
        HybridPolicy hybrid = (policy == TspEncoder.Policy.HYBRID)
                ? new HybridPolicy(defaultStyle) : null;

        // registryHash 校验：颜色结构变（Hypixel 改 lore / 染色变体）-> 返回 null 触发 cache miss
        // 注意：registry 在下面 register 保护的段后才完整，hash 校验要放到 register 之后
        // （但 hash 校验失败应尽早，所以这里先 register 再校验，顺序跟 encode 对齐）
        // v1.1: 建 idHashSet + hashToIds（只对保护的段）。tspChecksum=false 时不建（无 HASH 校验）
        Set<String> idHashSet = withChecksum ? new HashSet<>() : null;
        Map<String, List<Integer>> hashToIds = withChecksum ? new HashMap<>() : null;
        for (StyledSegment seg : origSegs) {
            boolean protect;
            if (hybrid != null) {
                protect = hybrid.shouldProtect(seg);
            } else {
                protect = !seg.isPlain();
            }
            if (protect) {
                int id = registry.register(seg.style());  // 幂等，返回已有 ID
                if (withChecksum) {
                    String hash = TspEncoder.sha4(seg.text());
                    String pair = id + ":" + hash;
                    idHashSet.add(pair);
                    hashToIds.computeIfAbsent(hash, k -> new ArrayList<>()).add(id);
                }
            }
        }

        // registryHash 校验：颜色结构变（Hypixel 改 lore / 染色变体）-> 返回 null 触发 cache miss
        if (registryHash != null && !registryHash.isEmpty()
                && !registry.fingerprint().equals(registryHash)) {
            return null;
        }

        // TSP decode + Level 1/2/3 校验（checksum 检测 + 确定性修复 + missing 检测）
        TspParser parser = new TspParser(TspRecovery.Level.V1);
        TspDecoder decoder = new TspDecoder(registry, idHashSet, hashToIds);
        List<StyledSegment> decoded = decoder.decode(parser.parse(template));

        // v1.1 Metrics: 记 recovery 事件到 RecoveryStats
        if (stats != null) {
            stats.recordTspDecode(decoder.getRepairedCount(), decoder.getAmbiguousCount(),
                    decoder.getInvalidCount(), decoder.getMissingCount(),
                    decoder.getAmbiguousCount() + decoder.getInvalidCount() > 0);
        }

        // Level 3: ambiguous/invalid HASH → smart fallback (strip tokens, show translation + original)
        if (decoder.getAmbiguousCount() + decoder.getInvalidCount() > 0) {
            LOGGER.info("TSP fallback: ambiguous={} invalid={} missing={}",
                    decoder.getAmbiguousCount(), decoder.getInvalidCount(), decoder.getMissingCount());
            tsp.Style bodyStyle = TspEncoder.detectDefaultStyle(origSegs);
            if (bodyStyle == null || bodyStyle.isEmpty()) bodyStyle = tsp.Style.EMPTY;
            return smartFallback(template, original, isParagraph, bodyStyle);
        }
        if (decoder.getMissingCount() > 0) {
            LOGGER.debug("TSP missing: {} token(s) dropped by AI", decoder.getMissingCount());
        }
        if (decoder.getRepairedCount() > 0) {
            LOGGER.info("TSP repaired: {} token(s) (Level 2)", decoder.getRepairedCount());
        }

        // fillNumbers：{0} -> vals[0]（数字从 original 提取，跟 encode 一致）
        // Hybrid: plain 段（默认色裸文本）染上 defaultStyle，避免渲染成白色
        List<StyledSegment> filled = new ArrayList<>();
        for (StyledSegment seg : decoded) {
            tsp.Style style = seg.style();
            if (style.isEmpty() && defaultStyle != null && !defaultStyle.isEmpty()) {
                style = defaultStyle;  // Hybrid 裸文本段染默认色
            }
            filled.add(new StyledSegment(seg.text(), style));
        }

        return original.renderTsp(filled, isParagraph);
    }

    @Override
    public String stripFormatTags(String template) {
        return TSP_TAG.matcher(template).replaceAll("$1");
    }

    /**
     * Strip ALL TSP tokens from the AI output, recovering human-readable text.
     * Uses the parser (with nested flatten recovery) so nested/malformed tokens
     * also get their text content extracted where possible.
     */
    private static String stripAllTspTokens(String template) {
        TspParser parser = new TspParser(TspRecovery.Level.V1);
        TspParser.ParseResult r = parser.parse(template);
        StringBuilder sb = new StringBuilder();
        for (TspElement e : r.elements()) {
            if (e instanceof TspToken t) {
                sb.append(t.text());
            } else if (e instanceof TspText t) {
                // Strip any remaining [[...]] markers from unrecovered spans
                sb.append(t.text().replaceAll("\\[\\[[^\\]]*\\|\\|", "")
                        .replace("]]", "").replace("[[", ""));
            }
        }
        return sb.toString().trim();
    }

    /**
     * Smart fallback: instead of fully reverting to original, keep the AI's
     * human-readable translation (strip [[...]] markers) with the body text color,
     * and append the original text in gray as reference.
     */
    private Component smartFallback(String template, StyledText original, boolean isParagraph,
                                     tsp.Style bodyTspStyle) {
        String translated = stripAllTspTokens(template);
        String originalText = original.plainText();

        net.minecraft.network.chat.Style bodyMc = StyledText.toMcStyle(bodyTspStyle);
        MutableComponent result = Component.empty();

        if (!translated.isEmpty()) {
            result.append(Component.literal(translated).setStyle(bodyMc));
        }
        if (!originalText.isBlank()) {
            if (!translated.isEmpty()) result.append(Component.literal(" "));
            result.append(Component.literal("(原文: " + originalText + ")")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
        } else if (translated.isEmpty()) {
            // Nothing salvageable — render original as-is
            return original.component();
        }

        if (isParagraph) {
            // Flatten newlines like normal paragraph rendering
            return original.renderTsp(List.of(
                    new StyledSegment(result.getString(), bodyTspStyle)), true);
        }
        return result;
    }

}
