package top.iencand.translex.client.config;

@FunctionalInterface
public interface ConfigReloadListener {
    void onConfigReload(ModConfig config);
}
