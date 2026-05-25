package top.iencand.translex.client.Translate;

import top.iencand.translex.client.config.ConfigReloadListener;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.util.I18nHelper;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TranslationManager 是插件的核心调度中心。
 * 负责任务的批量缓冲、异步流程控制及协调各子组件。
 */
public class TranslationManager {
    // 任务计数与状态管理
    private final AtomicLong translationCounter = new AtomicLong(0);
    private final List<PendingTask> requestBuffer = Collections.synchronizedList(new ArrayList<>());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // 组合解耦的功能组件
    private final TranslationRequester requester = new TranslationRequester();
    private final TranslationParser parser = new TranslationParser();
    private final TranslationCacheManager cacheManager = new TranslationCacheManager();
    private final TranslationProgressTracker progressTracker = new TranslationProgressTracker();
    private final ChatRenderer renderer = new ChatRenderer();
    private final ItemPresetLibrary presetLibrary = new ItemPresetLibrary();

    private ScheduledFuture<?> scheduledTask = null;

    public TranslationManager() {
        ModConfig.addListener(config -> {});
        presetLibrary.load();
    }

    /**
     * 内部任务记录类
     */
    private record PendingTask(String text, String displayId, boolean isIdMode,
                               String itemId, String itemDisplayName) {
        PendingTask(String text, String displayId, boolean isIdMode) {
            this(text, displayId, isIdMode, null, null);
        }
    }

    /**
     * 初始化持久化层（替代了旧的 setCacheFile 和 loadCache）
     * 由 TranslexClient 在 onInitializeClient 中调用。
     */
    public void initializePersistence(File file) {
        if (file != null) {
            cacheManager.init(file);
        }
    }

    /**
     * 异步翻译接口 (针对具有特定 ID 的聊天消息)
     */
    public void translateAsync(int id, String text, String unused) {
        submitToBatcher(text, String.valueOf(id), true);
    }

    /**
     * 异步翻译接口 (针对纯文本内容，如 /translex text 命令)
     */
    public void translateTextAsync(String text, String ctx) {
        submitToBatcher(text, "TX_" + translationCounter.incrementAndGet(), false);
    }

    /**
     * 异步翻译接口 (物品 Lore 翻译，支持预置库自动录入)
     */
    public void translateItemLoreAsync(String text, String context, String itemId, String itemDisplayName) {
        if (text == null || text.isBlank()) return;

        String cached = cacheManager.get(text);
        if (cached != null) {
            renderer.renderResult(text, cached, "IL_" + translationCounter.incrementAndGet());
            return;
        }

        String id = "IL_" + translationCounter.incrementAndGet();
        progressTracker.showLoading(id);
        requestBuffer.add(new PendingTask(text, id, false, itemId, itemDisplayName));
        scheduleFlush();
    }

    /**
     * 将翻译请求提交到批量缓冲区
     */
    private void submitToBatcher(String text, String id, boolean mode) {
        if (text == null || text.isBlank()) return;

        // 1. 检查缓存
        String cached = cacheManager.get(text);
        if (cached != null) {
            renderer.renderResult(text, cached, id);
            return;
        }

        // 2. 显示加载状态
        progressTracker.showLoading(id);

        // 3. 加入缓冲区并计划 1.5s 后刷新
        requestBuffer.add(new PendingTask(text, id, mode));
        scheduleFlush();
    }

    private void scheduleFlush() {
        synchronized (this) {
            if (scheduledTask == null || scheduledTask.isDone()) {
                scheduledTask = scheduler.schedule(this::flushBuffer, 1500, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * 批量刷新缓冲区，发送请求
     */
    private void flushBuffer() {
        List<PendingTask> tasks;
        synchronized (requestBuffer) {
            if (requestBuffer.isEmpty()) return;
            tasks = new ArrayList<>(requestBuffer);
            requestBuffer.clear();
        }

        List<String> rawTexts = tasks.stream().map(PendingTask::text).toList();
        String jsonPayload = new com.google.gson.Gson().toJson(rawTexts);

        requester.requestTranslation(
                ModConfig.get().apiKey, ModConfig.get().apiUrl, ModConfig.get().modelName,
                ModConfig.get().translationPrompt, jsonPayload, "BATCH", "批量",
                (key, result, id) -> processBatchResult(result, tasks)
        );
    }

    /**
     * 处理 AI 返回结果
     */
    private void processBatchResult(String result, List<PendingTask> tasks) {
        tasks.forEach(t -> progressTracker.removeLoading(t.displayId()));

        if (result != null && result.trim().startsWith("§c")) {
            tasks.forEach(t -> renderer.renderError(result.trim(), t.displayId()));
            return;
        }

        try {
            String[] results = parser.parse(result, tasks.size());
            for (int i = 0; i < tasks.size(); i++) {
                PendingTask t = tasks.get(i);
                cacheManager.put(t.text(), results[i]);
                renderer.renderResult(t.text(), results[i], t.displayId());
                // 物品 Lore 翻译成功后自动录入预置库
                if (t.itemId() != null && !t.itemId().isEmpty()) {
                    presetLibrary.put(t.itemId(), t.itemDisplayName(), results[i]);
                }
            }
        } catch (TranslationParser.ParseException e) {
            handleParseError(result, tasks, e.getMessage());
        }
    }

    /**
     * 异常处理逻辑
     */
    private void handleParseError(String rawResponse, List<PendingTask> tasks, String errorDetail) {
        net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
            if (rawResponse != null) {
                net.minecraft.client.MinecraftClient.getInstance().keyboard.setClipboard(rawResponse);
            }
        });

        String localizedError = I18nHelper.translate("translex.error.parse.json") + " (" + errorDetail + ")";
        tasks.forEach(t -> renderer.renderError(localizedError, t.displayId()));
    }

    /**
     * 停用时保存数据
     */
    public void shutdown() {
        if (cacheManager != null) {
            cacheManager.shutdown();
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ignored) {}
    }
}