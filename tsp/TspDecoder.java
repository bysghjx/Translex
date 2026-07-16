package tsp;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts parsed TSP elements back into styled text segments.
 *
 * <p>Rules (from spec §6):
 * <ul>
 *   <li>{@link TspToken}: looks up the style by ID in the {@link TspRegistry};
 *       unknown IDs fall back to {@link Style#EMPTY}.</li>
 *   <li>{@link TspText}: emitted as plain text (Style.EMPTY).</li>
 * </ul>
 *
 * <p>Malformed tokens have already been converted to {@link TspText} by the parser,
 * so the decoder does not need separate recovery logic.</p>
 */
public final class TspDecoder {

    private final TspRegistry registry;

    public TspDecoder(TspRegistry registry) {
        this.registry = registry;
    }

    /**
     * Decode a parse result into styled segments.
     *
     * @param parseResult the output of {@link TspParser#parse(String)}
     * @return ordered list of styled segments
     */
    public List<StyledSegment> decode(TspParser.ParseResult parseResult) {
        List<StyledSegment> segments = new ArrayList<>();
        for (TspElement element : parseResult.elements()) {
            switch (element) {
                case TspToken token -> {
                    Style style = registry.getStyle(token.id());
                    segments.add(new StyledSegment(token.text(), style));
                }
                case TspText text -> {
                    segments.add(StyledSegment.plain(text.text()));
                }
            }
        }
        return segments;
    }

    /**
     * Convenience: parse + decode in one call.
     */
    public List<StyledSegment> decodeString(String tspString) {
        TspParser parser = new TspParser();
        TspParser.ParseResult result = parser.parse(tspString);
        return decode(result);
    }
}
