package top.iencand.translex.client.translate.pipeline;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 物品 tooltip 段落分组器：识别跨行句子，合并成段落。
 *
 * <p>借鉴 Translate_AllinOne 的思路：连续的、无冒号的描述行（如 {@code Gain ... for every}
 * 跨行能力描述），且前行不以句末标点结尾，合并为一个段落整段翻译，给 AI 完整上下文，
 * 避免逐行翻译时 AI 脑补（如凭空加"每级"）。</p>
 *
 * <p>规则：
 * <ul>
 *   <li>第 0 行（物品名）强制独立</li>
 *   <li>含 {@code :} 或 {@code ：} 的行（stat 行，如 {@code Damage: 100}）独立</li>
 *   <li>无字母的行独立</li>
 *   <li>项目符号 / 大写标题行独立</li>
 *   <li>其余连续行，若前行不以 {@code . ! ? 。 ！ ？} 结尾，合并成段落</li>
 *   <li>段落至少 2 行，否则按单行处理</li>
 * </ul>
 * </p>
 */
public final class ParagraphGrouper {
    private ParagraphGrouper() {}

    private static final Pattern HAS_LETTER = Pattern.compile("[a-zA-Z]");
    private static final Pattern TERMINAL_PUNCT = Pattern.compile("[.!?。！？]\\s*$");
    private static final Pattern BULLET_OR_HEADER = Pattern.compile(
            "^\\s*(\\d+[.)]\\s|[-*•]\\s|[A-Z][A-Z ]{2,})");

    /** 一个分组：标记行范围 [startIndex, endIndexExclusive) 及是否段落。 */
    public record Group(int startIndex, int endIndexExclusive, boolean isParagraph) {
        public int lineCount() { return endIndexExclusive - startIndex; }
    }

    /** 将 tooltip 行分组。返回 Group 列表，覆盖所有行（无遗漏、无重叠）。 */
    public static List<Group> group(List<Component> lines) {
        List<Group> groups = new ArrayList<>();
        if (lines == null || lines.isEmpty()) return groups;

        int i = 0;
        while (i < lines.size()) {
            // 第 0 行（物品名）强制独立
            if (i == 0) {
                groups.add(new Group(0, 1, false));
                i = 1;
                continue;
            }
            int paraEnd = findParagraphEnd(lines, i);
            if (paraEnd - i >= 2) {
                groups.add(new Group(i, paraEnd, true));
                i = paraEnd;
            } else {
                groups.add(new Group(i, i + 1, false));
                i++;
            }
        }
        return groups;
    }

    /** 从 start 开始找段落结束（exclusive）。start 行必须是 paragraph-like 才能开始段落。 */
    private static int findParagraphEnd(List<Component> lines, int start) {
        if (!isParagraphLike(lines.get(start))) return start + 1;
        int end = start + 1;
        while (end < lines.size()
                && isParagraphLike(lines.get(end))
                && !endsWithTerminalPunct(lines.get(end - 1))) {
            end++;
        }
        return end;
    }

    private static boolean isParagraphLike(Component line) {
        if (line == null) return false;
        String raw = line.getString();
        if (raw == null || raw.isBlank()) return false;
        if (raw.indexOf(':') >= 0 || raw.indexOf('：') >= 0) return false;   // stat 行
        if (!HAS_LETTER.matcher(raw).find()) return false;                   // 无英文
        if (BULLET_OR_HEADER.matcher(raw).find()) return false;              // 符号/标题
        return true;
    }

    private static boolean endsWithTerminalPunct(Component line) {
        if (line == null) return true;
        String raw = line.getString();
        return raw == null || raw.isBlank() || TERMINAL_PUNCT.matcher(raw).find();
    }
}
