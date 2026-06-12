package top.iencand.translex.client.translate;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.config.NetworkConfig;
import top.iencand.translex.client.translate.render.TranslationProgressTracker;
import top.iencand.translex.client.util.I18nHelper;
import top.iencand.translex.client.web.ConsoleBroadcaster;
import top.iencand.translex.client.web.MetricsCollector;
import top.iencand.translex.client.web.TokenCounter;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * 网络层核心组件：批处理、请求去重、字典格式载荷、防御性响应清理、
 * 以及缺失条目的单条重试。
 *
 * <p>调用方通过 {@link #submit(String)} 加入文本队列，返回
 * {@link CompletableFuture} 在翻译完成后获得结果。</p>
 *
 * <p>批处理策略：在 1500ms 窗口内收集多条翻译请求，合并为一个字典格式的
 * JSON 载荷发送到 AI API，以提高吞吐量。</p>
 */
public class TranslationDispatcher {

    private static final Gson GSON = new Gson();

    // -------- 请求去重 --------
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();

    // -------- 批处理队列 --------
    private final List<BatchEntry> batchQueue = Collections.synchronizedList(new ArrayList<>());
    private final ScheduledExecutorService windowScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Translex-Dispatcher");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> windowFuture;
    private static final long WINDOW_MS = 1500;

    // -------- 组件 --------
    private final TranslationRequester requester = new TranslationRequester();
    private final TranslationProgressTracker progressTracker = new TranslationProgressTracker();
    private static final String BATCH_DISPLAY_ID = "TL_BATCH";

    // -------- 批处理状态 --------
    private volatile int batchSeq = 0;
    private volatile boolean shutdown;
    private static final DateTimeFormatter TRACE_TF = DateTimeFormatter.ofPattern("HH:mm:ss");

    private record BatchEntry(int index, String text, CompletableFuture<String> future) {}

    // ===============================================================
    // 公开 API
    // ===============================================================

    /**
     * 提交文本进行翻译。如果相同文本已在队列中等待，则返回已存在的 Future（去重）。
     *
     * @param text 待翻译文本
     * @return 翻译完成后的 Future
     */
    public CompletableFuture<String> submit(String text) {
        if (text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(text);
        }

        // Dedup: reuse existing future
        CompletableFuture<String> existing = pendingRequests.get(text);
        if (existing != null) return existing;

        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(text, future);

        // Clean up dedup entry when done
        future.whenComplete((result, ex) -> pendingRequests.remove(text));

        synchronized (batchQueue) {
            int idx = batchQueue.size();
            batchQueue.add(new BatchEntry(idx, text, future));
        }

        updateProgress();
        scheduleWindow();

        return future;
    }

    // ===============================================================
    // 窗口定时器（延迟刷出，收集批处理）
    // ===============================================================

    private void scheduleWindow() {
        synchronized (this) {
            if (windowFuture == null || windowFuture.isDone()) {
                windowFuture = windowScheduler.schedule(this::flush, WINDOW_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    // ===============================================================
    // 刷出：构建字典载荷、发送、解析、完成 Future
    // ===============================================================

    private void flush() {
        List<BatchEntry> batch;
        synchronized (batchQueue) {
            if (batchQueue.isEmpty()) return;
            batch = new ArrayList<>(batchQueue);
            batchQueue.clear();
        }

        if (batch.isEmpty()) return;

        final int seq = ++batchSeq;

        // Build dictionary payload
        JsonObject dict = new JsonObject();
        for (int i = 0; i < batch.size(); i++) {
            dict.addProperty(String.valueOf(i), batch.get(i).text());
        }
        String payload = GSON.toJson(dict);

        // Update progress: "正在翻译 N 条内容..."
        progressTracker.updateLoading(BATCH_DISPLAY_ID,
                I18nHelper.translate("translex.info.translating_batch", batch.size()));

        // ---- 指标采集 + 控制台广播 ----
        long systemPromptTokens = TokenCounter.estimate(ModConfig.get().translationPrompt);
        long payloadTokens      = TokenCounter.estimate(payload);
        long estimatedTokens    = systemPromptTokens + payloadTokens;
        MetricsCollector.get().recordAiRequestWithTokens(estimatedTokens);
        final long startTime = System.currentTimeMillis();
        final String apiUrlSnapshot = ModConfig.get().apiUrl;
        ConsoleBroadcaster.broadcast("INFO",
                "Sending AI request — batch #" + seq + ", " + batch.size() + " texts, "
                + payload.length() + " chars, ~" + estimatedTokens + " tokens");

        requester.requestTranslation(
                ModConfig.get().apiKey,
                apiUrlSnapshot,
                ModConfig.get().modelName,
                ModConfig.get().translationPrompt,
                payload,
                "BATCH_" + seq,
                "批_" + seq,
                (cacheKey, rawResult, displayId) -> {
                    long duration = System.currentTimeMillis() - startTime;
                    MetricsCollector.get().recordLatency(duration);
                    MetricsCollector.TraceEntry te = new MetricsCollector.TraceEntry(
                            LocalTime.now().format(TRACE_TF),
                            "POST", apiUrlSnapshot,
                            !rawResult.startsWith("§c"), duration,
                            payload, rawResult);
                    te.estimatedTokens            = estimatedTokens;
                    te.estimatedSystemPromptTokens = systemPromptTokens;
                    te.estimatedPayloadTokens      = payloadTokens;
                    MetricsCollector.get().recordTrace(te);
                    if (rawResult.startsWith("§c")) {
                        ConsoleBroadcaster.broadcast("ERROR",
                                "AI batch #" + seq + " FAILED after " + duration + "ms — " + rawResult);
                    } else {
                        ConsoleBroadcaster.broadcast("INFO",
                                "AI batch #" + seq + " completed in " + duration + "ms");
                    }
                    handleBatchResponse(rawResult, batch, seq);
                }
        );
    }

    private void handleBatchResponse(String rawResult, List<BatchEntry> batch, int seq) {
        if (shutdown) return;
        MinecraftClient.getInstance().execute(() -> {
            try {
                Map<Integer, String> parsed = parseDictResponse(rawResult, batch.size());

                List<BatchEntry> missing = new ArrayList<>();

                for (BatchEntry entry : batch) {
                    String translated = parsed.get(entry.index());
                    if (translated != null && !translated.isBlank()) {
                        entry.future().complete(translated);
                    } else {
                        missing.add(entry);
                    }
                }

                // Single-item retry for missing entries
                for (BatchEntry entry : missing) {
                    retrySingle(entry);
                }

            } catch (Exception e) {
                // Parse failure — complete all futures with error marker
                String errorMsg = "§c" + I18nHelper.translate("translex.error.parse.json");
                for (BatchEntry entry : batch) {
                    entry.future().complete(errorMsg);
                }
            } finally {
                progressTracker.removeLoading(BATCH_DISPLAY_ID);
            }
        });
    }

    // ===============================================================
    // 字典响应解析（带防御性清理）
    // ===============================================================

    static Map<Integer, String> parseDictResponse(String raw, int expectedSize) {
        try {
            return new TranslationParser().parseDict(raw, expectedSize);
        } catch (TranslationParser.ParseException e) {
            return Map.of();
        }
    }

    // ===============================================================
    // 单条重试（字典格式，id=0）
    // ===============================================================

    private void retrySingle(BatchEntry entry) {
        NetworkConfig.RETRY_EXECUTOR.execute(() -> {
            try {
                JsonObject dict = new JsonObject();
                dict.addProperty("0", entry.text());
                String payload = GSON.toJson(dict);

                CompletableFuture<String> retryFuture = new CompletableFuture<>();

                // ---- 指标采集 + 控制台广播 ----
                long sysTokens = TokenCounter.estimate(ModConfig.get().translationPrompt);
                long payTokens = TokenCounter.estimate(payload);
                long estTokens = sysTokens + payTokens;
                MetricsCollector.get().recordAiRequestWithTokens(estTokens);
                final long startTime = System.currentTimeMillis();
                final String apiUrlSnapshot = ModConfig.get().apiUrl;
                ConsoleBroadcaster.broadcast("WARN",
                        "Retrying single entry #" + entry.index() + " after batch miss");

                requester.requestTranslation(
                        ModConfig.get().apiKey,
                        apiUrlSnapshot,
                        ModConfig.get().modelName,
                        ModConfig.get().translationPrompt,
                        payload,
                        "RETRY_" + entry.index(),
                        "重试_" + entry.index(),
                        (cacheKey, rawResult, displayId) -> {
                            long duration = System.currentTimeMillis() - startTime;
                            MetricsCollector.get().recordLatency(duration);
                            MetricsCollector.TraceEntry te = new MetricsCollector.TraceEntry(
                                    LocalTime.now().format(TRACE_TF),
                                    "POST", apiUrlSnapshot,
                                    !rawResult.startsWith("§c"), duration,
                                    payload, rawResult);
                            te.estimatedTokens            = estTokens;
                            te.estimatedSystemPromptTokens = sysTokens;
                            te.estimatedPayloadTokens      = payTokens;
                            MetricsCollector.get().recordTrace(te);
                            Map<Integer, String> parsed = parseDictResponse(rawResult, 1);
                            String result = parsed.getOrDefault(0, entry.text());
                            if (!shutdown) {
                                MinecraftClient.getInstance().execute(() ->
                                        entry.future().complete(result));
                            } else {
                                entry.future().complete(result);
                            }
                        }
                );
            } catch (Exception e) {
                if (!shutdown) {
                    MinecraftClient.getInstance().execute(() ->
                            entry.future().complete(entry.text()));
                } else {
                    entry.future().complete(entry.text());
                }
            }
        });
    }

    // ===============================================================
    // 进度跟踪
    // ===============================================================

    private void updateProgress() {
        int count;
        synchronized (batchQueue) {
            count = batchQueue.size();
        }
        if (count == 0) return;

        String msg;
        if (count == 1) {
            msg = I18nHelper.translate("translex.info.preparing", 1);
        } else {
            msg = I18nHelper.translate("translex.info.preparing", count);
        }
        progressTracker.updateLoading(BATCH_DISPLAY_ID, msg);
    }

    // ===============================================================
    // 生命周期管理
    // ===============================================================

    public void shutdown() {
        shutdown = true;
        windowScheduler.shutdown();
        try {
            if (!windowScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                windowScheduler.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            windowScheduler.shutdownNow();
        }
    }
}
