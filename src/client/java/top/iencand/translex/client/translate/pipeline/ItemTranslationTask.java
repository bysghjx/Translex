package top.iencand.translex.client.translate.pipeline;

import top.iencand.translex.client.translate.model.StyledText;

import java.util.concurrent.CompletableFuture;

/**
 * 单行 / 段落翻译任务的不可变数据载体。
 *
 * <p>统一 {@code ItemTranslationPipeline} 中此前两个局部 record（{@code LinePending} /
 * {@code ParaPending}）的字段：单行即 {@code lineCount == 1} 的特殊段落，段落由
 * {@code lineCount > 1} 判别。字段全部不可变，便于任务在分组、提交、回调之间传递。</p>
 *
 * <p>{@code original} 使用协议无关的 {@link StyledText}，而非裸 {@code Component}：解码、
 * 数字回填、样式重建都依赖它，且避免调用方重复做 {@code StyleCodec} 抽取。</p>
 *
 * @param startLine    任务覆盖的首行下标（含）
 * @param lineCount    覆盖行数；单行恒为 1，段落 {@code >} 1
 * @param future       该任务提交给 dispatcher 的异步结果
 * @param original     原文（单行原文，或段落合并后的 StyledText）
 * @param template     提交给 AI 的模板（含 {@code {N}} 占位符）
 * @param cacheKey     行级 / 段落级缓存键
 * @param formatId     协议 id（SN / TSP / HYBRID / TSP-HYBRID）
 * @param registryHash TSP 的 registry 指纹（SN 为 null）
 */
public record ItemTranslationTask(
        int startLine,
        int lineCount,
        CompletableFuture<String> future,
        StyledText original,
        String template,
        String cacheKey,
        String formatId,
        String registryHash
) {

    /** 段落任务：覆盖多行（{@code lineCount > 1}）。 */
    public boolean isParagraph() {
        return lineCount > 1;
    }

    /** 覆盖行数尾下标（不含），等价于 {@code startLine + lineCount}。 */
    public int endLineExclusive() {
        return startLine + lineCount;
    }

    /** 单行任务工厂。 */
    public static ItemTranslationTask ofLine(
            int index,
            CompletableFuture<String> future,
            StyledText original,
            String template,
            String cacheKey,
            String formatId,
            String registryHash) {
        return new ItemTranslationTask(index, 1, future, original, template, cacheKey,
                formatId, registryHash);
    }

    /** 段落任务工厂。 */
    public static ItemTranslationTask ofParagraph(
            int startLine,
            int lineCount,
            CompletableFuture<String> future,
            StyledText original,
            String template,
            String cacheKey,
            String formatId,
            String registryHash) {
        return new ItemTranslationTask(startLine, lineCount, future, original, template,
                cacheKey, formatId, registryHash);
    }
}
