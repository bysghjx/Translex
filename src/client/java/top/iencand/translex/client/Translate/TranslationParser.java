package top.iencand.translex.client.Translate;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import top.iencand.translex.client.util.I18nHelper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 专门负责清洗和解析 AI 返回的原始字符串。
 */
public class TranslationParser {
    private static final Gson GSON = new Gson();
    // 匹配 JSON 数组的正则表达式，能够抓取被文字包围的 [...]
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[\\s*\".*\"\\s*\\]", Pattern.DOTALL);

    /**
     * 将 AI 返回的杂乱字符串解析为干净的字符串数组。
     * @param rawResponse AI 的原始输入
     * @param expectedSize 期望得到的译文数量（用于校验）
     * @return 解析后的数组
     * @throws ParseException 当解析完全失败或数量不匹配时抛出自定义异常
     */
    public String[] parse(String rawResponse, int expectedSize) throws ParseException {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new ParseException(I18nHelper.translate("translex.error.parse.empty"));
        }

        // 1. 预处理：移除所有 Minecraft 颜色代码和 Markdown 块标记
        String cleaned = rawResponse
                .replaceAll("§[0-9a-fk-or]", "") // 移除 §c 等颜色代码
                .replaceAll("```json|```", "")   // 移除 Markdown 块
                .trim();

        // 2. 尝试提取 JSON 数组部分
        String jsonPart = extractJsonArray(cleaned);

        try {
            // 3. 执行反序列化
            String[] results = GSON.fromJson(jsonPart, String[].class);

            // 4. 数量校验
            if (results == null) {
                throw new ParseException(I18nHelper.translate("translex.error.parse.null"));
            }
            if (results.length != expectedSize) {
                throw new ParseException(I18nHelper.translate("translex.error.parse.mismatch", expectedSize, results.length));
            }

            return results;

        } catch (JsonSyntaxException e) {
            // 如果解析失败，可能是 AI 返回了非 JSON 纯文本，尝试作为单条处理（如果预期就是1条）
            if (expectedSize == 1) {
                return new String[]{cleaned};
            }
            throw new ParseException(I18nHelper.translate("translex.error.parse.json_format"), e);
        }
    }

    /**
     * 内部方法：利用正则或索引寻找最外层的 [ ] 括号内容
     */
    private String extractJsonArray(String input) {
        int first = input.indexOf("[");
        int last = input.lastIndexOf("]");

        if (first != -1 && last != -1 && last > first) {
            return input.substring(first, last + 1);
        }
        return input; // 找不到括号则返回原样，交给 GSON 报错或兜底
    }

    /**
     * 自定义解析异常，方便 Manager 捕获并显示给用户
     */
    public static class ParseException extends Exception {
        public ParseException(String message) { super(message); }
        public ParseException(String message, Throwable cause) { super(message, cause); }
    }
}