package top.iencand.translex;

import net.fabricmc.api.ModInitializer;

/**
 * Translex 模组的主初始化入口。
 * 实现了 Fabric 的 {@link ModInitializer} 接口，在模组加载通用侧时调用。
 * 客户端专属的初始化逻辑在 {@code TranslexClient} 中完成。
 */
public class Translex implements ModInitializer {

    @Override
    public void onInitialize() {
        // 通用侧（服务端/无头环境）无需额外初始化
    }
}
