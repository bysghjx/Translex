package top.iencand.translex.client.translate.provider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 供应商适配器注册表。内置 OpenAI 兼容 + Anthropic，按 id 查找。
 *
 * <p>新增供应商：实现 {@link AiProvider} 后在 {@link #register} 静态块中注册即可。</p>
 */
public final class AiProviders {

    private static final Map<String, AiProvider> REGISTRY = new LinkedHashMap<>();
    public static final String DEFAULT_ID = "openai";

    static {
        register(new OpenAiProvider());
        register(new AnthropicProvider());
    }

    private AiProviders() {}

    private static void register(AiProvider provider) {
        REGISTRY.put(provider.id(), provider);
    }

    /** 按 id 查找适配器；未知 id 回退到默认（OpenAI）。 */
    public static AiProvider get(String id) {
        if (id == null) return REGISTRY.get(DEFAULT_ID);
        AiProvider p = REGISTRY.get(id.toLowerCase());
        return p != null ? p : REGISTRY.get(DEFAULT_ID);
    }

    /** 所有已注册适配器（保持注册顺序），供 UI 列出选项。 */
    public static Map<String, AiProvider> all() {
        return REGISTRY;
    }
}
