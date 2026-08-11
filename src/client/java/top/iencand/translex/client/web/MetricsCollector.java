package top.iencand.translex.client.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程安全的运行时可观测性指标收集器（单例）。
 *
 * <p>前端通过 GET /api/metrics 轮询获取 JSON，通过 GET /api/traces 拉取抓包。</p>
 */
public class MetricsCollector {

    private static final MetricsCollector INSTANCE = new MetricsCollector();

    // -------- 计数器 --------
    private final AtomicLong localHits  = new AtomicLong(0);  // 本地缓存命中次数
    private final AtomicLong aiRequests = new AtomicLong(0);  // AI 请求次数

    // -------- Token 统计 --------
    private final AtomicLong totalEstimatedTokens = new AtomicLong(0); // 估算总消耗 Token
    private final AtomicLong totalSavedTokens      = new AtomicLong(0); // 缓存命中所节省的 Token

    // -------- API 返回的实际 Token 用量 --------
    private final AtomicLong totalActualPromptTokens     = new AtomicLong(0); // 实际提示词 Token
    private final AtomicLong totalActualCompletionTokens = new AtomicLong(0); // 实际补全 Token
    private volatile boolean hasActualTokenData = false; // 是否已有实际 Token 数据

    // -------- 延迟历史（最近 20 次，供前端折线图） --------
    private final Deque<Long> latencyHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_LATENCY_SIZE = 20;

    // -------- Token 消耗历史（最近 20 次请求的实际消耗，供前端趋势图） --------
    private final Deque<Long> tokenHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_TOKEN_HISTORY_SIZE = 20;

    // -------- 网络抓包（最近 10 条） --------
    private final Deque<TraceEntry> traceDeque = new ConcurrentLinkedDeque<>();
    private static final AtomicLong traceIdCounter = new AtomicLong(0);
    private static final int MAX_TRACES = 10;

    // 暂存 API 返回的 token 数据（队列），等 TraceEntry 创建后 FIFO 消费
    private final Deque<TokenData> pendingTokenQueue = new ConcurrentLinkedDeque<>();
    private final AtomicLong stagedTokenCount = new AtomicLong(0); // 防止无 usage 的请求误消费

    private static final Logger LOGGER = LoggerFactory.getLogger("TranslexMetrics");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LONG_LIST_TYPE = new TypeToken<List<Long>>() {}.getType();

    private MetricsCollector() {}

    public static MetricsCollector get() { return INSTANCE; }

    // ===============================================================
    // 记录方法（供业务代码调用）
    // ===============================================================

    public void recordLocalHit() {
        localHits.incrementAndGet();
    }

    /** 记录一次缓存命中所节省的 Token 数 */
    public void recordLocalHitWithTokens(long tokensSaved) {
        localHits.incrementAndGet();
        totalSavedTokens.addAndGet(tokensSaved);
    }

    public void recordAiRequest() {
        aiRequests.incrementAndGet();
    }

    /** 记录一次 AI 请求所消耗的估算 Token 数 */
    public void recordAiRequestWithTokens(long estimatedTokens) {
        aiRequests.incrementAndGet();
        totalEstimatedTokens.addAndGet(estimatedTokens);
    }

    /** 记录 API 返回的实际 Token 用量 */
    public void recordActualTokenUsage(long promptTokens, long completionTokens) {
        totalActualPromptTokens.addAndGet(promptTokens);
        totalActualCompletionTokens.addAndGet(completionTokens);
        hasActualTokenData = true;
        tokenHistory.addLast(promptTokens + completionTokens);
        while (tokenHistory.size() > MAX_TOKEN_HISTORY_SIZE) tokenHistory.pollFirst();
    }

    public void recordLatency(long millis) {
        latencyHistory.addLast(millis);
        while (latencyHistory.size() > MAX_LATENCY_SIZE) {
            latencyHistory.pollFirst();
        }
    }

    public void recordTrace(TraceEntry entry) {
        // 仅在有暂存数据时才消费，避免无 usage 的请求误拿走下游数据
        if (stagedTokenCount.get() > 0) {
            TokenData td = pendingTokenQueue.pollFirst();
            if (td != null) {
                stagedTokenCount.decrementAndGet();
                entry.promptTokens     = td.promptTokens;
                entry.completionTokens = td.completionTokens;
                entry.totalTokens      = td.totalTokens;
                entry.cachedTokens     = td.cachedTokens;
                entry.reasoningTokens  = td.reasoningTokens;
                entry.hasTokenData     = true;
            }
        }
        traceDeque.addFirst(entry);
        while (traceDeque.size() > MAX_TRACES) {
            traceDeque.pollLast();
        }
    }

    /** 暂存 API 返回的 Token 数据，等下次 recordTrace() 时按 FIFO 消费 */
    public void stageTokenData(long promptTokens, long completionTokens,
                               long totalTokens, long cachedTokens, long reasoningTokens) {
        pendingTokenQueue.addLast(new TokenData(promptTokens, completionTokens,
                totalTokens, cachedTokens, reasoningTokens));
        stagedTokenCount.incrementAndGet();
    }

    // ===============================================================
    // 只读快照（供 WebServer 路由使用）
    // ===============================================================

