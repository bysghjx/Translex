package top.iencand.translex.client.util;

import java.util.regex.Pattern;

/**
 * 坐标指纹生成器，用于将消息中的坐标信息统一替换为占位符。
 * 用于聊天消息折叠功能，使不同坐标的同类消息能被合并。
 */
public class CoordinateFingerprint {
    /** 匹配 "x: 123 y: 456 z: 789" 格式的坐标 */
    private static final Pattern XYZ_PATTERN = Pattern.compile("\\bx:\\s*-?\\d+\\s+y:\\s*-?\\d+\\s+z:\\s*-?\\d+\\b");
    /** 匹配 "123 456 789" 格式的纯数字坐标 */
    private static final Pattern PURE_PATTERN = Pattern.compile("\\b-?\\d+\\s+-?\\d+\\s+-?\\d+\\b");

    /**
     * 生成坐标指纹。
     * @param input 输入文本
     * @return 若包含坐标则返回替换后的指纹，否则返回 null
     */
    public static String getFingerprint(String input) {
        if (XYZ_PATTERN.matcher(input).find()) {
            return XYZ_PATTERN.matcher(input).replaceAll("COORD_XYZ");
        }
        if (PURE_PATTERN.matcher(input).find()) {
            return PURE_PATTERN.matcher(input).replaceAll("COORD_PURE");
        }
        return null; // 如果不是坐标消息，返回 null
    }
}