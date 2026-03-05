package top.iencand.translex.client.Translate;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.util.I18nHelper;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class TranslationManager {
    private final AtomicLong translationCounter = new AtomicLong(0);
    private final Map<String, String> translationCache = new ConcurrentHashMap<>();
    private final List<PendingTask> requestBuffer = Collections.synchronizedList(new ArrayList<>());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final TranslationRequester translationRequester = new TranslationRequester();
    private final Gson gson = new Gson();
    private ScheduledFuture<?> scheduledTask = null;
    private File cacheFile;

    private record PendingTask(String text, String displayId, boolean isIdMode) {}

    public void translateAsync(int id, String text, String unused) { submitToBatcher(text, String.valueOf(id), true); }
    public void translateTextAsync(String text, String ctx) { submitToBatcher(text, String.valueOf(translationCounter.incrementAndGet()), false); }

    private void submitToBatcher(String text, String id, boolean mode) {
        if (text == null || text.isBlank()) return;
        if (translationCache.containsKey(text)) {
            showInChat(translationCache.get(text), id, false);
            return;
        }
        requestBuffer.add(new PendingTask(text, id, mode));
        synchronized (this) {
            if (scheduledTask == null || scheduledTask.isDone()) {
                scheduledTask = scheduler.schedule(this::flushBuffer, 1500, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void flushBuffer() {
        List<PendingTask> tasks;
        synchronized (requestBuffer) {
            if (requestBuffer.isEmpty()) return;
            tasks = new ArrayList<>(requestBuffer);
            requestBuffer.clear();
        }

        List<String> rawTexts = tasks.stream().map(PendingTask::text).toList();
        // 关键修复：第一个参数是用户Prompt，第二个是数量
        String instruction = I18nHelper.translate("translex.prompt.batch_instruction",
                ModConfig.get().translationPrompt, rawTexts.size());

        // 关键修复：将原文列表转为 JSON 作为 User 消息发送
        String jsonInput = gson.toJson(rawTexts);

        translationRequester.requestTranslation(
                ModConfig.get().apiKey, ModConfig.get().apiUrl, ModConfig.get().modelName,
                instruction, jsonInput, "BATCH", "批量",
                (key, result, id) -> processBatchResult(result, tasks)
        );
    }

    private void processBatchResult(String result, List<PendingTask> tasks) {
        if (result.startsWith("§c")) {
            tasks.forEach(t -> showInChat(result, t.displayId(), true));
            return;
        }

        try {
            String jsonPart = extractJsonArray(result);
            String[] results = gson.fromJson(jsonPart, String[].class);

            for (int i = 0; i < tasks.size(); i++) {
                PendingTask t = tasks.get(i);
                if (results != null && i < results.length) {
                    translationCache.put(t.text(), results[i]);
                    showInChat(results[i], t.displayId(), false);
                } else {
                    showInChat("§c" + I18nHelper.translate("translex.error.parse.mismatch_short"), t.displayId(), true);
                }
            }
        } catch (Exception e) {
            handleParseError(result, tasks);
        }
    }

    private void showInChat(String translatedText, String displayId, boolean isError) {
        MinecraftClient.getInstance().execute(() -> {
            if (MinecraftClient.getInstance().inGameHud == null) return;

            // 1. 构造 Translex (绿色)
            MutableText modName = Text.literal(I18nHelper.translate("translex.prefix.name"))
                    .formatted(Formatting.GREEN);

            // 2. 构造 » (蓝色)
            MutableText separator = Text.literal(I18nHelper.translate("translex.prefix.separator"))
                    .formatted(Formatting.BLUE);

            // 3. 组合前缀并设置悬停
            MutableText prefix = modName.append(separator);
            String hoverKey = isError ? "translex.error.hover" : "translex.hover.metadata";
            prefix.setStyle(prefix.getStyle().withHoverEvent(
                    new HoverEvent.ShowText(Text.literal(I18nHelper.translate(hoverKey, displayId)))
            ));

            // 4. 构造正文内容 (显式设为白色或根据错误设为红色)
            MutableText content = Text.literal(translatedText);

            if (isError) {
                content.formatted(Formatting.RED);
            } else {
                // 显式设置为 WHITE 确保不会继承前面的 GREEN
                content.formatted(Formatting.WHITE);
            }

            // 5. 拼接：[前缀(绿+蓝)] + [内容(白/红)]
            MutableText finalMessage = prefix.append(content);

            MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(finalMessage);
        });
    }

    private String extractJsonArray(String input) {
        // 移除所有颜色代码和 Markdown 干扰
        String cleaned = input.replaceAll("§[0-9a-fk-or]", "").replaceAll("```json|```", "").trim();
        int first = cleaned.indexOf("[");
        int last = cleaned.lastIndexOf("]");
        return (first != -1 && last != -1) ? cleaned.substring(first, last + 1) : cleaned;
    }

    private void handleParseError(String raw, List<PendingTask> tasks) {
        MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().keyboard.setClipboard(raw));
        tasks.forEach(t -> showInChat("§c" + I18nHelper.translate("translex.error.parse.json"), t.displayId(), true));
    }

    public void setCacheFile(File f) { this.cacheFile = f; }
    public void loadCache() {
        if (cacheFile == null || !cacheFile.exists()) return;
        try (FileReader r = new FileReader(cacheFile)) {
            Map<String, String> m = gson.fromJson(r, new TypeToken<ConcurrentHashMap<String, String>>(){}.getType());
            if (m != null) translationCache.putAll(m);
        } catch (Exception ignored) {}
    }
}