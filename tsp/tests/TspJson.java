package tsp.tests;

import java.util.*;

/**
 * 极简 JSON 解析器（无外部依赖），供 lore 数据加载用。
 * 支持 object/array/string/bool/null/number。足够解析 harvest 的 segment JSON。
 */
public final class TspJson {
    private final String s;
    private int pos;

    private TspJson(String s) { this.s = s; }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        return (Map<String, Object>) new TspJson(s).parseValue();
    }

    private Object parseValue() {
        skipWs();
        char c = s.charAt(pos);
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBool();
            case 'n' -> { pos += 4; yield null; }
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> m = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (s.charAt(pos) == '}') { pos++; return m; }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            pos++; // :
            Object val = parseValue();
            m.put(key, val);
            skipWs();
            if (s.charAt(pos) == ',') { pos++; continue; }
            if (s.charAt(pos) == '}') { pos++; break; }
        }
        return m;
    }

    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWs();
        if (s.charAt(pos) == ']') { pos++; return list; }
        while (true) {
            list.add(parseValue());
            skipWs();
            if (s.charAt(pos) == ',') { pos++; continue; }
            if (s.charAt(pos) == ']') { pos++; break; }
        }
        return list;
    }

    private String parseString() {
        StringBuilder sb = new StringBuilder();
        pos++; // "
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '"') break;
            if (c == '\\') {
                char n = s.charAt(pos++);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }

    private Boolean parseBool() {
        if (s.charAt(pos) == 't') { pos += 4; return true; }
        pos += 5; return false;
    }

    private Double parseNumber() {
        int start = pos;
        while (pos < s.length() && "-+0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
        return Double.parseDouble(s.substring(start, pos));
    }

    private void skipWs() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }
}
