package top.iencand.translex.client.Translate;

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
 * 缓存管理策略层。
 * 核心逻辑：枚举硬映射 (Glossary) > 内存 L1 > 磁盘分片 L2 > AI 翻译。
 */
public class TranslationCacheManager {
    private final TranslationCache cache = new TranslationCache();
    private final ScheduledExecutorService saveScheduler = Executors.newSingleThreadScheduledExecutor();

    // 追踪哪些分片发生了更新，用于增量异步保存
    private final Set<Integer> dirtyShards = ConcurrentHashMap.newKeySet();

    // 预编译术语正则：\b 单词边界防止子串误匹配（如 "Health" 不匹配 "Healthier"）
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
        // 每 5 分钟执行一次异步增量保存任务
        saveScheduler.scheduleAtFixedRate(this::autoSave, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * 初始化持久化层
     */
    public void init(File file) {
        this.cacheFile = file;
        cache.load(file);
    }

    /**
     * 获取翻译结果（由 TranslationManager 调用）
     */
    public String get(String original) {
        if (original == null || original.isEmpty()) return null;

        // 1. 术语表替换（省 token）
        String glossed = applyGlossary(original);
        if (!glossed.equals(original)) {
            return glossed;
        }

        // 2. 缓存查询
        String cached = cache.get(original);
        if (cached != null) {
            return cached;
        }

        return null;
    }

    private String applyGlossary(String text) {
        String result = text;
        for (Map.Entry<Pattern, String> entry : GLOSSARY_PATTERNS.entrySet()) {
            result = entry.getKey().matcher(result).replaceAll(entry.getValue());
        }
        return result;
    }

    /**
     * 存入翻译结果（由 TranslationManager 调用）
     */
    public void put(String original, String translated) {
        if (original == null || translated == null) return;

        // 标准化后存入
        String normKey = cache.normalize(original);
        cache.put(normKey, translated);

        // 标记脏分片
        dirtyShards.add(cache.getShardId(normKey));
    }

    /**
     * 定时自动保存任务
     */
    private void autoSave() {
        if (cacheFile == null || dirtyShards.isEmpty()) return;
        forceSave();
    }

    /**
     * 强制执行增量保存与内存回收
     */
    public void forceSave() {
        if (cacheFile == null || dirtyShards.isEmpty()) return;

        // 仅保存发生变动的分片 (Incremental Save)
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

        // --- 优化方向：清理机制 (Memory Eviction) ---
        // 如果内存中的条目超过 10,000 条，清空内存以防止 OOM
        // 下次 get 时，系统会根据需要重新从磁盘分片 Load (局部缓存思想)
        if (cache.getCacheMap().size() > 10000) {
            cache.getCacheMap().clear();
        }
    }

    /**
     * 判定是否为纯属性数值行
     */
    private boolean isPureAttributeLine(String text) {
        // 结构通常包含冒号和数字，例如 "力量: +10" 或 "Crit Damage: 50%"
        return text.contains(":") && text.matches(".*\\d+.*");
    }

    /**
     * 关闭管理器，确保数据不丢失
     */
    public void shutdown() {
        System.out.println("[Translex] 正在执行关闭前的数据强制保存...");
        try {
            // 1. 立即停止调度器，不再接受新任务
            saveScheduler.shutdown();

            // 2. 强制执行保存逻辑（确保它是同步的）
            forceSave();

            // 3. 等待调度器完全关闭（最多等 1 秒）
            if (!saveScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                saveScheduler.shutdownNow();
            }
            System.out.println("[Translex] 缓存保存成功！");
        } catch (Exception e) {
            System.err.println("[Translex] 关闭时保存失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}