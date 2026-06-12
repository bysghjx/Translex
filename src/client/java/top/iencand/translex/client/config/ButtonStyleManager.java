package top.iencand.translex.client.config;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 管理按钮显示样式与是否启用按钮的会话级覆盖。重启后恢复为 config.toml 中的默认值。
 */
public class ButtonStyleManager {
    private static final AtomicReference<String> sessionOverride = new AtomicReference<>(null);
    private static final AtomicReference<Boolean> enabledOverride = new AtomicReference<>(null);

    public static boolean isCompact() {
        String override = sessionOverride.get();
        if (override != null) return "COMPACT".equals(override);
        return "COMPACT".equals(ModConfig.get().buttonStyle);
    }

    /**
     * 切换按钮样式。
     * @return 切换后的样式名称（"NORMAL" 或 "COMPACT"）
     */
    public static String toggle() {
        String current = isCompact() ? "COMPACT" : "NORMAL";
        String next = "COMPACT".equals(current) ? "NORMAL" : "COMPACT";
        sessionOverride.set(next);
        return next;
    }

    /** 返回当前样式的名称 */
    public static String currentStyleName() {
        return isCompact() ? "COMPACT" : "NORMAL";
    }

    /**
     * 检查翻译按钮是否启用。
     * 会话级覆盖优先，若未设置则回退到 config.toml 中的 enableTranslateButton。
     */
    public static boolean isButtonEnabled() {
        Boolean override = enabledOverride.get();
        if (override != null) return override;
        return ModConfig.get().enableTranslateButton;
    }

    /**
     * 切换翻译按钮的启用/禁用状态。
     * @return 切换后是否启用
     */
    public static boolean toggleButtonEnabled() {
        boolean current = isButtonEnabled();
        enabledOverride.set(!current);
        return !current;
    }
}
