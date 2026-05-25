package top.iencand.translex.client.config;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 管理按钮显示样式的会话级覆盖。重启后恢复为 config.toml 中的默认值。
 */
public class ButtonStyleManager {
    private static final AtomicReference<String> sessionOverride = new AtomicReference<>(null);

    public static boolean isCompact() {
        String override = sessionOverride.get();
        if (override != null) return "COMPACT".equals(override);
        return "COMPACT".equals(ModConfig.get().buttonStyle);
    }

    public static String toggle() {
        String current = isCompact() ? "COMPACT" : "NORMAL";
        String next = "COMPACT".equals(current) ? "NORMAL" : "COMPACT";
        sessionOverride.set(next);
        return next;
    }

    public static String currentStyleName() {
        return isCompact() ? "COMPACT" : "NORMAL";
    }
}
