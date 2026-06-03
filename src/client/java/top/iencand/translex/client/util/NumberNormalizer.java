package top.iencand.translex.client.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts and normalizes digit sequences for cache-key deduplication.
 * Replace all digit runs with a single {num} placeholder so that
 * "Deals 100 damage" and "Deals 50 damage" share the same cache key.
 */
public class NumberNormalizer {
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d+");

    private final List<String> originalNumbers = new ArrayList<>();

    /**
     * Replace all digit sequences with {@code {num}} and record the originals.
     */
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
     * Restore the original digit sequences back into the translated text.
     * Walks through the translated string and replaces each {@code {num}}
     * with the corresponding recorded original number.
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

    /** Static convenience: does this text contain any digit? */
    public static boolean containsDigit(String text) {
        return text != null && DIGIT_PATTERN.matcher(text).find();
    }
}
