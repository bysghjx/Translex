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

    /** 聊天 / 自由文本翻译的强制 system prompt 模板。第一个 {@code %s} = 目标语言，第二个 {@code %s} = 专有名词指令。 */
    private static final String CHAT_SYSTEM_TEMPLATE =
            "You are a translation engine. The user message is a JSON object whose values are texts to translate. "
          + "Translate every value into %s. "
          + "Return a JSON object with exactly the same keys, each value being the translation. "
          + "Preserve placeholders like {0} {1} exactly as-is. "
          + "Preserve Minecraft section-sign color codes (e.g. §a §7) exactly as-is and in the same positions. "
          + "%s"
          + "Output ONLY the JSON object - no markdown, no code fences, no explanations, no extra text.";

    /** 物品 lore 翻译的强制 system prompt 模板，额外要求保留 &lt;sN&gt; 样式标签。 */
    private static final String ITEM_SYSTEM_TEMPLATE =
            "You are a translation engine for Minecraft item tooltips. The user message is a JSON object whose values are texts to translate. "
          + "Translate every value into %s. "
          + "Return a JSON object with exactly the same keys, each value being the translation. "
          + "Each line (JSON value) is independent — translate it as a self-contained unit. "
          + "CRITICAL: {0} {1} {2} etc. are opaque protection tokens, NOT variables to resolve. "
          + "You do NOT know what numbers they represent — NEVER fill them with concrete values like \"+30\" or \"(+30)\". "
          + "Output them literally as {0} {1} {2} in the exact same positions relative to the surrounding text. "
          + "Each placeholder is a distinct value — keep all of them even if two happen to represent the same number. "
          + "CRITICAL: Preserve style tags <s0> </s0> <s1> </s1> etc. exactly. "
          + "Keep the SAME tag IDs, the SAME number of tags, and the SAME sequential order as the input. "
          + "NEVER collapse multiple tags into one, NEVER create new tags, NEVER remove existing ones. "
          + "Even if two adjacent segments would look identical after translation, keep their tags separate. "
          + "Only translate the visible text between tags — treat tags and placeholders as untouchable tokens. "
          + "Preserve Minecraft section-sign color codes (e.g. §a §7) exactly as-is. "
          + "%s"
          + "Output ONLY the JSON object - no markdown, no code fences, no explanations, no extra text.";

    /** 默认目标语言，targetLanguage 为空时回落。 */
    public static final String DEFAULT_TARGET_LANGUAGE = "Simplified Chinese (简体中文)";

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

    /** 构造物品管线的强制 system prompt。 */
    public static String itemSystemPrompt(String targetLanguage, String properNounMode) {
        return String.format(ITEM_SYSTEM_TEMPLATE, safeLang(targetLanguage), properNounClause(properNounMode));
    }

    /** 目标语言为空时回落到默认值，避免生成 "translate into ." 这种残缺指令。 */
    private static String safeLang(String targetLanguage) {
        return (targetLanguage == null || targetLanguage.isBlank())
                ? DEFAULT_TARGET_LANGUAGE : targetLanguage.trim();
    }
}
