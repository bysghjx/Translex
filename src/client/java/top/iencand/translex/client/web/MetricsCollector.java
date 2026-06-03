package top.iencand.translex.client.web;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程安全的运行时可观测性指标收集器（单例）。
 *
 * <p>前端通过 GET /api/metrics 轮询获取 JSON，通过 GET /api/traces 拉取抓包。</p>
 */
public class MetricsCollector {

    private static final MetricsCollector INSTANCE = new MetricsCollector();

    // ---- 计数器 ----
    private final AtomicLong localHits  = new AtomicLong(0);
    private final AtomicLong aiRequests = new AtomicLong(0);

    // ---- Token 统计 ----
    private final AtomicLong totalEstimatedTokens = new AtomicLong(0);
    private final AtomicLong totalSavedTokens      = new AtomicLong(0);

    // ---- 延迟历史（最近 20 次，供折线图） ----
    private final Deque<Long> latencyHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_LATENCY_SIZE = 20;

    // ---- 网络抓包（最近 10 条） ----
    private final List<TraceEntry> traces = new CopyOnWriteArrayList<>();
    private static final int MAX_TRACES = 10;

    private MetricsCollector() {}

    public static MetricsCollector get() { return INSTANCE; }

    // ---------------------------------------------------------------
    // 记录方法（供业务代码调用）
    // ---------------------------------------------------------------

    public void recordLocalHit() {
        localHits.incrementAndGet();
    }

    /** 记录一次缓存命中所节省的 token 数 */
    public void recordLocalHitWithTokens(long tokensSaved) {
        localHits.incrementAndGet();
        totalSavedTokens.addAndGet(tokensSaved);
    }

    public void recordAiRequest() {
        aiRequests.incrementAndGet();
    }

    /** 记录一次 AI 请求所消耗的估算 token 数 */
    public void recordAiRequestWithTokens(long estimatedTokens) {
        aiRequests.incrementAndGet();
        totalEstimatedTokens.addAndGet(estimatedTokens);
    }

    public void recordLatency(long millis) {
        latencyHistory.addLast(millis);
        while (latencyHistory.size() > MAX_LATENCY_SIZE) {
            latencyHistory.pollFirst();
        }
    }

    public void recordTrace(TraceEntry entry) {
        traces.add(0, entry); // 最新的在最前面
        while (traces.size() > MAX_TRACES) {
            traces.remove(traces.size() - 1);
        }
    }

    // ---------------------------------------------------------------
    // 只读快照（供 WebServer 路由使用）
    // ---------------------------------------------------------------

    public long getLocalHits()            { return localHits.get(); }
    public long getAiRequests()           { return aiRequests.get(); }
    public long getTotalEstimatedTokens() { return totalEstimatedTokens.get(); }
    public long getTotalSavedTokens()     { return totalSavedTokens.get(); }
    public List<Long> getLatencyHistory() { return List.copyOf(latencyHistory); }
    public List<TraceEntry> getTraces()   { return List.copyOf(traces); }

    // ---------------------------------------------------------------
    // 内嵌数据类
    // ---------------------------------------------------------------

    public static class TraceEntry {
        public final String timestamp;
        public final String method;
        public final String url;
        public final boolean success;
        public final long durationMs;
        public final String requestBody;
        public final String responseBody;

        public TraceEntry(String timestamp, String method, String url,
                          boolean success, long durationMs,
                          String requestBody, String responseBody) {
            this.timestamp    = timestamp;
            this.method       = method;
            this.url          = url;
            this.success      = success;
            this.durationMs   = durationMs;
            this.requestBody  = requestBody;
            this.responseBody = responseBody;
        }
    }
}
