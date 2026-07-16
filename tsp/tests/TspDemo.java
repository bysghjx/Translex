package tsp.tests;

import tsp.*;
import java.util.List;

/**
 * Demonstrates TSP output format — with and without auto-default-style detection.
 * Run: java tsp.tests.TspDemo
 */
public final class TspDemo {

    private static final Style GRAY  = Style.of("#AAAAAA");
    private static final Style GREEN = Style.of("#55FF55");
    private static final Style AQUA  = Style.of("#55FFFF");
    private static final Style GOLD  = Style.of("#FFAA00");
    private static final Style WHITE = Style.of("#FFFFFF");
    private static final Style RED   = Style.of("#FF5555");

    public static void main(String[] args) {
        List<StyledSegment> statLine = List.of(
                seg(GRAY, "Damage: "), seg(RED, "+150"), seg(GRAY, " ("), seg(GREEN, "+30%"), seg(GRAY, ")"));

        List<StyledSegment> woolyCoat = List.of(
                seg(GOLD, "Wooly Coat"),
                seg(GRAY, "\nGain a "), seg(GREEN, "56%"), seg(GRAY, " chance for mobs to not"),
                seg(GRAY, "\ninflict "), seg(AQUA, "Cold"), seg(GRAY, " when damaging you in"),
                seg(GRAY, "\nthe "), seg(AQUA, "Glacite Mineshafts"), seg(GRAY, "."));

        List<StyledSegment> mammothFull = List.of(
                seg(GRAY, "[Lvl 56] "), seg(GOLD, "Mammoth"),
                seg(GRAY, "\nCombat Pet"),
                seg(GRAY, "\nCold Resistance: "), seg(WHITE, "{0}"),
                seg(GOLD, "\nTusk Luck"),
                seg(GRAY, "\nGain "), seg(GREEN, "+0.28 Magic Find "), seg(GRAY, "for every"),
                seg(GRAY, "\n100 "), seg(AQUA, "Mining Fortune"), seg(GRAY, ", doubled in the"),
                seg(GRAY, "\nGlacite Tunnels "), seg(GRAY, "and "), seg(AQUA, "Glacite"),
                seg(GRAY, "\nMineshafts"), seg(GRAY, "."));

        System.out.println("=== TSP Output: WITHOUT vs WITH auto-default-style ===\n");
        System.out.println("(default = most frequent color -> emitted as plain text, no token)\n");

        compare("Stat line: Damage +150 (+30%)", statLine);
        compare("Skill + paragraph: Wooly Coat", woolyCoat);
        compare("Full Mammoth: name + stat + 2 paragraphs", mammothFull);
    }

    private static void compare(String title, List<StyledSegment> input) {
        Style def = TspEncoder.detectDefaultStyle(input);

        // Without default
        TspRegistry regNo = new TspRegistry();
        TspEncoder encNo = new TspEncoder(regNo);
        String tspNo = encNo.encode(input);

        // With auto-default
        TspRegistry regAuto = new TspRegistry();
        TspEncoder encAuto = TspEncoder.withAutoDefault(regAuto, input);
        String tspAuto = encAuto.encode(input);

        System.out.println("━━━ " + title + " ━━━");
        System.out.println("Detected default: " + (def != null ? def.colorHex() : "none") + " (most frequent)");
        System.out.println("Highlight colors: " + regAuto.size() + " IDs");
        System.out.println();
        System.out.println("OLD (all tokens): " + tspNo);
        System.out.println("NEW (no default): " + tspAuto);
        System.out.println();
    }

    private static StyledSegment seg(Style s, String text) {
        return StyledSegment.styled(text, s);
    }
}
