package top.iencand.translex.client.util;

import net.minecraft.text.Text;
import java.util.regex.Pattern;

/**
 * 聊天消息处理器，负责生成消息的折叠指纹。
 * 支持坐标消息和普通文本消息两种指纹策略。
 */
public class ChatProcessor {
    /** 匹配 Minecraft 颜色代码 */
    private static final Pattern COLOR_PATTERN = Pattern.compile("§[0-9a-fk-orx]");
    /** 匹配折叠计数后缀，如 "(x3)" */
    private static final Pattern COUNT_SUFFIX_PATTERN = Pattern.compile("\\s*\\(x\\d+\\)$");
    /** 匹配翻译按钮前缀，如 "[翻译] "、"[Translate] "、"[T] " */
    private static final Pattern BUTTON_PREFIX_PATTERN = Pattern.compile("^\\[(翻译|Translate|T)\\]\\s*");

    /**
     * 生成用于消息折叠的指纹。
     * 先剥离按钮前缀和颜色码，然后根据是否包含坐标选择不同的指纹策略。
     */
    public static String getFoldFingerprint(Text text) {
        if (text == null) return "";
        String s = text.getString().trim();

        // 1. 剥离按钮前缀："[翻译] "、"[Translate] "、"[T] "
        s = BUTTON_PREFIX_PATTERN.matcher(s).replaceFirst("");

        // 2. 基础净化（去除颜色码和现有的 xN 计数后缀）
        s = COLOR_PATTERN.matcher(s).replaceAll("");
        s = COUNT_SUFFIX_PATTERN.matcher(s).replaceAll("");

        // 3. 根据内容选择指纹策略
        String coordFp = CoordinateFingerprint.getFingerprint(s);
        if (coordFp != null) {
            return "COORD_" + coordFp;
        }

        return "TEXT_" + TextFingerprint.getFingerprint(s);
    }

    /** 判断消息是否应该被折叠处理（必须包含翻译按钮标记） */
    public static boolean shouldProcess(Text text) {
        if (text == null) return false;
        String s = text.getString();
        return s.contains("[翻译]") || s.contains("[Translate]") || s.contains("[T]");
    }
}