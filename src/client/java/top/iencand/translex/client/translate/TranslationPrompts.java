package top.iencand.translex.client.translate;

/**
 * 强制 system prompt 模板（用户不可见、不可改的格式约束部分）。
 *
 * <p>把"系统必须的格式约束"从用户可调的 prompt 中分离出来，固化为常量：
 * 要求按 JSON 字典同 key 返回、保留占位符 {0} 与 § 颜色码、只输出 JSON 不输出任何额外内容。
 * 物品版额外要求保留 {@code <sN>} 样式标签。目标语言与"专有名词处理策略"通过参数注入。</p>
 *
 * <p>用户的个性化补充（语气、术语偏好等）改由可选的 user 消息承载
 * （{@code ModConfig.userChatPrompt} / {@code userItemPrompt}），与本类互不干扰。</p>
 */
public final class TranslationPrompts {

    private TranslationPrompts() {}

    /** 聊天 / 自由文本翻译的强制 system prompt 模板。第一个 {@code %s} = 目标语言，第二个 {@code %s} = 专有名词指令。
     *  聊天管线 normalizeNumbers=false（数字原样发，无 {num} 占位符），故无占位符保留指令。 */
    private static final String CHAT_SYSTEM_TEMPLATE =
            "You are a translation engine. Translate every value of the JSON object into %s, "
          + "returning a JSON object with the same keys. "
          + "Preserve §a §7 color codes as-is. "
          + "%s"
          + "Actually translate every value; never return the input unchanged. "
          + "If unsure, leave that value unchanged. "
          + "Output ONLY the JSON object.";

    /** 物品 lore 翻译的强制 system prompt 模板，额外要求保留 &lt;sN&gt; 样式标签。 */
    private static final String ITEM_SYSTEM_TEMPLATE =
            "You are a Minecraft item tooltip translation engine. Translate every value of the "
          + "JSON object into %s, returning a JSON object with the same keys. "
          + "A value may be a single line or a multiline paragraph (lines joined by \\n); "
          + "keep the SAME number of lines and the SAME \\n structure. "
          + "{0} {1} etc. are opaque tokens — output them literally in place, never fill or remove. "
          + "<s0> </s0> etc. are style tags — keep the same IDs, count, and order; translate only "
          + "the text between them. "
          + "Preserve §a §7 color codes as-is. "
          + "%s"
          + "Actually translate every value; never return the input unchanged. "
          + "If unsure, leave that value unchanged. "
          + "Output ONLY the JSON object.";

    /** 物品 lore 翻译的 TSP 强 prompt 模板：[[ID||TEXT]] ID 绑定内容 + 反例 + 占位符。
     *  大规模实测（767 段落）颜色准确率 100%，弱 prompt 会 5/5 错（AI 挪内容到错 ID）。 */
    private static final String ITEM_SYSTEM_TEMPLATE_TSP =
            "You are a Minecraft item tooltip translation engine. Translate every value of the "
          + "JSON object into %s, returning a JSON object with the same keys. "
          + "A value may be a single line or a multiline paragraph (lines joined by \\n); "
          + "keep the SAME number of lines and the SAME \\n structure. "
          + "[[NUMBER||TEXT]] tokens: NUMBER is a style ID PERMANENTLY BOUND to its content. "
          + "[[N||X]] MUST become [[N||translated X]] - N stays attached to its ORIGINAL content. "
          + "NEVER move content to a different NUMBER. NEVER merge or split tokens. "
          + "{0} {1} etc. are number placeholders - output them LITERALLY, never fill or remove. "
          + "Example: [[0||56%%]] -> [[0||56%%]], [[1||Glacite]] -> [[1||冰川]] (NOT [[0||冰川]]). "
          + "Reorder whole tokens freely for natural Chinese. "
          + "%s"
          + "Actually translate every value; never return the input unchanged. "
          + "If unsure, leave that value unchanged. "
          + "Output ONLY the JSON object.";

    /** 默认目标语言，targetLanguage 为空时回落。 */
    public static final String DEFAULT_TARGET_LANGUAGE = "Simplified Chinese (简体中文)";

