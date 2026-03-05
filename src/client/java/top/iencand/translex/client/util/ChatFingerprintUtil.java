package top.iencand.translex.client.util;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class ChatFingerprintUtil {
    private static final Pattern COLOR_PATTERN = Pattern.compile("§[0-9a-fk-orx]");
    private static final Pattern COUNT_SUFFIX_PATTERN = Pattern.compile("\\s*\\(x\\d+\\)$");

    // --- 严格坐标匹配正则 ---
    // 模式 A: 匹配带前缀的坐标，例如 x: 123 y: 234 z: 456 (允许空格)
    // 使用 \\b 确保前后是边界，避免匹配到 xxxxx123
    private static final Pattern COORD_XYZ_PATTERN = Pattern.compile("\\bx:\\s*-?\\d+\\s+y:\\s*-?\\d+\\s+z:\\s*-?\\d+\\b");

    // 模式 B: 匹配纯空格分隔的三个数字，例如 123 234 456
    // 同样使用 \\b 确保不会匹配到 abc123456
    private static final Pattern COORD_PURE_PATTERN = Pattern.compile("\\b-?\\d+\\s+-?\\d+\\s+-?\\d+\\b");

    /**
     * 生成指纹：只模糊化识别到的“坐标片段”，其他内容保持不动
     */
    public static String getFingerprint(String input) {
        if (input == null || !input.contains("[翻译]")) return input;

        // 1. 去掉颜色代码和我们自己加的 (xN) 后缀
        String s = COLOR_PATTERN.matcher(input).replaceAll("");
        s = COUNT_SUFFIX_PATTERN.matcher(s).replaceAll("");

        // 2. 识别并替换“严格坐标”
        // 我们不使用全局 replaceAll("-?\\d+", "#") 了，因为它会误伤所有数字

        // 先匹配 x: 1 y: 2 z: 3 这种格式
        Matcher xyzMatcher = COORD_XYZ_PATTERN.matcher(s);
        if (xyzMatcher.find()) {
            // 只把匹配到的坐标部分替换为统一占位符
            s = xyzMatcher.replaceAll("COORDS_XYZ_TEMPLATE");
        } else {
            // 如果没匹配到 XYZ，尝试匹配纯数字 123 234 456 格式
            Matcher pureMatcher = COORD_PURE_PATTERN.matcher(s);
            if (pureMatcher.find()) {
                s = pureMatcher.replaceAll("COORDS_PURE_TEMPLATE");
            }
        }

        // 3. 此时 s 中非坐标部分的数字（如等级 [232] 或 ID 里的数字）依然保留原样
        // 这样不同等级、不同 ID 的人发的消息，指纹就不一样，不会被错误折叠
        return s.trim();
    }

    public static boolean isTargetMessage(String input) {
        // 只有包含 [翻译] 且 包含任一坐标模式的消息才处理
        if (input == null || !input.contains("[翻译]")) return false;
        return COORD_XYZ_PATTERN.matcher(input).find() || COORD_PURE_PATTERN.matcher(input).find();
    }
}