package top.iencand.translex.client.translate;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import top.iencand.translex.client.util.I18nHelper;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 解析器：清理并解析 AI 原始返回结果，将其转换为带索引的翻译条目映射表。
 * 支持 JSON 对象和 JSON 数组两种格式。
 */
public class TranslationParser {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    /** 防御性修复：处理 AI 返回中未加引号的数字键：{@code {0:"text"}} → {@code {"0":"text"}} */
    private static final Pattern UNQUOTED_KEY_RE = Pattern.compile("\\{(\\d+):");

    /**
     * 将 AI 响应解析为字典 {@code {"0":"text","1":"text"}}。
     * 应用防御性清理并对每个值执行 {@link TranslationPostProcessor#clean} 后处理。
     *
     * @param rawResponse  AI 原始响应文本
     * @param expectedSize 期望的条目数（仅用于 API 兼容，未实际使用）
     * @return 索引 → 翻译文本的映射表
     */
    public Map<Integer, String> parseDict(String rawResponse, int expectedSize) throws ParseException {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new ParseException(I18nHelper.translate("translex.error.parse.empty"));
        }

        // 1. Strip color codes and markdown fences
        String cleaned = rawResponse
                .replaceAll("§[0-9a-fk-or]", "")
                .replaceAll("```(?:json)?\\s*|```", "")
                .trim();

        // 2. Extract JSON object
        cleaned = extractJsonObject(cleaned);

        // 3. Defensive: fix unquoted numeric keys
        cleaned = UNQUOTED_KEY_RE.matcher(cleaned).replaceAll("{\"$1\":");

        // 4. Parse
        try {
            Map<String, String> stringMap = GSON.fromJson(cleaned, MAP_TYPE);
            Map<Integer, String> result = new LinkedHashMap<>();
            if (stringMap != null) {
                for (Map.Entry<String, String> e : stringMap.entrySet()) {
                    try {
                        result.put(Integer.parseInt(e.getKey()),
                                TranslationPostProcessor.clean(e.getValue()));
                    } catch (NumberFormatException ignored) {}
                }
            }
            return result;
        } catch (JsonSyntaxException e) {
            // Fallback: try as array
            try {
                String[] arr = GSON.fromJson(cleaned, String[].class);
                Map<Integer, String> result = new LinkedHashMap<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length; i++) {
                        result.put(i, TranslationPostProcessor.clean(arr[i]));
                    }
                }
                return result;
            } catch (JsonSyntaxException e2) {
                throw new ParseException(I18nHelper.translate("translex.error.parse.json_format"), e2);
            }
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static String extractJsonObject(String input) {
        int first = input.indexOf("{");
        int last = input.lastIndexOf("}");
        if (first != -1 && last != -1 && last > first) {
            return input.substring(first, last + 1);
        }
        return input;
    }

    // ---------------------------------------------------------------
    // Exception
    // ---------------------------------------------------------------

    public static class ParseException extends Exception {
        public ParseException(String message) { super(message); }
        public ParseException(String message, Throwable cause) { super(message, cause); }
    }
}
