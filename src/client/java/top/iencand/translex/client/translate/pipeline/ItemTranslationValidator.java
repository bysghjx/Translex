package top.iencand.translex.client.translate.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.iencand.translex.client.translate.model.TranslationFormatRegistry;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 物品说明（lore）翻译管线中的 AI 输出校验与运行时词条学习。
 *
 * <p>从 {@link ItemTranslationPipeline} 拆分出的无状态组件：
 * <ul>
 *     <li>{@link #validateTranslation} 校验 AI 翻译是否保留了全部 {@code {i}} 占位符和
 *         {@code <sN>} 标签，AI 输出损坏时返回 {@code null} 表示回退英文原文。</li>
 *     <li>{@link #learnFromTranslation} 仅对附魔行（行内 2+ 个「名字+数字」模式）做保守学习，
 *         支持 TSP {@code [[ID||TEXT]]} 与 SN {@code <sN>TEXT</sN>} 两种模板。</li>
 * </ul>
 *
 * <p>所有方法均为 {@code public static}，方便主管线（{@link ItemTranslationPipeline}）直接调用；
 * 不持有任何状态，行为与日志语义和拆分前完全一致。
 */
public final class ItemTranslationValidator {

    private ItemTranslationValidator() {}

    /** 与主管线一致的日志名，避免拆分后日志来源变化。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("Translex/ItemPipeline");

    /** 样式匹配正则（与 StyleCodec 一致），用于解析模板中的 {@code <sN>} 段。 */
    private static final Pattern STYLE_TAG_RE = Pattern.compile("<s(\\d+)>(.*?)</s\\1>", Pattern.DOTALL);

    /** 匹配 {@code {0}}、{@code {1}} 等占位符。 */
    private static final Pattern PLACEHOLDER_RE = Pattern.compile("\\{(\\d+)\\}");

    /**
     * 运行时学习：对比原文和译文 token，提取附魔名翻译存入 TermDictionary。
     * 保守策略：只对附魔段学习（行内 2+ 个「名字+数字」模式），只学单附魔……「名字+数字」模式，
     * 数字对齐，避免对描述段学错。
     */
    public static void learnFromTranslation(String origTemplate, String translatedTemplate, String formatId) {
        try {
            // 只对附魔段学习（非附魔不学，避免污染词典）
            if (!isEnchantLine(origTemplate)) return;
            java.util.Map<String, String> orig = extractTokensForLearn(origTemplate, formatId);
            java.util.Map<String, String> trans = extractTokensForLearn(translatedTemplate, formatId);
            java.util.regex.Pattern enchName = java.util.regex.Pattern.compile("^([A-Za-z][A-Za-z\\s]+?)\\s+(\\d+)$");
            java.util.regex.Pattern zhName = java.util.regex.Pattern.compile("^(.+?)\\s+(\\d+)$");
            for (java.util.Map.Entry<String, String> e : orig.entrySet()) {
                String transText = trans.get(e.getKey());
                if (transText == null) continue;
                String o = e.getValue().trim();
                String t = transText.trim();
                java.util.regex.Matcher om = enchName.matcher(o);
                java.util.regex.Matcher tm = zhName.matcher(t);
                if (om.matches() && tm.matches() && om.group(2).equals(tm.group(2))) {
                    String en = om.group(1).trim();
                    String zh = tm.group(1).trim();
                    if (!en.isEmpty() && !zh.isEmpty() && !en.equals(zh)) {
                        top.iencand.translex.client.translate.model.TermDictionary.get().learn(en, zh);
                    }
                }
            }
        } catch (Exception ignored) {
            // 学习失败不影响翻译流程
        }
    }

    /**
     * 附魔行检测：行内 2+ 个「名字+数字」模式（逗号分隔的附魔列表）。
     * 排除 stat 行（含冒号）、描述行（无 2+ 个「名字+数字」）。
     * 对编码后的 template 也能判断（标签结构不影响字母+数字匹配）。
     */
    public static boolean isEnchantLine(String text) {
        if (text == null || text.contains(":")) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "[A-Za-z][A-Za-z\\s]+?\\s+\\d+").matcher(text);
        int count = 0;
        while (m.find()) count++;
        return count >= 2;
    }

    /** 提取模板里的 token（id -> text），支持 TSP {@code [[ID||TEXT]]} 和 SN {@code <sN>TEXT</sN>}。 */
    public static java.util.Map<String, String> extractTokensForLearn(String template, String formatId) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        if (template == null) return map;
        java.util.regex.Pattern p;
        if (TranslationFormatRegistry.usesTspSyntax(formatId)) {
            p = java.util.regex.Pattern.compile("\\[\\[(\\d+)(?::[0-9a-fA-F]+)?\\|\\|((?:\\\\.|[^\\\\\\]])*?)\\]\\]");
        } else {
            p = java.util.regex.Pattern.compile("<s(\\d+)>(.*?)</s\\1>", java.util.regex.Pattern.DOTALL);
        }
        java.util.regex.Matcher m = p.matcher(template);
        while (m.find()) {
            map.put(m.group(1), m.group(2));
        }
        return map;
    }

    /**
     * 验证 AI 翻译结果是否保留了所有 {@code {i}} 占位符和 {@code <sN>} 标签。
     * 若 AI 塌缩了标签或丢失了占位符，返回 {@code null} 表示应回退到英文原文；
     * 否则返回原始 AI 结果（验证通过）。
     *
     * @param original 原始模板（如 {@code <s0>Defense: </s0><s1>{0}</s1><s2>{1}</s2>}）
     * @param aiResult AI 返回的翻译（可能缺少标签/占位符）
     * @param lineIdx  行号（用于日志）
     * @return 验证通过的 AI 结果，或 null 表示损坏需回退
     */
    public static String validateTranslation(String original, String aiResult, int lineIdx) {
        if (original == null || aiResult == null) return aiResult;

        // 1. 统计原始/AI 的 {i} 占位符
        java.util.Set<String> origPH = new java.util.LinkedHashSet<>();
        Matcher mo = PLACEHOLDER_RE.matcher(original);
        while (mo.find()) origPH.add(mo.group());

        java.util.Set<String> aiPH = new java.util.LinkedHashSet<>();
        Matcher ma = PLACEHOLDER_RE.matcher(aiResult);
        while (ma.find()) aiPH.add(ma.group());

        java.util.Set<String> lostPH = new java.util.LinkedHashSet<>(origPH);
        lostPH.removeAll(aiPH);
        boolean lostPlaceholders = !lostPH.isEmpty();

        // 2. 统计标签数量
        int origTags = 0, aiTags = 0;
        Matcher mt = STYLE_TAG_RE.matcher(original);
        while (mt.find()) origTags++;
        mt = STYLE_TAG_RE.matcher(aiResult);
        while (mt.find()) aiTags++;

        boolean collapsed = aiTags < origTags;

        // 始终输出验证结果（INFO 级别，方便确认逻辑执行了）
        LOGGER.info("Validator Line {}: origTags={} aiTags={} collapsed={} origPH={} aiPH={} lostPH={}",
                lineIdx, origTags, aiTags, collapsed, origPH, aiPH, lostPH);

        // 3. 检测到损坏 -> 回退到英文原文（保留单色），不缓存损坏结果
        if (collapsed || lostPlaceholders) {
            LOGGER.warn("⚠ Line {} REJECTED - tags {}->{}  placeholders lost={}  |  orig={}  |  ai={}",
                    lineIdx, origTags, aiTags, lostPH, original, aiResult);
            return null;  // null = 回退到英文原文
        }

        // 4. AI 多加占位符 -> 清洗多余 {i}（不回退，保留翻译）
        java.util.Set<String> extraPH = new java.util.LinkedHashSet<>(aiPH);
        extraPH.removeAll(origPH);
        if (!extraPH.isEmpty()) {
            LOGGER.warn("⚠ Line {} EXTRA placeholders {} - cleaned, kept translation", lineIdx, extraPH);
            String cleaned = aiResult;
            for (String ph : extraPH) cleaned = cleaned.replace(ph, "");
            return cleaned;
        }

        return aiResult;
    }
}
