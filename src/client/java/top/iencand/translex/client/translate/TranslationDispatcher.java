package top.iencand.translex.client.translate;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.MinecraftClient;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.net.NetworkConfig;
import top.iencand.translex.client.util.I18nHelper;
import top.iencand.translex.client.web.ConsoleBroadcaster;
import top.iencand.translex.client.web.MetricsCollector;
import top.iencand.translex.client.web.TokenCounter;

import java.lang.reflect.Type;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Network-layer core: batching, request deduplication, dict-format payloads,
 * defensive response cleaning, and single-item retry on missing entries.
 *
 * <p>Callers use {@link #submit(String)} to enqueue a text and receive a
 * {@link CompletableFuture} that completes with the translated result.</p>
 */
public class TranslationDispatcher {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    /**
     * Fixes AI responses that use unquoted numeric keys: {@code {0:"text"}} → {@code {"0":"text"}}.
     */
    private static final Pattern UNQUOTED_KEY_RE = Pattern.compile("\\{(\\d+):");

    // --- dedup ---
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();

    // --- batching ---
    private final List<BatchEntry> batchQueue = Collections.synchronizedList(new ArrayList<>());
    private final ScheduledExecutorService windowScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Translex-Dispatcher");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> windowFuture;
    private static final long WINDOW_MS = 1500;

    // --- components ---
    private final TranslationRequester requester = new TranslationRequester();
    private final TranslationProgressTracker progressTracker = new TranslationProgressTracker();
    private static final String BATCH_DISPLAY_ID = "TL_BATCH";

    // --- batch state ---
    private volatile int batchSeq = 0;
    private static final DateTimeFormatter TRACE_TF = DateTimeFormatter.ofPattern("HH:mm:ss");

    private record BatchEntry(int index, String text, CompletableFuture<String> future) {}

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /**
     * Submit text for translation. If an identical text is already pending
     * the returned future is shared (deduplication).
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

    // ---------------------------------------------------------------
    // Window timer
    // ---------------------------------------------------------------

    private void scheduleWindow() {
        synchronized (this) {
            if (windowFuture == null || windowFuture.isDone()) {
                windowFuture = windowScheduler.schedule(this::flush, WINDOW_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    // ---------------------------------------------------------------
    // Flush: build dict, send, parse, complete futures
    // ---------------------------------------------------------------

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
        long estimatedTokens = TokenCounter.estimate(ModConfig.get().translationPrompt)
                + TokenCounter.estimate(payload);
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
                    MetricsCollector.get().recordTrace(new MetricsCollector.TraceEntry(
                            LocalTime.now().format(TRACE_TF),
                            "POST", apiUrlSnapshot,
                            !rawResult.startsWith("§c"), duration,
                            payload, rawResult));
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

    // ---------------------------------------------------------------
    // Dict response parsing with defensive cleaning
    // ---------------------------------------------------------------

    static Map<Integer, String> parseDictResponse(String raw, int expectedSize) {
        if (raw == null || raw.isBlank()) return Map.of();

        // 1. Strip Minecraft color codes and markdown fences
        String cleaned = raw
                .replaceAll("§[0-9a-fk-or]", "")
                .replaceAll("```(?:json)?\\s*|```", "")
                .trim();

        // 2. Extract JSON object
        cleaned = extractJsonObject(cleaned);

        // 3. Defensive: fix unquoted numeric keys {0:"text"} → {"0":"text"}
        cleaned = UNQUOTED_KEY_RE.matcher(cleaned).replaceAll("{\"$1\":");

        // 4. Parse
        try {
            Map<String, String> stringMap = GSON.fromJson(cleaned, MAP_TYPE);
            Map<Integer, String> result = new LinkedHashMap<>();
            if (stringMap != null) {
                for (Map.Entry<String, String> e : stringMap.entrySet()) {
                    try {
                        result.put(Integer.parseInt(e.getKey()), e.getValue());
                    } catch (NumberFormatException ignored) {}
                }
            }
            return result;
        } catch (JsonSyntaxException e) {
            // Fallback: try as array
            try {
                String[] arr = GSON.fromJson(cleaned, String[].class);
                Map<Integer, String> result = new LinkedHashMap<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length; i++) {
                        result.put(i, arr[i]);
                    }
                }
                return result;
            } catch (JsonSyntaxException e2) {
                return Map.of();
            }
        }
    }

    private static String extractJsonObject(String input) {
        int first = input.indexOf('{');
        int last = input.lastIndexOf('}');
        if (first != -1 && last != -1 && last > first) {
            return input.substring(first, last + 1);
        }
        return input;
    }

    // ---------------------------------------------------------------
    // Single-item retry (dict format, id=0)
    // ---------------------------------------------------------------

    private void retrySingle(BatchEntry entry) {
        NetworkConfig.RETRY_EXECUTOR.execute(() -> {
            try {
                JsonObject dict = new JsonObject();
                dict.addProperty("0", entry.text());
                String payload = GSON.toJson(dict);

                CompletableFuture<String> retryFuture = new CompletableFuture<>();

                // ---- 指标采集 + 控制台广播 ----
                long estTokens = TokenCounter.estimate(ModConfig.get().translationPrompt)
                        + TokenCounter.estimate(payload);
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
                            MetricsCollector.get().recordTrace(new MetricsCollector.TraceEntry(
                                    LocalTime.now().format(TRACE_TF),
                                    "POST", apiUrlSnapshot,
                                    !rawResult.startsWith("§c"), duration,
                                    payload, rawResult));
                            Map<Integer, String> parsed = parseDictResponse(rawResult, 1);
                            String result = parsed.getOrDefault(0, entry.text());
                            MinecraftClient.getInstance().execute(() ->
                                    entry.future().complete(result));
                        }
                );
            } catch (Exception e) {
                MinecraftClient.getInstance().execute(() ->
                        entry.future().complete(entry.text()));
            }
        });
    }

    // ---------------------------------------------------------------
    // Progress tracking
    // ---------------------------------------------------------------

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

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    public void shutdown() {
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
