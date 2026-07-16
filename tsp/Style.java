package tsp;

import java.util.Objects;

/**
 * Protocol-level style definition. Independent of Minecraft.
 *
 * <p>Draft 1 supports only color. Future versions may add bold / italic / events.</p>
 */
public final class Style {

    public static final Style EMPTY = new Style(null);

    private final String colorHex; // e.g. "#AAAAAA", or null for no color

    private Style(String colorHex) {
        this.colorHex = colorHex;
    }

    /** Create a Style with the given hex color (e.g. "#FF5555"). */
    public static Style of(String colorHex) {
        if (colorHex == null) return EMPTY;
        return new Style(colorHex);
    }

    public String colorHex() {
        return colorHex;
    }

    public boolean isEmpty() {
        return colorHex == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Style style)) return false;
        return Objects.equals(colorHex, style.colorHex);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(colorHex);
    }

    @Override
    public String toString() {
        return isEmpty() ? "Style.EMPTY" : "Style(" + colorHex + ")";
    }
}
