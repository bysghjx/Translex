package top.iencand.translex.client.translate;

import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import top.iencand.translex.client.translate.cache.TemporaryTooltipCache;
import top.iencand.translex.client.translate.cache.TranslationCacheManager;
import top.iencand.translex.client.translate.model.ItemPresetLibrary;
import top.iencand.translex.client.translate.model.LineTemplate;
import top.iencand.translex.client.translate.model.StyleCodec;
import top.iencand.translex.client.translate.render.ChatRenderer;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.web.ConsoleBroadcaster;
import top.iencand.translex.client.web.MetricsCollector;
import top.iencand.translex.client.web.TokenCounter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 翻译管理器，作为翻译流程的轻量级入口协调器。
 *
 * <p>核心流程：词库预处理 → 缓存检查 → 网络分发 → 渲染输出 + 存储。
 */
public class TranslationManager {

    private final TranslationSplitter splitter = new TranslationSplitter();
    private final TranslationDispatcher dispatcher = new TranslationDispatcher();
    private final TranslationCacheManager cacheManager = new TranslationCacheManager();
    private final ChatRenderer renderer = new ChatRenderer();
    private final ItemPresetLibrary presetLibrary = new ItemPresetLibrary();

    public TranslationManager() {
        ModConfig.addListener(config -> {});
        presetLibrary.load();
    }

    public void initializePersistence(File file) {
        if (file != null) cacheManager.init(file);
    }

    // ===============================================================
    // 聊天消息翻译（不查缓存，直接发送）
    // ===============================================================

    public void translateChatMessageAsync(String text, String displayId) {
        if (text == null || text.isBlank()) return;
        TranslationSplitter.SplitResult split = splitter.split(text, cacheManager::applyGlossary);
        if (!split.needsTranslation()) {
            renderer.renderResult(text, mergePreTranslated(split), displayId);
            return;
        }
        dispatcher.submit(split.untranslatedText())
                .thenAccept(translated -> {
                    String full = splitter.merge(split, translated);
                    renderer.renderResult(text, full, displayId);
                });
    }

    // ===============================================================
    // 物品说明翻译（逐行、模板化处理）
    // ===============================================================

    /**
     * 按行翻译物品说明（模板模式）。
     * 每行先检查是否为纯中文（无需翻译），再查缓存，最后提交 AI。
     * 支持并行处理各行，并在全部完成后统一渲染。
     */
    public void translateItemLoreTemplates(List<Text> originalLines, String itemId,
                                            String itemDisplayName, ItemStack stack) {
        if (originalLines == null || originalLines.isEmpty()) return;
        int n = originalLines.size();

        Text[] result = new Text[n];
        String[] storedTemplates = new String[n];  // plain translated template for output modes
        String displayId = "IL_" + System.currentTimeMillis();

        record Pending(int index, CompletableFuture<String> future, LineTemplate tmpl, String cacheKey) {}
        List<Pending> pending = new ArrayList<>();
        java.util.Set<Integer> aiIndices = new java.util.LinkedHashSet<>();

        for (int i = 0; i < n; i++) {
            LineTemplate tmpl = LineTemplate.fromText(originalLines.get(i));
            String lineText = originalLines.get(i).getString();
            String glossed = cacheManager.applyGlossary(lineText);

            if (!containsEnglish(glossed)) {
                result[i] = Text.literal(glossed).setStyle(
                        originalLines.get(i).getStyle().getColor() != null
                                ? originalLines.get(i).getStyle()
                                : findColor(originalLines.get(i)));
                storedTemplates[i] = glossed;
            } else {
                String ck = cacheManager.buildCacheKey(StyleCodec.stripTags(tmpl.getTemplate()));
                String cachedJson = cacheManager.getByCacheKey(ck);
                if (cachedJson != null) {
                    // Cache hit — merge cached non-number styles with current number styles
                    result[i] = tmpl.buildFromCache(cachedJson);
                    storedTemplates[i] = tmpl.getTemplate(); // fallback template for output mode
                } else {
                    result[i] = tmpl.apply(glossed);
                    storedTemplates[i] = glossed;
                    aiIndices.add(i);
                    pending.add(new Pending(i, dispatcher.submit(tmpl.getTemplate()), tmpl, ck));
                }
            }
        }

        if (aiIndices.isEmpty()) {
            renderer.renderResult(joinTexts(originalLines), joinTexts(List.of(result)), displayId);
            handleOutputMode(String.join("\n", storedTemplates), itemId, stack);
            return;
        }

        final java.util.Set<Integer> completed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (Pending p : pending) {
            final int idx = p.index();
            final LineTemplate tmpl = p.tmpl();
            p.future().thenAccept(translatedTemplate -> {
                // Store cache entry with style snapshots
                cacheManager.putByCacheKey(p.cacheKey(), tmpl.toCacheEntry(translatedTemplate));
                result[idx] = tmpl.buildText(translatedTemplate);
                storedTemplates[idx] = translatedTemplate;
                completed.add(idx);
                if (completed.size() == aiIndices.size()) {
                    renderer.renderResult(joinTexts(originalLines), joinTexts(List.of(result)), displayId);
                    handleOutputMode(String.join("\n", storedTemplates), itemId, stack);
                }
            });
        }
    }

    // ===============================================================
    // 物品说明翻译（完整文本 + 预处理分词器）
    // ===============================================================

