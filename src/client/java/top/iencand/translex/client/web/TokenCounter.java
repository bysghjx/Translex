package top.iencand.translex.client.web;

/**
 * 本地 Token 估算器。基于字符/词语启发式规则近似计算，
 * 无需外部 NLP 库，误差通常在 ±15% 以内，足够用于监控看板。
 *
 * <p>规则：
 * <ul>
 *   <li>CJK / 日韩文字 ≈ 1.5 token/字符</li>
 *   <li>其余文字按空格分词 ≈ 1.3 token/词</li>
 * </ul>
 */
public final class TokenCounter {

    private TokenCounter() {}

    /**
     * 估算给定文本的 token 数。
     * @param text 输入文本，可为 null
     * @return 估算 token 数，null / 空字符串返回 0
     */
    public static long estimate(String text) {
        if (text == null || text.isEmpty()) return 0;

        int cjkChars = 0;
        String[] words = text.split("\\s+");
        int nonCjkWordCount = 0;

        for (String word : words) {
            if (word.isEmpty()) continue;
            if (isAllCJK(word)) {
                cjkChars += word.length();
            } else {
                nonCjkWordCount++;
                for (int i = 0; i < word.length(); i++) {
                    if (isCJK(word.charAt(i))) cjkChars++;
                }
            }
        }

        return Math.round(cjkChars * 1.5 + nonCjkWordCount * 1.3);
    }

    private static boolean isCJK(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT
            || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
            || block == Character.UnicodeBlock.HIRAGANA
            || block == Character.UnicodeBlock.KATAKANA
            || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
            || block == Character.UnicodeBlock.HANGUL_SYLLABLES
            || block == Character.UnicodeBlock.HANGUL_JAMO
            || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
    }

    private static boolean isAllCJK(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isCJK(s.charAt(i))) return false;
        }
        return s.length() > 0;
    }
}
