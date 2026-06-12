package top.iencand.translex.client.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数字提取与规范化工具，用于缓存键去重。
 * 将所有数字序列替换为 {num} 占位符，使得
 * "造成 100 点伤害"和"造成 50 点伤害"共享同一个缓存键。
 */
public class NumberNormalizer {
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d+");

    private final List<String> originalNumbers = new ArrayList<>();

    /** 将所有数字序列替换为 {@code {num}} 并记录原始数字 */
    public String normalize(String text) {
        if (text == null) return "";
        originalNumbers.clear();
        Matcher m = DIGIT_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            originalNumbers.add(m.group());
            m.appendReplacement(sb, "{num}");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 将原始数字序列恢复回翻译文本中。
     * 遍历翻译后的字符串，将每个 {@code {num}} 替换为对应的原始数字。
     */
    public String restore(String translated) {
        if (translated == null || originalNumbers.isEmpty()) return translated;
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        int numIdx = 0;
        int pos;
        while ((pos = translated.indexOf("{num}", idx)) >= 0 && numIdx < originalNumbers.size()) {
            sb.append(translated, idx, pos);
            sb.append(originalNumbers.get(numIdx));
            numIdx++;
            idx = pos + "{num}".length();
        }
        sb.append(translated, idx, translated.length());
        return sb.toString();
    }

    /** 静态便捷方法：文本是否包含任何数字？ */
    public static boolean containsDigit(String text) {
        return text != null && DIGIT_PATTERN.matcher(text).find();
    }
}
