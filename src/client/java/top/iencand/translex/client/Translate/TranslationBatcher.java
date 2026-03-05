package top.iencand.translex.client.Translate;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class TranslationBatcher {
    // 待处理的任务项
    public record BatchTask(String text, Consumer<String> onResult) {}

    private final List<BatchTask> queue = Collections.synchronizedList(new ArrayList<>());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> currentFlushTask = null;
    private final long windowMs;
    private final Consumer<List<BatchTask>> processor;

    /**
     * @param windowMs 聚合窗口时间（如 800ms）
     * @param processor 窗口关闭后，如何处理这一批任务
     */
    public TranslationBatcher(long windowMs, Consumer<List<BatchTask>> processor) {
        this.windowMs = windowMs;
        this.processor = processor;
    }

    public void submit(String text, Consumer<String> onResult) {
        queue.add(new BatchTask(text, onResult));

        synchronized (this) {
            if (currentFlushTask == null || currentFlushTask.isDone()) {
                currentFlushTask = scheduler.schedule(this::flush, windowMs, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void flush() {
        List<BatchTask> batch;
        synchronized (queue) {
            if (queue.isEmpty()) return;
            batch = new ArrayList<>(queue);
            queue.clear();
        }
        processor.accept(batch);
    }
}