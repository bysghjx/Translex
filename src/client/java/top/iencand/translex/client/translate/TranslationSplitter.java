package top.iencand.translex.client.translate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Pre-processes raw text before sending to AI translation.
 *
 * <p>Splits by newline, applies the glossary to each line, and classifies
 * lines as already-translated (no English letters left, or pure symbols)
 * vs. still-needs-translation. This reduces the token payload and avoids
 * re-translating lines the glossary already covers.</p>
 */
public class TranslationSplitter {

    /** Matches lines composed only of whitespace, dashes, asterisks, equals, underscores. */
    private static final Pattern SYMBOL_ONLY = Pattern.compile("^[\\s\\-*=_]+$");

    /** At least one ASCII letter means the line still needs translation. */
    private static final Pattern HAS_ENGLISH = Pattern.compile("[a-zA-Z]");

    public SplitResult split(String original, Function<String, String> glossaryApplier) {
        if (original == null || original.isEmpty()) {
            return new SplitResult("", List.of(), List.of());
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

        String untranslatedText = String.join("\n", untranslatedLines);
        return new SplitResult(untranslatedText, preTranslated, untranslatedLines);
    }

    /**
     * Merge AI-translated lines back into the original order.
     *
     * @param split         the split result from {@link #split}
     * @param translatedRaw the AI response (newline-separated translations)
     * @return the fully merged string with original line breaks
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
     * Result of the split operation.
     * @param untranslatedText   newline-joined text to send to AI (may be empty)
     * @param preTranslated      per-line list; null entries are placeholders for AI results
     * @param untranslatedLines  original English lines that need translation
     */
    public record SplitResult(
            String untranslatedText,
            List<String> preTranslated,
            List<String> untranslatedLines
    ) {
        public boolean needsTranslation() {
            return !untranslatedLines.isEmpty();
        }
    }
}
