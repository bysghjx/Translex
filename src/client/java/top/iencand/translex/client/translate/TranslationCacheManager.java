package top.iencand.translex.client.translate;

import top.iencand.translex.client.util.NumberNormalizer;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Cache management with glossary overlay, number-normalized keys,
 * and incremental disk shard persistence.
 *
 * <p>Lookup order: Glossary → Memory (L1) → Disk shard (L2) → AI.</p>
 */
public class TranslationCacheManager {
    private final TranslationCache cache = new TranslationCache();
    private final ScheduledExecutorService saveScheduler = Executors.newSingleThreadScheduledExecutor();

    private final Set<Integer> dirtyShards = ConcurrentHashMap.newKeySet();

    private static final Map<Pattern, String> GLOSSARY_PATTERNS = buildGlossaryPatterns();

    private static Map<Pattern, String> buildGlossaryPatterns() {
        Map<Pattern, String> map = new LinkedHashMap<>();
        for (SkyBlockTerm term : SkyBlockTerm.VALUES) {
            map.put(Pattern.compile("\\b" + Pattern.quote(term.getEn()) + "\\b"), term.getZh());
        }
        return map;
    }

    private File cacheFile;

    public TranslationCacheManager() {
        saveScheduler.scheduleAtFixedRate(this::autoSave, 1, 1, TimeUnit.MINUTES);
    }

    public void init(File file) {
        this.cacheFile = file;
        cache.load(file);
    }

    // ---------------------------------------------------------------
    // Glossary (public so TranslationSplitter can use it)
    // ---------------------------------------------------------------

    public String applyGlossary(String text) {
        if (text == null) return null;
        String result = text;
        for (Map.Entry<Pattern, String> entry : GLOSSARY_PATTERNS.entrySet()) {
            result = entry.getKey().matcher(result).replaceAll(entry.getValue());
        }
        return result;
    }

    // ---------------------------------------------------------------
    // Cache key generation (with number normalization)
    // ---------------------------------------------------------------

    /**
     * Build a normalized cache key: strip colors → lowercase → collapse
     * whitespace → normalize digits to {num}.
     */
    public String buildCacheKey(String original) {
        if (original == null) return "";
        String step1 = original.replaceAll("§[0-9a-fk-or]", "").trim();
        String step2 = step1.toLowerCase().replaceAll("\\s+", " ");
        String step3 = step2.replaceAll("\\d+", "{num}");
        return step3;
    }

    // ---------------------------------------------------------------
    // Cache access (template-based)
    // ---------------------------------------------------------------

    /** Look up by pre-computed cache key. */
    public String getByCacheKey(String cacheKey) {
        if (cacheKey == null || cacheKey.isEmpty()) return null;
        return cache.getByNormKey(cacheKey);
    }

    /** Store with pre-computed cache key. */
    public void putByCacheKey(String cacheKey, String translated) {
        if (cacheKey == null || translated == null) return;
        cache.putByNormKey(cacheKey, translated);
        dirtyShards.add(cache.getShardId(cacheKey));
    }

    // ---------------------------------------------------------------
    // Legacy compat (without number normalization)
    // ---------------------------------------------------------------

    /** @deprecated Use {@link #getByCacheKey}({@link #buildCacheKey}(original)) instead. */
    @Deprecated
    public String get(String original) {
        if (original == null || original.isEmpty()) return null;
        String glossed = applyGlossary(original);
        if (!glossed.equals(original)) return glossed;
        return cache.get(original);
    }

    /** @deprecated Use {@link #putByCacheKey} instead. */
    @Deprecated
    public void put(String original, String translated) {
        if (original == null || translated == null) return;
        String normKey = cache.normalize(original);
        cache.put(normKey, translated);
        dirtyShards.add(cache.getShardId(normKey));
    }

    // ---------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------

    private void autoSave() {
        if (cacheFile == null || dirtyShards.isEmpty()) return;
        forceSave();
    }

    public void forceSave() {
        if (cacheFile == null || dirtyShards.isEmpty()) return;
        for (int shardId : dirtyShards) {
            Map<String, String> shardData = new HashMap<>();
            cache.getCacheMap().forEach((k, v) -> {
                if (cache.getShardId(k) == shardId) {
                    shardData.put(k, v);
                }
            });
            cache.saveShard(cacheFile, shardId, shardData);
        }
        dirtyShards.clear();

        if (cache.getCacheMap().size() > 10000) {
            cache.getCacheMap().clear();
        }
    }

    public void shutdown() {
        System.out.println("[Translex] Forcing cache save before shutdown...");
        try {
            saveScheduler.shutdown();
            forceSave();
            if (!saveScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                saveScheduler.shutdownNow();
            }
            System.out.println("[Translex] Cache saved successfully.");
        } catch (Exception e) {
            System.err.println("[Translex] Shutdown save failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
