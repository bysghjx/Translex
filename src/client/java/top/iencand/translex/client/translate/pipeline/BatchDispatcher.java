package top.iencand.translex.client.translate.pipeline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.config.NetworkConfig;
import top.iencand.translex.client.translate.TranslationParser;
import top.iencand.translex.client.translate.TranslationRequester;
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
 * 可复用的网络批处理器：批处理、请求去重、字典格式载荷、防御性响应清理、缺失条目单条重试。
 *
 * <p>由原 {@code TranslationDispatcher} 改造而来，通过注入的 {@link PipelineConfig}
 * 区分各管线的 system prompt、批处理窗口、进度行 displayId、调度线程名。
 * 聊天管线与物品管线各持有一个独立实例，去重表 {@code pendingRequests} 天然按实例隔离，
 * 因此不会跨管线去重（彻底分离）。</p>
 *
 * <p>多个实例共享同一个无状态的 {@link TranslationRequester}（复用 OkHttp 连接池）。</p>
 */
public class BatchDispatcher {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter TRACE_TF = DateTimeFormatter.ofPattern("HH:mm:ss");

    // -------- 管线配置 --------
    private final PipelineConfig config;
    private final String batchDisplayId;

    // -------- 请求去重（按实例隔离） --------
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();

    // -------- 批处理队列 --------
    private final List<BatchEntry> batchQueue = Collections.synchronizedList(new ArrayList<>());
    private final ScheduledExecutorService windowScheduler;
    private ScheduledFuture<?> windowFuture;

    // -------- 组件（requester 共享注入） --------
    private final TranslationRequester requester;
    private final TranslationProgressTracker progressTracker = new TranslationProgressTracker();

    // -------- 批处理状态 --------
    private volatile int batchSeq = 0;
    private volatile boolean shutdown;

    private record BatchEntry(int index, String text, CompletableFuture<String> future) {}

