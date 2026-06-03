package top.iencand.translex.client.translate;

import net.minecraft.item.ItemStack;
import top.iencand.translex.client.cache.TemporaryTooltipCache;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.web.ConsoleBroadcaster;
import top.iencand.translex.client.web.MetricsCollector;
import top.iencand.translex.client.web.TokenCounter;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Lightweight entry-point coordinator.
 *
 * <p>Flow: glossary → cache-check → dispatch → render + store.
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

    // ---- chat message (skip cache) ----

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

    // ---- item lore (full tooltip + splitter preprocessing) ----

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

    // ---- free-text (/translex text) ----

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

    // ---- output mode ----

    private void handleOutputMode(String translated, String itemId, ItemStack stack) {
        String mode = ModConfig.get().outputMode;
        List<String> lines = Arrays.asList(translated.split("\n"));
        switch (mode) {
            case "permanent" -> {
                if (itemId != null && !itemId.isEmpty()) presetLibrary.putTooltip(itemId, lines);
            }
            case "temporary" -> {
                if (stack != null && !stack.isEmpty()) TemporaryTooltipCache.put(stack, lines);
            }
        }
    }

    // ---- helpers ----

    private static String mergePreTranslated(TranslationSplitter.SplitResult split) {
        return String.join("\n", split.preTranslated());
    }

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
