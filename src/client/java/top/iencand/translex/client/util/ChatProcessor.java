package top.iencand.translex.client.util;

import net.minecraft.text.Text;
import java.util.regex.Pattern;

public class ChatProcessor {
    private static final Pattern COLOR_PATTERN = Pattern.compile("§[0-9a-fk-orx]");
    private static final Pattern COUNT_SUFFIX_PATTERN = Pattern.compile("\\s*\\(x\\d+\\)$");

    private static final Pattern BUTTON_PREFIX_PATTERN = Pattern.compile("^\\[(翻译|Translate|T)\\]\\s*");

    public static String getFoldFingerprint(Text text) {
        if (text == null) return "";
        String s = text.getString().trim();

        // 1. 剥离按钮前缀: "[翻译] ", "[Translate] ", "[T] "
        s = BUTTON_PREFIX_PATTERN.matcher(s).replaceFirst("");

        // 2. 基础净化（颜色和现有的 xN）
        s = COLOR_PATTERN.matcher(s).replaceAll("");
        s = COUNT_SUFFIX_PATTERN.matcher(s).replaceAll("");

        // 3. 策略选择
        String coordFp = CoordinateFingerprint.getFingerprint(s);
        if (coordFp != null) {
            return "COORD_" + coordFp;
        }

        return "TEXT_" + TextFingerprint.getFingerprint(s);
    }

    public static boolean shouldProcess(Text text) {
        if (text == null) return false;
        String s = text.getString();
        return s.contains("[翻译]") || s.contains("[Translate]") || s.contains("[T]");
    }
}