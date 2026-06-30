package top.iencand.translex.client.translate.pipeline;

import java.util.function.Supplier;

/**
 * 描述一条翻译管线差异的不可变配置对象。
 *
 * <p>让同一个 {@link BatchDispatcher} 类被聊天管线和物品管线两份实例复用而互不串扰：
 * <ul>
 *   <li>{@code displayIdPrefix} —— 进度行标记前缀（聊天 {@code "TL_CHAT"} / 物品 {@code "TL_ITEM"}），
 *       两条管线各用独立 displayId，进度行不会互相覆盖。</li>
 *   <li>{@code windowMs} —— 批处理窗口（毫秒），各管线可独立设定。</li>
 *   <li>{@code promptSupplier} —— 该管线的 system prompt 来源（聊天用 {@code translationPrompt}，
 *       物品用 {@code itemTranslationPrompt}），每次发请求时实时读取，配置热重载即时生效。</li>
 * </ul>
 */
public final class PipelineConfig {

    private final String displayIdPrefix;
    private final long windowMs;
    private final Supplier<String> systemPromptSupplier;
    private final Supplier<String> userPromptSupplier;
    private final String threadName;

    public PipelineConfig(String displayIdPrefix, long windowMs,
                          Supplier<String> systemPromptSupplier,
                          Supplier<String> userPromptSupplier,
                          String threadName) {
        this.displayIdPrefix = displayIdPrefix;
        this.windowMs = windowMs;
        this.systemPromptSupplier = systemPromptSupplier;
        this.userPromptSupplier = userPromptSupplier;
        this.threadName = threadName;
    }

    /** 进度行 displayId 前缀（如 {@code "TL_CHAT"}）。批处理 displayId = prefix + "_BATCH"。 */
    public String displayIdPrefix() { return displayIdPrefix; }

    /** 批处理窗口（毫秒）。 */
    public long windowMs() { return windowMs; }

    /** 实时获取该管线的强制 system prompt（已注入目标语言）。 */
    public String systemPrompt() { return systemPromptSupplier.get(); }

    /** 实时获取用户可选的 user prompt（可能为空，空则不发送）。 */
    public String userPrompt() { return userPromptSupplier.get(); }

    /** 调度线程名（便于日志/排查）。 */
    public String threadName() { return threadName; }
}
