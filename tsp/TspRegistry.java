package tsp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local Style ↔ ID mapping for a single translation request.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Assign numeric IDs to styles on first encounter.</li>
 *   <li>Deduplicate identical styles (same Style -> same ID).</li>
 *   <li>Preserve insertion order (deterministic output).</li>
 *   <li>Produce a {@link #fingerprint()} for cache integrity checks.</li>
 * </ul>
 *
 * <p>NOT thread-safe. One registry per request.</p>
 */
public final class TspRegistry {

    private final Map<Integer, Style> idToStyle = new LinkedHashMap<>();
    private final Map<Style, Integer> styleToId = new LinkedHashMap<>();
    private int nextId = 0;

    /**
     * Register a style and return its ID.
     * If the style is {@link Style#EMPTY} or null, returns -1 (no token needed).
     * If the style was already registered, returns the existing ID.
     */
    public int register(Style style) {
        if (style == null || style.isEmpty()) {
            return -1;
        }
        Integer existing = styleToId.get(style);
        if (existing != null) {
            return existing;
        }
        int id = nextId++;
        idToStyle.put(id, style);
        styleToId.put(style, id);
        return id;
    }

    /**
     * Look up the style for a given ID.
     * Returns {@link Style#EMPTY} for unknown IDs.
     */
    public Style getStyle(int id) {
        Style style = idToStyle.get(id);
        return style != null ? style : Style.EMPTY;
    }

    /** Number of registered styles. */
    public int size() {
        return idToStyle.size();
    }

    /** Returns true if no styles have been registered. */
    public boolean isEmpty() {
        return idToStyle.isEmpty();
    }

    /**
     * Compute a fingerprint of the registry for cache integrity checks.
     *
     * <p>Encodes the full {@code ID -> Style} mapping in ID order (which reflects
     * the order colors first appear in the source text). Used to detect when a
     * cached TSP value's registry no longer matches the current source's color
     * structure -- e.g. Hypixel changes lore colors, or a dyed item variant --
     * so the cache can be invalidated instead of decoding to wrong colors.</p>
     *
     * <p>Algorithm: concatenate {@code "ID:colorHex"} for each entry in ID order,
     * then SHA-256 (first 16 hex chars). Same color structure -> same fingerprint;
     * color order/set changes -> different fingerprint.</p>
     *
     * <p>Phase 1 (Draft 1) only encodes color. When Style gains bold/italic/etc,
     * extend the encoded form to include them so the fingerprint stays a faithful
     * digest of the full Style.</p>
     *
     * @return 16-char hex fingerprint, or empty string if registry is empty
     */
    public String fingerprint() {
        if (idToStyle.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Style> e : idToStyle.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            Style s = e.getValue();
            sb.append(e.getKey()).append(':').append(s.isEmpty() ? "" : s.colorHex());
        }
        return sha256Hex16(sb.toString());
    }

    /** SHA-256 of the input, first 16 hex chars (64-bit digest, collision-negligible). */
    private static String sha256Hex16(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    @Override
    public String toString() {
        return "TspRegistry" + idToStyle;
    }
}
