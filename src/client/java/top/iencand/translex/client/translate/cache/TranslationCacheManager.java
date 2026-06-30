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
import java.util.regex.Matcher;
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
        cache.setMaxSize(ModConfig.get().cacheMaxEntries);
        cache.load(file);
    }

    /** 配置重载后刷新内存上限（供 ConfigReloadListener 调用）。 */
    public void applyConfig() {
        cache.setMaxSize(ModConfig.get().cacheMaxEntries);
    }

    // ===============================================================
    // 词库查询（公开给 TranslationSplitter 使用）
    // ===============================================================

    public String applyGlossary(String text) {
        return applyGlossaryStatic(text);
    }

    /** 静态词库替换：供不持有缓存管理器的聊天管线复用（词库为静态、无状态）。 */
    public static String applyGlossaryStatic(String text) {
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

    /** 删除指定缓存键的条目（用于清除损坏的缓存数据）。 */
    public void removeByCacheKey(String cacheKey) {
        if (cacheKey == null) return;
        cache.removeByNormKey(cacheKey);
    }

    // ===============================================================
    // 模板内词库替换（保留 &lt;sN&gt; 颜色结构）
    // ===============================================================

    private static final Pattern TEMPLATE_TAG = Pattern.compile("<s(\\d+)>(.*?)</s\\1>", Pattern.DOTALL);

    /**
     * 对带 &lt;sN&gt; 标签的模板文本应用词库替换，仅替换标签内的可见文本，
     * 保留所有标签和占位符结构。这样词库处理完的行可以直接走
     * {@code LineTemplate.buildText()} 渲染，颜色完整。
     *
     * @param template 带样式标签的模板，如 {@code <s0>Defense: </s0><s1>{0}</s1><s2>(+30)</s2>}
     * @return 标签间内容经词库替换后的模板，如 {@code <s0>防御力: </s0><s1>{0}</s1><s2>(+30)</s2>}
     */
    public static String applyGlossaryToTemplate(String template) {
        if (template == null) return null;
        StringBuilder result = new StringBuilder(template.length() + 32);
        Matcher m = TEMPLATE_TAG.matcher(template);
        while (m.find()) {
            String content = m.group(2);
            String glossed = applyGlossaryStatic(content);
            m.appendReplacement(result,
                    "<s" + m.group(1) + ">" + Matcher.quoteReplacement(glossed) + "</s" + m.group(1) + ">");
        }
        m.appendTail(result);
        return result.toString();
    }

    /** 检查带标签的模板经词库替换后是否仍含英文（决定是否需要 AI 翻译）。 */
    public static boolean templateStillHasEnglish(String template) {
        if (template == null) return false;
        // 去掉所有 <sN> 标签，检查剩余文本是否含英文字母
        return Pattern.compile("[a-zA-Z]").matcher(template.replaceAll("</?s\\d+>", "")).find();
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
        Map<String, String> snapshot = cache.snapshot();
        for (int shardId : dirtyShards) {
            Map<String, String> shardData = new HashMap<>();
            snapshot.forEach((k, v) -> {
                if (cache.getShardId(k) == shardId) {
                    shardData.put(k, v);
                }
            });
            cache.saveShard(cacheFile, shardId, shardData);
        }
        dirtyShards.clear();
        // 内存上限由 TranslationCache 的 LRU 淘汰自动维护，无需在此粗暴全清。
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
