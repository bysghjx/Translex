package top.iencand.translex.client.Translate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Type;

/**
 * 负责缓存的底层存储与磁盘 IO。
 * 采用分片存储 (Sharding) 以优化大批量数据的读写性能。
 */
public class TranslationCache {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // 内存核心存储：Key 为标准化后的原文，Value 为译文
    private final Map<String, String> storage = new ConcurrentHashMap<>();

    /**
     * 同步加载所有分片文件
     * @param baseDir 存放 shard_x.json 的文件夹目录
     */
    public void load(File baseDir) {
        if (!baseDir.exists()) {
            System.out.println("[Translex-Debug] 目录不存在，创建新目录: " + baseDir.getAbsolutePath());
            baseDir.mkdirs();
            return;
        }

        File[] files = baseDir.listFiles((dir, name) -> name.startsWith("shard_") && name.endsWith(".json"));
        if (files == null || files.length == 0) {
            System.out.println("[Translex-Debug] 警告：在目录内未找到任何 shard_*.json 文件！");
            return;
        }

        int totalLoaded = 0;
        for (File file : files) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                Type type = new TypeToken<Map<String, String>>(){}.getType();
                Map<String, String> shardData = GSON.fromJson(reader, type);

                if (shardData != null) {
                    storage.putAll(shardData);
                    totalLoaded += shardData.size();
                    // 打印前两个 Key 看看长什么样，检查是否有“补丁污染”
                    shardData.keySet().stream().limit(1).forEach(k ->
                            System.out.println("[Translex-Debug] 样本加载 Key: [" + k + "]"));
                }
            } catch (Exception e) {
                System.err.println("[Translex-Debug] 加载文件 " + file.getName() + " 失败: " + e.getMessage());
            }
        }
        System.out.println("[Translex-Debug] 缓存同步就绪！内存条目总数: " + storage.size());
    }

    /**
     * 获取缓存条目
     */
    public String get(String original) {
        if (original == null) return null;
        String normKey = normalize(original);
        String result = storage.get(normKey);

        // 只有在没命中的时候输出，防止刷屏，或者根据需要开启全量输出
        if (result == null) {
            // System.out.println("[Translex-Debug] 缓存未命中: [" + normKey + "]");
        } else {
            System.out.println("[Translex-Debug] 缓存命中成功！Key: [" + normKey.substring(0, Math.min(normKey.length(), 20)) + "...]");
        }
        return result;
    }

    /**
     * 存入缓存条目
     */
    public void put(String original, String translated) {
        if (original == null || translated == null) return;
        storage.put(normalize(original), translated);
    }

    /**
     * 标准化 Key：去除颜色代码、首尾空格，确保匹配一致性
     */
    public String normalize(String input) {
        if (input == null) return "";
        // 移除 Minecraft 颜色代码 (§0-§f, §l-§o, §r)
        return input.replaceAll("§[0-9a-fk-or]", "").trim();
    }

    /**
     * 根据 Key 计算其所属的分片 ID (0-15)
     */
    public int getShardId(String key) {
        // 使用 Math.abs 确保索引永远为正数
        return Math.abs(key.hashCode()) % 16;
    }

    /**
     * 保存单个分片到磁盘
     */
    public void saveShard(File baseDir, int shardId, Map<String, String> data) {
        if (data == null || data.isEmpty()) return;

        File shardFile = new File(baseDir, "shard_" + shardId + ".json");
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(shardFile), StandardCharsets.UTF_8)) {
            GSON.toJson(data, writer);
        } catch (IOException e) {
            System.err.println("[Translex] 保存分片 " + shardId + " 失败: " + e.getMessage());
        }
    }

    public Map<String, String> getCacheMap() {
        return storage;
    }
}