    public BatchDispatcher(PipelineConfig config, TranslationRequester sharedRequester) {
        this.config = config;
        this.requester = sharedRequester;
        this.batchDisplayId = config.displayIdPrefix() + "_BATCH";
        this.windowScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, config.threadName());
            t.setDaemon(true);
            return t;
        });
    }

    // ===============================================================
    // 公开 API
    // ===============================================================

    /**
     * 提交文本进行翻译。如果相同文本已在本管线队列中等待，则返回已存在的 Future（管线内去重）。
     */
    public CompletableFuture<String> submit(String text) {
        if (text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(text);
        }

        CompletableFuture<String> existing = pendingRequests.get(text);
        if (existing != null) return existing;

        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(text, future);
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
    // 窗口定时器
    // ===============================================================

    private void scheduleWindow() {
        synchronized (this) {
            if (windowFuture == null || windowFuture.isDone()) {
                windowFuture = windowScheduler.schedule(this::flush, config.windowMs(), TimeUnit.MILLISECONDS);
            }
        }
    }

    // ===============================================================
    // 刷出
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

        JsonObject dict = new JsonObject();
        for (int i = 0; i < batch.size(); i++) {
            dict.addProperty(String.valueOf(i), batch.get(i).text());
        }
        String payload = GSON.toJson(dict);

        progressTracker.updateLoading(batchDisplayId,
                I18nHelper.translate("translex.info.translating_batch", batch.size()));

        final String systemPrompt = config.systemPrompt();
        final String userPrompt   = config.userPrompt();
        long systemPromptTokens = TokenCounter.estimate(systemPrompt)
                + (userPrompt != null && !userPrompt.isBlank() ? TokenCounter.estimate(userPrompt) : 0);
        long payloadTokens      = TokenCounter.estimate(payload);
        long estimatedTokens    = systemPromptTokens + payloadTokens;
        MetricsCollector.get().recordAiRequestWithTokens(estimatedTokens);
        final long startTime = System.currentTimeMillis();
        final String apiUrlSnapshot = ModConfig.get().apiUrl;
        ConsoleBroadcaster.broadcast("INFO",
                "Sending AI request — " + config.displayIdPrefix() + " batch #" + seq + ", "
                + batch.size() + " texts, " + payload.length() + " chars, ~" + estimatedTokens + " tokens");

        requester.requestTranslation(
                ModConfig.get().apiKey,
                apiUrlSnapshot,
                ModConfig.get().modelName,
                systemPrompt,
                userPrompt,
                payload,
                "BATCH_" + seq,
                "批_" + seq,
                (cacheKey, rawResult, displayId, rawBody) -> {
                    long duration = System.currentTimeMillis() - startTime;
                    MetricsCollector.get().recordLatency(duration);
                    MetricsCollector.TraceEntry te = new MetricsCollector.TraceEntry(
                            LocalTime.now().format(TRACE_TF),
                            "POST", apiUrlSnapshot,
                            !rawResult.startsWith("§c"), duration,
                            buildTraceBody(systemPrompt, userPrompt, payload), rawBody != null ? rawBody : rawResult);
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
        Minecraft.getInstance().execute(() -> {
            try {
                // 整批请求失败（如网络/HTTP/API 错误）：rawResult 是 §c 开头的错误串，
                // 不是可解析的字典。直接把错误传播给每个 future，让上层渲染红字，
                // 而不是误判为"缺失"再单条重试（同样会失败并被静默吞掉）。
                if (rawResult != null && rawResult.startsWith("§c")) {
                    for (BatchEntry entry : batch) {
                        entry.future().complete(rawResult);
                    }
                    return;
                }

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

                for (BatchEntry entry : missing) {
                    retrySingle(entry);
                }

            } catch (Exception e) {
                String errorMsg = "§c" + I18nHelper.translate("translex.error.parse.json");
                for (BatchEntry entry : batch) {
                    entry.future().complete(errorMsg);
                }
            } finally {
                progressTracker.removeLoading(batchDisplayId);
            }
        });
    }

    // ===============================================================
    // 字典响应解析
    // ===============================================================

    static Map<Integer, String> parseDictResponse(String raw, int expectedSize) {
        try {
            return new TranslationParser().parseDict(raw, expectedSize);
        } catch (TranslationParser.ParseException e) {
            return Map.of();
        }
    }

    /**
     * 构造抓包页显示用的请求体：还原实际发送的 messages 数组
     * （强制 system → 可选 user → payload），让用户能在 Web 控制台看到完整 prompt，
     * 而不是只看到 payload。仅用于展示，与真正发送的请求由 TranslationRequester 各自构造。
     */
    private static String buildTraceBody(String systemPrompt, String userPrompt, String payload) {
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        messages.add(traceMsg("system", systemPrompt));
        if (userPrompt != null && !userPrompt.isBlank()) {
            messages.add(traceMsg("user", userPrompt));
        }
        messages.add(traceMsg("user", payload));
        JsonObject body = new JsonObject();
        body.add("messages", messages);
        return GSON.toJson(body);
    }

    private static JsonObject traceMsg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    // ===============================================================
    // 单条重试
    // ===============================================================

    private void retrySingle(BatchEntry entry) {
        NetworkConfig.RETRY_EXECUTOR.execute(() -> {
            try {
                JsonObject dict = new JsonObject();
                dict.addProperty("0", entry.text());
                String payload = GSON.toJson(dict);

                final String systemPrompt = config.systemPrompt();
                final String userPrompt   = config.userPrompt();
                long sysTokens = TokenCounter.estimate(systemPrompt)
                        + (userPrompt != null && !userPrompt.isBlank() ? TokenCounter.estimate(userPrompt) : 0);
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
                        systemPrompt,
                        userPrompt,
                        payload,
                        "RETRY_" + entry.index(),
                        "重试_" + entry.index(),
                        (cacheKey, rawResult, displayId, rawBody) -> {
                            long duration = System.currentTimeMillis() - startTime;
                            MetricsCollector.get().recordLatency(duration);
                            MetricsCollector.TraceEntry te = new MetricsCollector.TraceEntry(
                                    LocalTime.now().format(TRACE_TF),
                                    "POST", apiUrlSnapshot,
                                    !rawResult.startsWith("§c"), duration,
                                    buildTraceBody(systemPrompt, userPrompt, payload), rawBody != null ? rawBody : rawResult);
                            te.estimatedTokens            = estTokens;
                            te.estimatedSystemPromptTokens = sysTokens;
                            te.estimatedPayloadTokens      = payTokens;
                            MetricsCollector.get().recordTrace(te);
                            Map<Integer, String> parsed = parseDictResponse(rawResult, 1);
                            String result = parsed.getOrDefault(0, entry.text());
                            if (!shutdown) {
                                Minecraft.getInstance().execute(() ->
                                        entry.future().complete(result));
                            } else {
                                entry.future().complete(result);
                            }
                        }
                );
            } catch (Exception e) {
                if (!shutdown) {
                    Minecraft.getInstance().execute(() ->
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
        progressTracker.updateLoading(batchDisplayId,
                I18nHelper.translate("translex.info.preparing", count));
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
