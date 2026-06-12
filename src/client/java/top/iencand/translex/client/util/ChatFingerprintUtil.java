package top.iencand.translex.client.util;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * 聊天指纹工具，用于生成消息的"指纹"进行去重比较。
 * 专门处理包含坐标信息的消息，对坐标段进行统一替换以便折叠。
 */
public class ChatFingerprintUtil {
    private static final Pattern COLOR_PATTERN = Pattern.compile("§[0-9a-fk-orx]");
    private static final Pattern COUNT_SUFFIX_PATTERN = Pattern.compile("\\s*\\(x\\d+\\)$");

    // -------- 严格坐标匹配正则 --------
    // 模式 A: 匹配带前缀的坐标，例如 x: 123 y: 234 z: 456（允许空格）
    // 使用 \\b 确保前后是单词边界，避免匹配到 xxxxx123
    private static final Pattern COORD_XYZ_PATTERN = Pattern.compile("\\bx:\\s*-?\\d+\\s+y:\\s*-?\\d+\\s+z:\\s*-?\\d+\\b");

    // 模式 B: 匹配纯空格分隔的三个数字，例如 123 234 456
    // 同样使用 \\b 确保不会匹配到 abc123456
    private static final Pattern COORD_PURE_PATTERN = Pattern.compile("\\b-?\\d+\\s+-?\\d+\\s+-?\\d+\\b");

    /** 生成指纹，仅模糊化识别到的坐标片段，其他内容保持不动 */
    public static String getFingerprint(String input) {
        if (input == null || !input.contains("[翻译]")) return input;

        // 1. 去除颜色代码和自定义的 (xN) 计数后缀
        String s = COLOR_PATTERN.matcher(input).replaceAll("");
        s = COUNT_SUFFIX_PATTERN.matcher(s).replaceAll("");

        // 2. 识别并替换严格坐标
        // 不使用全局 replaceAll("-?\\d+", "#")，因为它会误伤所有数字
        // 先匹配 x: 1 y: 2 z: 3 格式
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

    /**
     * 判断消息是否需要坐标折叠处理。
     * 仅处理包含 [翻译] 标志且包含坐标模式的消息。
     */
    public static boolean isTargetMessage(String input) {
        if (input == null || !input.contains("[翻译]")) return false;
        return COORD_XYZ_PATTERN.matcher(input).find() || COORD_PURE_PATTERN.matcher(input).find();
    }
}