package top.iencand.translex.client.util;

import net.minecraft.client.resources.language.I18n;

/**
 * 国际化（I18n）辅助工具类。
 * 封装 Minecraft 的 {@link I18n} 系统，提供带模组前缀的翻译方法。
 */
public class I18nHelper {
    /**
     * 翻译指定的语言键。
     * @param key  语言键（如 "translex.gui.translate_button"）
     * @param args 格式化参数
     * @return 翻译后的文本
     */
    public static String translate(String key, Object... args) {
        return I18n.get(key, args);
    }

    /**
     * 翻译带模组前缀的消息。
     * 前缀由 "translex.prefix" 键定义，例如 "[Translex] "。
     * @param key  语言键
     * @param args 格式化参数
     * @return 前缀 + 翻译文本
     */
    public static String getPrefixed(String key, Object... args) {
        return translate("translex.prefix") + translate(key, args);
    }
}