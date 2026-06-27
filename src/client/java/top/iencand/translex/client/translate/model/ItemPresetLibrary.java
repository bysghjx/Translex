package top.iencand.translex.client.translate.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 永久预设库，将 SkyBlock 物品 ID 映射到完整的翻译后说明文本。
 * 用于 "permanent" 输出模式和工具提示替换 Mixin。
 *
 * <p>在内存中存储 {@code Map<String, List<String>>}，并将数据序列化到
 * {@code item_cache.json} 文件中。</p>
 */
public class ItemPresetLibrary {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemPresetLibrary");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // -------- 存储结构：itemId → 说明行列表（字符串形式用于 JSON 序列化） --------
    private final ConcurrentHashMap<String, List<String>> items = new ConcurrentHashMap<>();
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PresetSaver");
        t.setDaemon(true);
        return t;
    });
    private File file;

    // -------- Mixin 使用的单例 --------
    private static ItemPresetLibrary INSTANCE;

    public static ItemPresetLibrary getInstance() {
        return INSTANCE;
    }

    // ===============================================================
    // 加载 / 保存
    // ===============================================================

    public void load() {
        INSTANCE = this;
        file = getPresetFile();
        if (!file.exists()) return;

        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            var type = new TypeToken<ConcurrentHashMap<String, List<String>>>() {}.getType();
            ConcurrentHashMap<String, List<String>> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                items.putAll(loaded);
                LOGGER.info("Loaded {} item presets from {}", items.size(), file.getAbsolutePath());
            }
        } catch (Exception e) {
            LOGGER.error("Error loading item presets: {}", e.getMessage());
        }
    }

    private void saveAsync() {
        saveExecutor.execute(this::save);
    }

    private synchronized void save() {
        if (file == null) file = getPresetFile();
        file.getParentFile().mkdirs();
        try {
            String json = GSON.toJson(items);
            Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Error saving item presets: {}", e.getMessage());
        }
    }

    public void shutdown() {
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                saveExecutor.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            saveExecutor.shutdownNow();
        }
    }

    private static File getPresetFile() {
        File configDir = FabricLoader.getInstance().getConfigDir().toFile();
        File modDir = new File(configDir, "translex");
        modDir.mkdirs();
        return new File(modDir, "item_cache.json");
    }

    // ===============================================================
    // 访问接口 — 供 Mixin 使用
    // ===============================================================

    /**
     * 返回完整的翻译后工具提示，以 {@code List<String>} 形式（每行纯文本）。
     * 由工具提示替换 Mixin 调用，样式从原始行中获取。
     */
    public List<String> getTooltip(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        return items.get(itemId);
    }

    /**
     * 为指定物品 ID 存储完整的翻译后工具提示。
     * 由 TranslationManager 在 permanent 模式下成功翻译后调用。
     */
    public void putTooltip(String itemId, List<String> lines) {
        if (itemId == null || itemId.isEmpty() || lines == null) return;
        items.put(itemId, lines);
        saveAsync();
    }

    // ===============================================================
    // 旧版兼容 — 由 ChatRenderer / 命令路径使用
    // ===============================================================

    /** 获取预设库中的条目（合并后的字符串，用于聊天渲染） */
    public ItemPreset get(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        List<String> lines = items.get(itemId);
        if (lines == null) return null;
        return new ItemPreset(lines.get(0), lines);
    }

    /** 获取预设库中的说明文本，以灰色格式化的 List&lt;Component&gt; 返回（用于聊天渲染） */
    public List<Component> getAsText(String itemId) {
        List<String> lines = getTooltip(itemId);
        if (lines == null) return null;
        List<Component> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        return result;
    }

    /** 存储单字符串格式的预设（向后兼容） */
    public void put(String itemId, String nameZh, String loreZh) {
        if (itemId == null || itemId.isEmpty()) return;
        List<String> lines = (loreZh != null)
                ? Arrays.asList(loreZh.split("\n"))
                : List.of();
        items.put(itemId, lines);
        saveAsync();
    }

    /** 存储多行说明文本的预设 */
    public void putLines(String itemId, String nameZh, List<String> loreLines) {
        if (itemId == null || itemId.isEmpty()) return;
        items.put(itemId, loreLines);
        saveAsync();
    }

    /** 按 ID 移除单个物品预设 */
    public void remove(String itemId) {
        if (itemId == null) return;
        items.remove(itemId);
        saveAsync();
    }

    /** 清空所有预设 */
    public void clear() {
        items.clear();
        saveAsync();
    }

    // ===============================================================
    // 物品预设内部类
    // ===============================================================

    public static class ItemPreset {
        public String name;
        public List<String> loreLines;

        public ItemPreset(String name, List<String> loreLines) {
            this.name = name;
            this.loreLines = loreLines;
        }

        public String getLore() {
            return loreLines != null ? String.join("\n", loreLines) : "";
        }
    }
}
