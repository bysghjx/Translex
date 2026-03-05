package top.iencand.translex.client.util;

import net.minecraft.client.resource.language.I18n;

public class I18nHelper {
    public static String translate(String key, Object... args) {
        return I18n.translate(key, args);
    }

    public static String getPrefixed(String key, Object... args) {
        return translate("translex.prefix") + translate(key, args);
    }
}