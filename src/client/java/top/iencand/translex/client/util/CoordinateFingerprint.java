package top.iencand.translex.client.util;

import java.util.regex.Pattern;

public class CoordinateFingerprint {
    private static final Pattern XYZ_PATTERN = Pattern.compile("\\bx:\\s*-?\\d+\\s+y:\\s*-?\\d+\\s+z:\\s*-?\\d+\\b");
    private static final Pattern PURE_PATTERN = Pattern.compile("\\b-?\\d+\\s+-?\\d+\\s+-?\\d+\\b");

    public static String getFingerprint(String input) {
        if (XYZ_PATTERN.matcher(input).find()) {
            return XYZ_PATTERN.matcher(input).replaceAll("COORD_XYZ");
        }
        if (PURE_PATTERN.matcher(input).find()) {
            return PURE_PATTERN.matcher(input).replaceAll("COORD_PURE");
        }
        return null; // 如果不是坐标，返回 null
    }
}