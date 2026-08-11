package top.iencand.translex.client.translate.model;

import top.iencand.translex.client.config.ModConfig;
import tsp.TspEncoder;

import java.util.Locale;

/**
 * Registry for translation wire formats.
 *
 * <p>{@code TSP} is the canonical Full mode identifier and {@code HYBRID} is
 * the canonical TSP Hybrid mode identifier. The display-oriented
 * {@code TSP-FULL} and historical {@code TSP-HYBRID} spellings remain accepted
 * as input aliases.</p>
 */
public final class TranslationFormatRegistry {

    private static final String LEGACY_HYBRID_ID = "TSP-HYBRID";
    private static final String DISPLAY_FULL_ID = "TSP-FULL";

    private TranslationFormatRegistry() {
    }

    public static TranslationFormat forId(String id) {
        String canonical = canonicalId(id);
        if (!usesTspSyntax(canonical)) {
            return new SnFormat();
        }
        return forId(canonical, ModConfig.get().tspChecksum);
    }

    /**
     * Deterministic construction entry point for tests and callers that already
     * own a configuration snapshot.
     */
    public static TranslationFormat forId(String id, boolean tspChecksum) {
        return switch (canonicalId(id)) {
            case "TSP" -> new TspFormat(TspEncoder.Policy.FULL, tspChecksum);
            case "HYBRID" -> new TspFormat(TspEncoder.Policy.HYBRID, tspChecksum);
            default -> new SnFormat();
        };
    }

    public static String canonicalId(String id) {
        String key = id == null ? "SN" : id.trim().toUpperCase(Locale.ROOT);
        if (DISPLAY_FULL_ID.equals(key)) return "TSP";
        if (LEGACY_HYBRID_ID.equals(key)) return "HYBRID";
        return key;
    }

    public static boolean usesTspSyntax(String id) {
        String canonical = canonicalId(id);
        return "TSP".equals(canonical) || "HYBRID".equals(canonical);
    }
}
