package top.iencand.translex.client.config;

/**
 * 配置重载事件的监听器接口。
 * 当 ModConfig 重新加载时，所有已注册的监听器会被依次调用。
 * 使用 {@link ModConfig#addListener(ConfigReloadListener)} 注册。
 */
@FunctionalInterface
public interface ConfigReloadListener {
    /** 配置重载时调用 */
    void onConfigReload(ModConfig config);
}
