package top.iencand.translex.client.translate.model;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import tsp.StyledSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Protocol-neutral representation of one styled Minecraft text value.
 *
 * <p>The object owns the expensive style extraction and numeric placeholders.
 * Translation providers can therefore choose a wire format without duplicating
 * the Component traversal or changing how numbers are restored.</p>
 */
public final class StyledText {

    private static final Pattern STYLE_TAG = Pattern.compile(
            "<s(\\d+)>(.*?)</s\\1>", Pattern.DOTALL);
    private static final Pattern NUMBER = Pattern.compile(
            "[\\d.,+%kmb\\-s()]*\\d[\\d.,+%kmb\\-s()]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKER = Pattern.compile("\\{(\\d+)\\}");

    private final Component component;
    private final StyleCodec.ExtractionResult styles;
    private final String snTemplate;
    private final List<String> numericValues;
    private final List<StyledSegment> tspSegments;

    private StyledText(Component component, StyleCodec.ExtractionResult styles,
                       String snTemplate, List<String> numericValues,
                       List<StyledSegment> tspSegments) {
        this.component = component;
        this.styles = styles;
        this.snTemplate = snTemplate;
        this.numericValues = List.copyOf(numericValues);
        this.tspSegments = List.copyOf(tspSegments);
    }

    public static StyledText of(Component component) {
        Component source = component == null ? Component.empty() : component;
        StyleCodec.ExtractionResult extraction = StyleCodec.extract(source);
        String marked = extraction.markedText();
        StringBuilder sn = new StringBuilder();
        List<String> values = new ArrayList<>();
        List<StyledSegment> tsp = new ArrayList<>();
        Matcher matcher = STYLE_TAG.matcher(marked);
        int lastEnd = 0;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                String plain = marked.substring(lastEnd, matcher.start());
                sn.append(plain);
                tsp.add(StyledSegment.plain(plain));
            }

            int styleId = Integer.parseInt(matcher.group(1));
            String content = matcher.group(2);
            String protectedContent = content;
            if (NUMBER.matcher(content).matches()) {
                protectedContent = "{" + values.size() + "}";
                values.add(content);
            }

            sn.append("<s").append(styleId).append(">")
                    .append(protectedContent)
                    .append("</s").append(styleId).append(">");
            tsp.add(new StyledSegment(protectedContent,
                    toTspStyle(extraction.styleMap().get(styleId))));
            lastEnd = matcher.end();
        }

        if (lastEnd < marked.length()) {
            String plain = marked.substring(lastEnd);
            sn.append(plain);
            tsp.add(StyledSegment.plain(plain));
        }

        return new StyledText(source, extraction, sn.toString(), values, tsp);
    }

    public Component component() {
        return component;
    }

    public StyleCodec.ExtractionResult extractionResult() {
        return styles;
    }

    public String snTemplate() {
        return snTemplate;
    }

    public List<String> numericValues() {
        return numericValues;
    }

    public List<StyledSegment> tspSegments() {
        return tspSegments;
    }

    public String plainText() {
        return StyleCodec.stripTags(styles.markedText());
    }

    public Component renderSn(String template, boolean isParagraph) {
        String filled = restoreNumbers(template);
        filled = MARKER.matcher(filled).replaceAll("");
        if (isParagraph) {
            filled = normalizeParagraph(filled);
        }
        return StyleCodec.reapply(filled, styles.styleMap());
    }

    public Component renderTsp(List<StyledSegment> segments, boolean isParagraph) {
        MutableComponent result = Component.empty();
        for (StyledSegment segment : segments) {
            String text = restoreNumbers(segment.text());
            if (isParagraph) {
                text = normalizeParagraph(text);
            }
            result.append(Component.literal(text).setStyle(toMcStyle(segment.style())));
        }
        return result;
    }

    public String restoreNumbers(String template) {
        String result = template;
        for (int i = 0; i < numericValues.size(); i++) {
            result = result.replace("{" + i + "}", numericValues.get(i));
        }
        return result;
    }

    public static net.minecraft.network.chat.Style toMcStyle(tsp.Style style) {
        if (style == null || style.isEmpty()) {
            return net.minecraft.network.chat.Style.EMPTY;
        }
        try {
            int rgb = Integer.parseInt(style.colorHex().substring(1), 16);
            return net.minecraft.network.chat.Style.EMPTY.withColor(TextColor.fromRgb(rgb));
        } catch (Exception ignored) {
            return net.minecraft.network.chat.Style.EMPTY;
        }
    }

    private static tsp.Style toTspStyle(net.minecraft.network.chat.Style style) {
        if (style == null || style.getColor() == null) {
            return tsp.Style.EMPTY;
        }
        return tsp.Style.of(String.format("#%06X", style.getColor().getValue()));
    }

    private static String normalizeParagraph(String text) {
        return text.replace("\n", " ").replaceAll("\\s{2,}", " ");
    }
}
