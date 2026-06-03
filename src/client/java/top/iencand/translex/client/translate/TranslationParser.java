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
 * Cleans and parses AI raw responses into structured data.
 * Supports both JSON arrays (legacy) and JSON dicts (current).
 */
public class TranslationParser {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    /** Matches the innermost JSON array. */
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[\\s*\".*\"\\s*\\]", Pattern.DOTALL);

    /** Defensive: fix unquoted numeric keys: {@code {0:"text"}} → {@code {"0":"text"}}. */
    private static final Pattern UNQUOTED_KEY_RE = Pattern.compile("\\{(\\d+):");

    // ---------------------------------------------------------------
    // Legacy array parsing (for backwards compat)
    // ---------------------------------------------------------------

    public String[] parse(String rawResponse, int expectedSize) throws ParseException {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new ParseException(I18nHelper.translate("translex.error.parse.empty"));
        }

        String cleaned = rawResponse
                .replaceAll("§[0-9a-fk-or]", "")
                .replaceAll("```json|```", "")
                .trim();

        String jsonPart = extractJsonArray(cleaned);

        try {
            String[] results = GSON.fromJson(jsonPart, String[].class);
            if (results == null) {
                throw new ParseException(I18nHelper.translate("translex.error.parse.null"));
            }
            if (results.length != expectedSize) {
                throw new ParseException(I18nHelper.translate("translex.error.parse.mismatch", expectedSize, results.length));
            }
            return results;
        } catch (JsonSyntaxException e) {
            if (expectedSize == 1) {
                return new String[]{cleaned};
            }
            throw new ParseException(I18nHelper.translate("translex.error.parse.json_format"), e);
        }
    }

    // ---------------------------------------------------------------
    // Dict format parsing (current primary path)
    // ---------------------------------------------------------------

    /**
     * Parse the AI response as a dictionary {@code {"0":"text","1":"text"}}.
     * Applies defensive cleaning before parsing.
     *
     * @param rawResponse  raw AI response body
     * @param expectedSize hint for validation
     * @return map of index → translated text
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
                        result.put(Integer.parseInt(e.getKey()), e.getValue());
                    } catch (NumberFormatException ignored) {}
                }
            }
            return result;
        } catch (JsonSyntaxException e) {
            // Fallback: try as array
            try {
                String jsonPart = extractJsonArray(cleaned);
                String[] arr = GSON.fromJson(jsonPart, String[].class);
                Map<Integer, String> result = new LinkedHashMap<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length; i++) {
                        result.put(i, arr[i]);
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

    private String extractJsonArray(String input) {
        int first = input.indexOf("[");
        int last = input.lastIndexOf("]");
        if (first != -1 && last != -1 && last > first) {
            return input.substring(first, last + 1);
        }
        return input;
    }

    private String extractJsonObject(String input) {
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
