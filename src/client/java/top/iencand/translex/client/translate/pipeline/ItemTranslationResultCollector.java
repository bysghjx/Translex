package top.iencand.translex.client.translate.pipeline;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.translate.cache.TemporaryTooltipCache;
import top.iencand.translex.client.translate.cache.TranslationCacheManager;
import top.iencand.translex.client.translate.model.ItemPresetLibrary;
import top.iencand.translex.client.translate.model.TranslationCacheEntry;
import top.iencand.translex.client.translate.model.TranslationFormat;
import top.iencand.translex.client.translate.model.TranslationFormatRegistry;
import top.iencand.translex.client.translate.render.ChatRenderer;
import top.iencand.translex.client.util.TooltipKeyUtil;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Completes submitted item translation tasks and owns all output side effects.
 *
 * <p>The pipeline plans work; this collector validates responses, updates caches,
 * rebuilds styled components, de-duplicates errors, renders the completed batch,
 * and persists the selected output mode.</p>
 */
final class ItemTranslationResultCollector {

    private static final Logger LOGGER = LoggerFactory.getLogger("Translex/ItemPipeline");
    private static final String ERROR_PREFIX = "\u00A7c";

    private final List<Component> originalLines;
    private final String itemId;
    private final ItemStack stack;
    private final Component[] results;
    private final String[] storedTemplates;
    private final String displayId;
    private final Set<Integer> aiIndices;
    private final boolean debug;
    private final ChatRenderer renderer;
    private final TranslationCacheManager cacheManager;
    private final ItemPresetLibrary presetLibrary;
    private final tsp.RecoveryStats recoveryStats;
    private final Set<Integer> completed = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean errorReported = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);

    ItemTranslationResultCollector(
            List<Component> originalLines,
            String itemId,
            ItemStack stack,
            Component[] results,
            String[] storedTemplates,
            String displayId,
            Set<Integer> aiIndices,
            boolean debug,
            ChatRenderer renderer,
            TranslationCacheManager cacheManager,
            ItemPresetLibrary presetLibrary,
            tsp.RecoveryStats recoveryStats) {
        this.originalLines = originalLines;
        this.itemId = itemId;
        this.stack = stack;
        this.results = results;
        this.storedTemplates = storedTemplates;
        this.displayId = displayId;
        this.aiIndices = aiIndices;
        this.debug = debug;
        this.renderer = renderer;
        this.cacheManager = cacheManager;
        this.presetLibrary = presetLibrary;
        this.recoveryStats = recoveryStats;
    }

    void finishImmediately() {
        finish();
    }

    void attach(List<ItemTranslationTask> tasks) {
        for (ItemTranslationTask task : tasks) {
            task.future().thenAccept(translated -> complete(task, translated));
        }
    }

    private void complete(ItemTranslationTask task, String translated) {
        if (task.isParagraph()) {
            completeParagraph(task, translated);
        } else {
            completeLine(task, translated);
        }
        maybeFinish();
    }

    private void completeLine(ItemTranslationTask task, String translated) {
        int index = task.startLine();
        Component original = task.original().component();
        TranslationFormat format = TranslationFormat.forId(task.formatId());

        if (isRequestError(translated)) {
            reportErrorOnce(translated);
            results[index] = original;
            storedTemplates[index] = cacheEntry(task, task.template());
        } else {
            String validated = TranslationFormatRegistry.usesTspSyntax(task.formatId())
                    ? translated
                    : ItemTranslationValidator.validateTranslation(task.template(), translated, index);
            if (validated != null) {
                cacheManager.putByCacheKey(task.cacheKey(), cacheEntry(task, validated));
                Component decoded = format.decode(validated, task.original(), false,
                        task.registryHash(), recoveryStats);
                results[index] = decoded != null ? decoded : original;
                storedTemplates[index] = cacheEntry(task, validated);
                ItemTranslationValidator.learnFromTranslation(
                        task.template(), validated, task.formatId());
            } else {
                results[index] = original;
                storedTemplates[index] = cacheEntry(task, task.template());
            }
        }
        completed.add(index);
    }

    private void completeParagraph(ItemTranslationTask task, String translated) {
        int start = task.startLine();
        if (isRequestError(translated)) {
            reportErrorOnce(translated);
            fallbackParagraph(task);
            return;
        }

        try {
            TranslationFormat format = TranslationFormat.forId(task.formatId());
            Component translatedParagraph = format.decode(
                    translated, task.original(), true, task.registryHash(), recoveryStats);
            if (translatedParagraph == null) {
                translatedParagraph = task.original().component();
            }

            cacheManager.putByCacheKey(task.cacheKey(), cacheEntry(task, translated));
            ItemTranslationValidator.learnFromTranslation(
                    task.template(), translated, task.formatId());
            results[start] = translatedParagraph;
            storedTemplates[start] = cacheEntry(task, translated.replace("\n", " "));
            for (int line = start + 1; line < task.endLineExclusive(); line++) {
                results[line] = null;
                storedTemplates[line] = "";
            }
            markParagraphCompleted(task);
        } catch (Exception exception) {
            LOGGER.warn("Paragraph render failed at lines {}-{}, fallback to original: {}",
                    start, task.endLineExclusive() - 1, exception.getMessage());
            fallbackParagraph(task);
        }
    }

    private void fallbackParagraph(ItemTranslationTask task) {
        for (int line = task.startLine(); line < task.endLineExclusive(); line++) {
            results[line] = originalLines.get(line);
            storedTemplates[line] = cacheEntry(task, task.template());
            completed.add(line);
        }
    }

    private void markParagraphCompleted(ItemTranslationTask task) {
        for (int line = task.startLine(); line < task.endLineExclusive(); line++) {
            completed.add(line);
        }
    }

    private void maybeFinish() {
        if (completed.size() == aiIndices.size()) {
            finish();
        }
    }

    private void finish() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        if (debug) {
            LOGGER.info("StyleDump END - item={}", itemId);
        }
        renderer.renderResult(joinTexts(originalLines), joinTextsSafe(results), displayId);
        handleOutputMode(String.join("\n", storedTemplates));
    }

    private boolean isRequestError(String translated) {
        return translated != null && translated.startsWith(ERROR_PREFIX);
    }

    private void reportErrorOnce(String translated) {
        if (errorReported.compareAndSet(false, true)) {
            renderer.renderError(translated, displayId);
        }
    }

    private String cacheEntry(ItemTranslationTask task, String template) {
        return new TranslationCacheEntry(
                task.formatId(), template, task.registryHash()).toJson();
    }

    private void handleOutputMode(String translated) {
        List<String> lines = Arrays.asList(translated.split("\n", -1));
        switch (ModConfig.get().outputMode) {
            case "permanent" -> {
                if (stack != null && !stack.isEmpty()) {
                    String key = TooltipKeyUtil.buildKey(stack, originalLines);
                    if (key != null) {
                        presetLibrary.putTooltip(key, lines);
                    }
                }
            }
            case "temporary" -> {
                if (stack != null && !stack.isEmpty()) {
                    TemporaryTooltipCache.put(stack, originalLines, lines);
                }
            }
        }
    }

    private static String joinTexts(List<Component> lines) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            joined.append(lines.get(i).getString());
            if (i < lines.size() - 1) {
                joined.append("\n");
            }
        }
        return joined.toString();
    }

    private static String joinTextsSafe(Component[] lines) {
        StringBuilder joined = new StringBuilder();
        boolean first = true;
        for (Component line : lines) {
            if (line == null) {
                continue;
            }
            if (!first) {
                joined.append("\n");
            }
            joined.append(line.getString());
            first = false;
        }
        return joined.toString();
    }
}
