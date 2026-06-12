package top.iencand.translex.client.translate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对 AI 返回的翻译结果进行本地后处理。
 *
 * <p>处理内容：
 * <ul>
 *   <li>解码残留的 Unicode 转义序列</li>
 *   <li>规范化多余空白行</li>
 *   <li>修复 AI 偶尔产出的双转义换行（仅当与真实换行相邻时）</li>
 *   <li>清理首尾多余符号</li>
 * </ul>
 */
public final class TranslationPostProcessor {

    /** 匹配 \\ uXXXX 形式的 Unicode 转义（4 位十六进制） */
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    private TranslationPostProcessor() {}

    /**
     * 对单条翻译文本执行后处理。
     * @param text 原始翻译文本，可能为 null
     * @return 清理后的文本；null 输入返回空字符串
     */
    public static String clean(String text) {
        if (text == null) return "";

        // 1. 解码 Unicode 转义
        text = decodeUnicodeEscapes(text);

        // 2. 将紧邻真实换行的字面量 \\ n 还原（AI 双转义的典型特征）
        //    避免误伤独立的、有意义的字面量反斜杠 n
        text = fixStrayLiteralNewlines(text);

        // 3. 规范化：连续 3 个以上换行压缩为 2 个
        text = text.replaceAll("\n{3,}", "\n\n");

        // 4. 去除首尾多余空白，但不破坏内部缩进
        text = text.trim();

        return text;
    }

    /**
     * 将文本中的 \\ uXXXX 转义序列解码为对应 Unicode 字符。
     */
    private static String decodeUnicodeEscapes(String input) {
        Matcher m = UNICODE_ESCAPE.matcher(input);
        if (!m.find()) return input;
        m.reset();
        StringBuilder sb = new StringBuilder(input.length());
        while (m.find()) {
            int codePoint = Integer.parseInt(m.group(1), 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) codePoint)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 将 \"真实换行 + 字面量 \\ n\" 或 \"字面量 \\ n + 真实换行\" 模式中的
     * 字面量 \\ n 替换为真实换行。只处理与真实换行相邻的情况，避免误伤。
     */
    private static String fixStrayLiteralNewlines(String input) {
        // 先把 "\\n" 紧邻真实 \n 的情况修复：
        // "\n\\n" → "\n\n" 或 "\\n\n" → "\n\n"
        String result = input.replace("\n\\n", "\n\n")
                             .replace("\\n\n", "\n\n");
        // 开头/结尾孤立的 "\\n" → "\n"
        if (result.startsWith("\\n")) result = "\n" + result.substring(2);
        if (result.endsWith("\\n"))   result = result.substring(0, result.length() - 2) + "\n";
        return result;
    }
}