    public long getLocalHits()            { return localHits.get(); }
    public long getAiRequests()           { return aiRequests.get(); }
    public long getTotalEstimatedTokens()      { return totalEstimatedTokens.get(); }
    public long getTotalSavedTokens()          { return totalSavedTokens.get(); }
    public long getTotalActualPromptTokens()   { return totalActualPromptTokens.get(); }
    public long getTotalActualCompletionTokens(){ return totalActualCompletionTokens.get(); }
    public long getTotalActualTokens()         { return totalActualPromptTokens.get() + totalActualCompletionTokens.get(); }
    public boolean hasActualTokenData()        { return hasActualTokenData; }
    public List<Long> getLatencyHistory() { return List.copyOf(latencyHistory); }
    public List<Long> getTokenHistory()   { return List.copyOf(tokenHistory); }
    public List<TraceEntry> getTraces()   { return new ArrayList<>(traceDeque); }

    // ===============================================================
    // 内嵌数据类
    // ===============================================================

    private record TokenData(long promptTokens, long completionTokens,
                              long totalTokens, long cachedTokens,
                              long reasoningTokens) {}

    public static class TraceEntry {
        public final long id;
        public final String timestamp;
        public final String method;
        public final String url;
        public final boolean success;
        public final long durationMs;
        public final String requestBody;
        public final String responseBody;
        public long estimatedTokens  = 0;  // 请求时本地估算的总 Token 数
        public long estimatedPayloadTokens = 0;    // 其中翻译载荷部分的 Token
        public long estimatedSystemPromptTokens = 0; // 其中 system prompt 的 Token
        public long promptTokens     = 0;  // API 返回的 prompt_tokens
        public long completionTokens = 0;  // API 返回的 completion_tokens
        public long totalTokens      = 0;  // API 返回的 total_tokens
        public long cachedTokens     = 0;  // API 返回的缓存命中 Token 数（服务器端）
        public long reasoningTokens  = 0;  // API 返回的 reasoning/thinking Token 数
        public boolean hasTokenData  = false;
        public String debugLines     = null;  // per-line debug（原文/过滤后/encoded），JSON 数组字符串，null 表示无

        public TraceEntry(String timestamp, String method, String url,
                          boolean success, long durationMs,
                          String requestBody, String responseBody) {
            this.id           = traceIdCounter.incrementAndGet();
            this.timestamp    = timestamp;
            this.method       = method;
            this.url          = url;
            this.success      = success;
            this.durationMs   = durationMs;
            this.requestBody  = requestBody;
            this.responseBody = responseBody;
        }
    }

    // ===============================================================
    // 持久化（JSON → config/translex/metrics.json）
    // ===============================================================

    /** 将当前指标写入指定文件（仅写计数器 + 延迟历史，不写 traces） */
    public synchronized void saveToFile(Path file) {
        JsonObject json = new JsonObject();
        json.addProperty("localHits",                  localHits.get());
        json.addProperty("aiRequests",                 aiRequests.get());
        json.addProperty("totalEstimatedTokens",       totalEstimatedTokens.get());
        json.addProperty("totalSavedTokens",           totalSavedTokens.get());
        json.addProperty("totalActualPromptTokens",    totalActualPromptTokens.get());
        json.addProperty("totalActualCompletionTokens",totalActualCompletionTokens.get());
        json.addProperty("hasActualTokenData",         hasActualTokenData);
        json.add("latencyHistory", GSON.toJsonTree(new ArrayList<>(latencyHistory)));
        json.add("tokenHistory",   GSON.toJsonTree(new ArrayList<>(tokenHistory)));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[Metrics] Failed to save metrics: {}", e.getMessage());
        }
    }

    /** 从指定文件恢复计数器（文件不存在或损坏时静默跳过） */
    public synchronized void loadFromFile(Path file) {
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            localHits.set(                   getLong(json, "localHits"));
            aiRequests.set(                  getLong(json, "aiRequests"));
            totalEstimatedTokens.set(        getLong(json, "totalEstimatedTokens"));
            totalSavedTokens.set(            getLong(json, "totalSavedTokens"));
            totalActualPromptTokens.set(     getLong(json, "totalActualPromptTokens"));
            totalActualCompletionTokens.set( getLong(json, "totalActualCompletionTokens"));
            hasActualTokenData = json.has("hasActualTokenData") && json.get("hasActualTokenData").getAsBoolean();
            if (json.has("latencyHistory")) {
                List<Long> restored = GSON.fromJson(json.get("latencyHistory"), LONG_LIST_TYPE);
                if (restored != null) {
                    latencyHistory.clear();
                    for (Long v : restored) {
                        if (latencyHistory.size() >= MAX_LATENCY_SIZE) break;
                        latencyHistory.add(v);
                    }
                }
            }
            if (json.has("tokenHistory")) {
                List<Long> restoredTokens = GSON.fromJson(json.get("tokenHistory"), LONG_LIST_TYPE);
                if (restoredTokens != null) {
                    tokenHistory.clear();
                    for (Long v : restoredTokens) {
                        if (tokenHistory.size() >= MAX_TOKEN_HISTORY_SIZE) break;
                        tokenHistory.add(v);
                    }
                }
            }
            LOGGER.info("[Metrics] Loaded from {} — {} hits, {} AI reqs, {} est. tokens",
                    file.getFileName(), localHits.get(), aiRequests.get(), totalEstimatedTokens.get());
        } catch (Exception e) {
            LOGGER.warn("[Metrics] Failed to load metrics: {}", e.getMessage());
        }
    }

    private static long getLong(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsLong() : 0;
    }
}
