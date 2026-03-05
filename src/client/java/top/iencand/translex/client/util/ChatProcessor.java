package top.iencand.translex.client.util;

import net.minecraft.text.Text;
import java.util.regex.Pattern;

public class ChatProcessor {
    private static final Pattern COLOR_PATTERN = Pattern.compile("§[0-9a-fk-orx]");
    private static final Pattern COUNT_SUFFIX_PATTERN = Pattern.compile("\\s*\\(x\\d+\\)$");

    // 严格坐标模式：必须是独立片段，防止匹配 xxxxx123
    private static final Pattern COORD_XYZ_PATTERN = Pattern.compile("\\bx:\\s*-?\\d+\\s+y:\\s*-?\\d+\\s+z:\\s*-?\\d+\\b");
    private static final Pattern COORD_PURE_PATTERN = Pattern.compile("\\b-?\\d+\\s+-?\\d+\\s+-?\\d+\\b");

    /**
     * 生成用于对比的消息“指纹”
     * 逻辑：剥离 [翻译] 按钮 -> 剥离颜色 -> 模板化坐标
     */
    public static String getFoldFingerprint(Text text) {
        if (text == null) return "";
        String content = text.getString().trim();

        // 1. 兼容翻译 ID 系统：剥离前面的 "[翻译] " 前缀
        // 这样无论 ID 是多少，指纹都只关注后面的正文
        if (content.startsWith("[翻译] ")) {
            content = content.substring(5);
        }

        // 2. 基础净化：去掉颜色和现有的折叠计数
        content = COLOR_PATTERN.matcher(content).replaceAll("");
        content = COUNT_SUFFIX_PATTERN.matcher(content).replaceAll("");

        // 3. 严格坐标模板化
        // 只有符合坐标格式的片段会被替换，其他数字（如 ID、等级）保持不变
        if (COORD_XYZ_PATTERN.matcher(content).find()) {
            content = COORD_XYZ_PATTERN.matcher(content).replaceAll("COORD_XYZ");
        } else if (COORD_PURE_PATTERN.matcher(content).find()) {
            content = COORD_PURE_PATTERN.matcher(content).replaceAll("COORD_PURE");
        } else {
            // 如果不是坐标消息，返回原文本进行精确折叠（或者返回空表示不折叠）
            return content.isEmpty() ? "" : content;
        }

        return content.trim();
    }

    public static boolean shouldProcess(Text text) {
        String s = text.getString();
        return s.contains("[翻译]") && (COORD_XYZ_PATTERN.matcher(s).find() || COORD_PURE_PATTERN.matcher(s).find());
    }
}