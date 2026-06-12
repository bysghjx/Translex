package top.iencand.translex.client.translate;

import top.iencand.translex.client.util.NumberNormalizer;
import top.iencand.translex.client.web.ConsoleBroadcaster;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 文本预处理器，在发送到 AI 翻译前对原始文本进行预处理。
 *
 * <p>按换行符拆分文本，对每行应用词库替换，并将行分类为"已翻译"
 * （无英文字母或纯符号）和"待翻译"。这减少了 token 载荷，
 * 避免了重复翻译词库已覆盖的行。</p>
 *
 * <p>未翻译行中的数字在发送前会被规范化为 {@code {num}} 占位符，
 * 防止 AI 错误地修改数值。原始数字在翻译后通过 {@link NumberNormalizer} 恢复。</p>
 */
public class TranslationSplitter {

    /** 匹配仅由空白、连字符、星号、等号、下划线组成的行（纯分隔线） */
    private static final Pattern SYMBOL_ONLY = Pattern.compile("^[\\s\\-*=_]+$");

    /** 至少包含一个 ASCII 字母（需要 AI 翻译的标志） */
    private static final Pattern HAS_ENGLISH = Pattern.compile("[a-zA-Z]");

    public SplitResult split(String original, Function<String, String> glossaryApplier) {
        if (original == null || original.isEmpty()) {
            return new SplitResult("", List.of(), List.of(), new NumberNormalizer());
        }

        String[] lines = original.split("\n", -1);
        List<String> preTranslated = new ArrayList<>(lines.length);
        List<String> untranslatedLines = new ArrayList<>();

        for (String line : lines) {
            // Pure symbol / empty line → already fine
            if (line.isEmpty() || SYMBOL_ONLY.matcher(line).matches()) {
                preTranslated.add(line);
                continue;
            }

            // Apply glossary
            String glossed = glossaryApplier.apply(line);

            // Still has English letters?
            if (HAS_ENGLISH.matcher(glossed).find()) {
                // Keep the ORIGINAL (not glossed) for AI translation
                untranslatedLines.add(line);
                preTranslated.add(null); // placeholder
            } else {
                // Glossary fully translated this line
                preTranslated.add(glossed);
            }
        }

        String rawText = String.join("\n", untranslatedLines);
        NumberNormalizer normalizer = new NumberNormalizer();
        String untranslatedText = normalizer.normalize(rawText);
        SplitResult result = new SplitResult(untranslatedText, preTranslated, untranslatedLines, normalizer);

        int glossaried = (int) preTranslated.stream().filter(l -> l != null).count();
        ConsoleBroadcaster.broadcast("DEBUG",
                "Splitter: " + lines.length + " total lines → "
                + glossaried + " glossaried, " + untranslatedLines.size() + " need AI");

        return result;
    }

    /**
     * 将 AI 翻译结果合并回原始行顺序。
     *
     * @param split         来自 {@link #split} 的分词结果
     * @param translatedRaw AI 响应（以换行符分隔的翻译结果）
     * @return 完全合并的字符串，保留原始换行结构
     */
    public String merge(SplitResult split, String translatedRaw) {
        if (translatedRaw == null) return String.join("\n", split.preTranslated());

        String[] aiLines = translatedRaw.split("\n", -1);
        List<String> result = new ArrayList<>(split.preTranslated());
        int aiIdx = 0;

        for (int i = 0; i < result.size(); i++) {
            if (result.get(i) == null) { // placeholder
                if (aiIdx < aiLines.length) {
                    result.set(i, aiLines[aiIdx]);
                    aiIdx++;
                } else {
                    // Missing translation — keep original line from untranslated list
                    int placeholderIdx = countNullsUpTo(result, i);
                    if (placeholderIdx < split.untranslatedLines().size()) {
                        result.set(i, split.untranslatedLines().get(placeholderIdx));
                    }
                }
            }
        }

        return String.join("\n", result);
    }

    private static int countNullsUpTo(List<String> list, int upTo) {
        int count = 0;
        for (int i = 0; i < upTo; i++) {
            if (list.get(i) == null) count++;
        }
        return count;
    }

    /**
     * 分词操作的结果。
     * @param untranslatedText  发送给 AI 的文本（换行连接，数字已规范化为 {num}）
     * @param preTranslated     每行的列表；null 条目表示需要 AI 处理的占位符
     * @param untranslatedLines 需要翻译的原始英文行（用于合并时的回退）
     * @param normalizer        通过 {@link NumberNormalizer#restore} 恢复 AI 响应中的原始数字
     */
    public record SplitResult(
            String untranslatedText,
            List<String> preTranslated,
            List<String> untranslatedLines,
            NumberNormalizer normalizer
    ) {
        public boolean needsTranslation() {
            return !untranslatedLines.isEmpty();
        }
    }
}
