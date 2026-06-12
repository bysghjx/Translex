package top.iencand.translex.client.translate.cache;

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
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.translate.model.SkyBlockTerm;
import top.iencand.translex.client.web.ConsoleBroadcaster;

/**
 * 缓存管理器，提供词库覆盖、数字规范化键值和增量磁盘分片持久化。
 *
 * <p>查找顺序：词库 → 内存 (L1) → 磁盘分片 (L2) → AI。</p>
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

    // ===============================================================
    // 词库查询（公开给 TranslationSplitter 使用）
    // ===============================================================

    public String applyGlossary(String text) {
        if (text == null) return null;
        String result = text;
        for (Map.Entry<Pattern, String> entry : GLOSSARY_PATTERNS.entrySet()) {
            result = entry.getKey().matcher(result).replaceAll(entry.getValue());
        }
        return result;
    }

    // ===============================================================
    // 缓存键生成（含数字规范化）
    // ===============================================================

    /**
     * 构建规范化的缓存键：去除颜色码 → 转小写 → 压缩空白。
     * 注意：不进行数字规范化，价格敏感的行必须精确匹配。
     */
    public String buildCacheKey(String original) {
        if (original == null) return "";
        return original.replaceAll("§[0-9a-fk-or]", "").trim()
                .toLowerCase().replaceAll("\\s+", " ");
    }

    // ===============================================================
    // 缓存访问（模板化）
    // ===============================================================

    /** 通过预计算的缓存键查询。 */
    public String getByCacheKey(String cacheKey) {
        if (cacheKey == null || cacheKey.isEmpty()) return null;
        if (ModConfig.get().debug) return null; // debug mode: force cache miss
        return cache.getByNormKey(cacheKey);
    }

    /** 用预计算的缓存键存储。 */
    public void putByCacheKey(String cacheKey, String translated) {
        if (cacheKey == null || translated == null) return;
        if (ModConfig.get().debug) return; // debug mode: don't cache
        cache.putByNormKey(cacheKey, translated);
        dirtyShards.add(cache.getShardId(cacheKey));
    }

    // ===============================================================
    // 旧版兼容（不含数字规范化）
    // ===============================================================

    /** @deprecated 请使用 {@link #getByCacheKey}({@link #buildCacheKey}(original)) 替代。 */
    @Deprecated
    public String get(String original) {
        if (original == null || original.isEmpty()) return null;
        String glossed = applyGlossary(original);
        if (!glossed.equals(original)) return glossed;
        return cache.get(original);
    }

    /** @deprecated 请使用 {@link #putByCacheKey} 替代。 */
    @Deprecated
    public void put(String original, String translated) {
        if (original == null || translated == null) return;
        String normKey = cache.normalize(original);
        cache.put(normKey, translated);
        dirtyShards.add(cache.getShardId(normKey));
    }

    // ===============================================================
    // 持久化
    // ===============================================================

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
        ConsoleBroadcaster.broadcast("INFO", "Forcing cache save before shutdown...");
        try {
            saveScheduler.shutdown();
            forceSave();
            if (!saveScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                saveScheduler.shutdownNow();
            }
            ConsoleBroadcaster.broadcast("INFO", "Cache saved successfully.");
        } catch (Exception e) {
            ConsoleBroadcaster.broadcast("ERROR", "Shutdown save failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