    /** 预设目标语言列表（Web UI 下拉用）。targetLanguage 命中其中之一即为 preset 模式，否则 custom。 */
    public static final java.util.List<String> PRESET_LANGUAGES = java.util.List.of(
            "Simplified Chinese (简体中文)",
            "English",
            "日本語 (Japanese)",
            "繁體中文 (Traditional Chinese)",
            "한국어 (Korean)",
            "Français",
            "Deutsch",
            "Español",
            "Русский (Russian)",
            "Português");

    /** 判定 targetLanguage 是否属于预设列表（Web UI 据此决定显示 preset 下拉还是 custom 输入框）。 */
    public static String deriveTargetLanguageMode(String targetLanguage) {
        if (targetLanguage == null || targetLanguage.isBlank()) return "preset";
        return PRESET_LANGUAGES.contains(targetLanguage.trim()) ? "preset" : "custom";
    }

    /**
     * 反向翻译（{@code /translex say}）的强制 system prompt：把任意输入译成英文并自动发送。
     * 要求只输出译文本身、不加引号/解释/markdown，便于直接作为聊天内容发送。
     */
    private static final String SAY_SYSTEM_PROMPT =
            "You are a translation engine for a Minecraft (Hypixel SkyBlock) chat. "
          + "Translate the user's message into natural, concise English suitable for in-game chat. "
          + "Keep proper nouns, item names, enchantment names and ability names in their conventional SkyBlock English form. "
          + "Output ONLY the translated English text - no quotes, no markdown, no explanations, no extra text, no leading/trailing whitespace.";

    /** 反向翻译（中译英发送）的强制 system prompt。 */
    public static String saySystemPrompt() {
        return SAY_SYSTEM_PROMPT;
    }

    // -------- 专有名词处理策略（用户三档可选） --------

    /** 保留所有专有名词（物品名/附魔名/能力名）为英文。SkyBlock 老玩家习惯。 */
    public static final String PROPER_NOUN_KEEP = "keep";
    /** 全部翻译，包括物品名/附魔名/能力名。 */
    public static final String PROPER_NOUN_TRANSLATE = "translate";
    /** 仅保留物品名为英文，附魔名/能力名翻译。 */
    public static final String PROPER_NOUN_ITEM_ONLY = "item_only";

    private static String properNounClause(String mode) {
        if (mode == null) mode = PROPER_NOUN_KEEP;
        return switch (mode) {
            case PROPER_NOUN_TRANSLATE -> ""; // 不加限制，全部翻译
            case PROPER_NOUN_ITEM_ONLY ->
                    "Keep item names in English, but translate enchantment names and ability names. ";
            default -> // PROPER_NOUN_KEEP
                    "Keep proper nouns, item names, enchantment names and ability names in English. ";
        };
    }

    /** 构造聊天管线的强制 system prompt。 */
    public static String chatSystemPrompt(String targetLanguage, String properNounMode) {
        return String.format(CHAT_SYSTEM_TEMPLATE, safeLang(targetLanguage), properNounClause(properNounMode));
    }

    /** 构造物品管线的强制 system prompt（sN 格式）。 */
    public static String itemSystemPrompt(String targetLanguage, String properNounMode) {
        return String.format(ITEM_SYSTEM_TEMPLATE, safeLang(targetLanguage), properNounClause(properNounMode));
    }

    /** 构造物品管线的强制 system prompt（TSP 格式）。 */
    public static String itemSystemPromptTsp(String targetLanguage, String properNounMode) {
        return String.format(ITEM_SYSTEM_TEMPLATE_TSP, safeLang(targetLanguage), properNounClause(properNounMode));
    }

    /** 目标语言为空时回落到默认值，避免生成 "translate into ." 这种残缺指令。 */
    private static String safeLang(String targetLanguage) {
        return (targetLanguage == null || targetLanguage.isBlank())
                ? DEFAULT_TARGET_LANGUAGE : targetLanguage.trim();
    }
}