    /**
     * 翻译完整的物品说明文本（旧版方式，使用分词器进行预处理）。
     * @deprecated 推荐使用 {@link #translateItemLoreTemplates} 以获得更好的模板化支持
     */
    @Deprecated
    public void translateItemLoreAsync(String fullTooltipText, String itemId, String itemDisplayName, ItemStack stack) {
        if (fullTooltipText == null || fullTooltipText.isBlank()) return;

        // 1. Split: apply glossary per line, separate pre-translated from AI-needed
        TranslationSplitter.SplitResult split = splitter.split(fullTooltipText, cacheManager::applyGlossary);
        String displayId = "IL_" + System.currentTimeMillis();

        // 2. If glossary handled everything, no AI needed
        if (!split.needsTranslation()) {
            String merged = mergePreTranslated(split);
            renderer.renderResult(fullTooltipText, merged, displayId);
            handleOutputMode(merged, itemId, stack);
            return;
        }

        // 3. Check cache for the untranslated portion
        String cacheKey = cacheManager.buildCacheKey(split.untranslatedText());
        String cached = cacheManager.getByCacheKey(cacheKey);
        if (cached != null) {
            String merged = splitter.merge(split, cached);
            renderer.renderResult(fullTooltipText, merged, displayId);
            handleOutputMode(merged, itemId, stack);
            return;
        }

        // 4. Dispatch only untranslated lines to AI
        dispatcher.submit(split.untranslatedText())
                .thenAccept(translated -> {
                    cacheManager.putByCacheKey(cacheKey, translated);
                    String merged = splitter.merge(split, translated);
                    renderer.renderResult(fullTooltipText, merged, displayId);
                    handleOutputMode(merged, itemId, stack);
                });
    }

    // ===============================================================
    // 自由文本翻译（/translex text 命令）
    // ===============================================================

    /**
     * 翻译用户输入的自由文本。
     * 流程：分词器处理 → 缓存检查（命中则统计节省的 token）→ AI 分发 → 渲染。
     */
    public void translateTextAsync(String text, String displayId) {
        if (text == null || text.isBlank()) return;
        TranslationSplitter.SplitResult split = splitter.split(text, cacheManager::applyGlossary);
        if (!split.needsTranslation()) {
            renderer.renderResult(text, mergePreTranslated(split), displayId);
            return;
        }
        String cacheKey = cacheManager.buildCacheKey(split.untranslatedText());
        String cached = cacheManager.getByCacheKey(cacheKey);
        if (cached != null) {
            long tokensSaved = TokenCounter.estimate(split.untranslatedText());
            MetricsCollector.get().recordLocalHitWithTokens(tokensSaved);
            ConsoleBroadcaster.broadcast("DEBUG",
                    "Cache hit — text (~" + tokensSaved + " tokens saved): "
                    + cacheKey.substring(0, Math.min(cacheKey.length(), 50)) + "...");
            String full = splitter.merge(split, cached);
            renderer.renderResult(text, full, displayId);
            return;
        }
        dispatcher.submit(split.untranslatedText())
                .thenAccept(translated -> {
                    cacheManager.putByCacheKey(cacheKey, translated);
                    String full = splitter.merge(split, translated);
                    renderer.renderResult(text, full, displayId);
                });
    }

    public void translateAsync(int id, String text, String playerPrefix) {
        translateChatMessageAsync(text, String.valueOf(id));
    }

    // ===============================================================
    // 输出模式处理
    // ===============================================================

    /**
     * 根据配置的输出模式处理翻译结果。
     * <ul>
     *   <li>permanent → 存入 ItemPresetLibrary（永久存储）</li>
     *   <li>temporary → 存入 TemporaryTooltipCache（临时存储）</li>
     * </ul>
     */
    private void handleOutputMode(String translated, String itemId, ItemStack stack) {
        String mode = ModConfig.get().outputMode;
        List<String> lines = Arrays.asList(translated.split("\n", -1));
        switch (mode) {
            case "permanent" -> {
                if (itemId != null && !itemId.isEmpty()) presetLibrary.putTooltip(itemId, lines);
            }
            case "temporary" -> {
                if (stack != null && !stack.isEmpty()) TemporaryTooltipCache.put(stack, lines);
            }
        }
    }

    // ===============================================================
    // 辅助方法
    // ===============================================================

    /**
     * 递归查找文本中第一个非空的颜色样式。
     */
    private static net.minecraft.text.Style findColor(Text text) {
        for (Text child : text.getSiblings()) {
            net.minecraft.text.Style cs = findColor(child);
            if (cs.getColor() != null) return cs;
        }
        return text.getStyle();
    }

    /** 将 Text 列表拼接为带换行符的字符串 */
    private static String joinTexts(List<Text> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i).getString());
            if (i < lines.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }

    /** 合并预处理（已由词库翻译）的行 */
    private static String mergePreTranslated(TranslationSplitter.SplitResult split) {
        return String.join("\n", split.preTranslated());
    }

    /** 检查文本中是否包含英文字母（判断是否需要 AI 翻译） */
    private static boolean containsEnglish(String text) {
        return text != null && text.matches("(?s).*[a-zA-Z].*");
    }

    public ItemPresetLibrary getPresetLibrary() { return presetLibrary; }
    public TranslationCacheManager getCacheManager() { return cacheManager; }

    public void shutdown() {
        if (cacheManager != null) cacheManager.shutdown();
        if (dispatcher != null) dispatcher.shutdown();
    }
}
