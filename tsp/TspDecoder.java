package tsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts parsed TSP elements back into styled text segments.
 *
 * <p>Rules (from spec §6):
 * <ul>
 *   <li>{@link TspToken}: looks up the style by ID in the {@link TspRegistry};
 *       unknown IDs fall back to {@link Style#EMPTY}.</li>
 *   <li>{@link TspText}: emitted as plain text (Style.EMPTY).</li>
 * </ul>
 *
 * <h3>v1.1 checksum 校验（Level 1/2/3 Recovery）</h3>
 * 传入 {@code idHashSet}（合法 (ID:HASH) 对）+ {@code hashToIds}（HASH->可能 ID 列表）时，
 * decoder 对带 checksum 的 token 做完整性校验：
 * <ul>
 *   <li>(ID, HASH) 在集合 -> <b>Level 0 正确</b>，用 ID 查颜色</li>
 *   <li>HASH 合法且唯一匹配（hashToIds[size==1]）-> <b>Level 2 确定性修复</b>：
 *       用唯一 ID 查颜色</li>
 *   <li>HASH 合法但多匹配（hashToIds[size>1]）-> <b>Level 3 ambiguous 不猜</b>：
 *       Style.EMPTY，调用方整段回退（不猜内容归属）</li>
 *   <li>HASH 不合法 -> <b>Level 3 invalid</b>：Style.EMPTY，整段回退</li>
 *   <li>无 checksum 或无校验集 -> 现有逻辑（按 ID 查，不校验）</li>
 * </ul>
 *
 * <p>原则：能确定就修，不能确定就回退。颜色错比没翻译严重，宁可少翻也不染错色。</p>
 */
public final class TspDecoder {

    private final TspRegistry registry;
    private final Set<String> idHashSet;              // 合法 (ID:HASH) 对，null = 不校验
    private final Map<String, List<Integer>> hashToIds;  // HASH -> 可能的 ID 列表（Level 2 修复用）

    // 单次 decode 的统计（每次 decode 重置）
    private int repairedCount;    // Level 2: HASH 唯一匹配，自动修复 ID
    private int ambiguousCount;   // Level 3: HASH 多匹配，不猜（ambiguous）
    private int invalidCount;     // Level 3: HASH 不合法 / 丢失
    private int missingCount;     // Level 3: 输入 (ID,HASH) 对在输出中缺失（AI 丢 token）

    public TspDecoder(TspRegistry registry) {
        this(registry, null, null);
    }

    /** v1.1 校验版：传入 idHashSet + hashToIds 启用 Level 1/2/3。 */
    public TspDecoder(TspRegistry registry, Set<String> idHashSet, Map<String, List<Integer>> hashToIds) {
        this.registry = registry;
        this.idHashSet = idHashSet;
        this.hashToIds = hashToIds;
    }

    public List<StyledSegment> decode(TspParser.ParseResult parseResult) {
        repairedCount = 0;
        ambiguousCount = 0;
        invalidCount = 0;
        missingCount = 0;
        // Track which (ID, HASH) pairs from idHashSet are covered by output tokens.
        // Includes Level 2 repairs: if token arrives with wrong ID but HASH fixes it,
        // the corrected (ID, HASH) is also covered.
        Set<String> covered = idHashSet != null ? new java.util.HashSet<>() : null;
        List<StyledSegment> segments = new ArrayList<>();
        for (TspElement element : parseResult.elements()) {
            switch (element) {
                case TspToken token -> {
                    Style s = resolveStyle(token, covered);
                    segments.add(new StyledSegment(token.text(), s));
                }
                case TspText text -> segments.add(StyledSegment.plain(text.text()));
            }
        }
        // Post-decode: detect (ID, HASH) pairs from input that are missing in output.
        // Skip when output has zero checksum tokens (v1.0 compat — nothing to match).
        if (covered != null) {
            boolean anyChecksum = false;
            for (TspToken t : parseResult.tokens()) {
                if (t.checksum() != null) { anyChecksum = true; break; }
            }
            if (anyChecksum) {
                for (String expected : idHashSet) {
                    if (!covered.contains(expected)) missingCount++;
                }
            }
        }
        return segments;
    }

    /** 便捷：parse + decode。 */
    public List<StyledSegment> decodeString(String tspString) {
        TspParser parser = new TspParser();
        return decode(parser.parse(tspString));
    }

    /**
     * 解析 token 的样式，含 v1.1 checksum 校验（Level 1/2/3）。
     * 原则：能确定就修，不能确定就回退（Style.EMPTY，调用方整段回退）。
     */
    private Style resolveStyle(TspToken token, Set<String> covered) {
        if (idHashSet != null && token.checksum() != null) {
            String idHash = token.id() + ":" + token.checksum();
            if (idHashSet.contains(idHash)) {
                // Level 0: (ID, HASH) 匹配 -> 正确
                if (covered != null) covered.add(idHash);
                return registry.getStyle(token.id());
            } else if (hashToIds != null && hashToIds.containsKey(token.checksum())) {
                // HASH 合法，检查匹配数
                List<Integer> ids = hashToIds.get(token.checksum());
                if (ids.size() == 1) {
                    // Level 2: 唯一匹配 -> 确定性修复 ID（100% 确定，不猜）
                    repairedCount++;
                    if (covered != null) covered.add(ids.get(0) + ":" + token.checksum());
                    return registry.getStyle(ids.get(0));
                } else {
                    // Level 3: 多匹配（同内容不同色）-> ambiguous，不猜
                    ambiguousCount++;
                    return Style.EMPTY;
                }
            } else {
                // Level 3: HASH 不合法（AI 乱编 / 丢失）-> invalid
                invalidCount++;
                return Style.EMPTY;
            }
        }
        // 无 checksum 或无校验集 -> 现有逻辑（按 ID 查，未知 ID -> EMPTY）
        return registry.getStyle(token.id());
    }

    /** Level 2 确定性修复次数（HASH 唯一匹配，改回正确 ID）。 */
    public int getRepairedCount() { return repairedCount; }

    /** Level 3 ambiguous 次数（HASH 多匹配，不猜）。 */
    public int getAmbiguousCount() { return ambiguousCount; }

    /** Level 3 invalid 次数（HASH 不合法）。 */
    public int getInvalidCount() { return invalidCount; }

    /** Level 3 missing 次数（输入 token 在输出中缺失，AI 丢 token）。 */
    public int getMissingCount() { return missingCount; }

    /** Level 3 总异常（ambiguous + invalid + missing）。>0 时调用方应整段回退。 */
    public int getLevel3Count() { return ambiguousCount + invalidCount + missingCount; }
}
