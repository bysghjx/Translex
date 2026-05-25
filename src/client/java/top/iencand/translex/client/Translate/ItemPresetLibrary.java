package top.iencand.translex.client.Translate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品 ID → 翻译结果的预置库。初始为空，翻译成功后自动录入。
 * 下次遇到同一物品 ID 直接命中，不再调用 AI。
 */
public class ItemPresetLibrary {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemPresetLibrary");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ConcurrentHashMap<String, ItemPreset> items = new ConcurrentHashMap<>();
    private File file;

    public void load() {
        file = getPresetFile();
        if (!file.exists()) return;

        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            var type = new TypeToken<ConcurrentHashMap<String, ItemPreset>>() {}.getType();
            ConcurrentHashMap<String, ItemPreset> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                items.putAll(loaded);
                LOGGER.info("Loaded {} item presets from {}", items.size(), file.getAbsolutePath());
            }
        } catch (Exception e) {
            LOGGER.error("Error loading item presets: {}", e.getMessage());
        }
    }

    public ItemPreset get(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        return items.get(itemId);
    }

    public void put(String itemId, String nameZh, String loreZh) {
        if (itemId == null || itemId.isEmpty()) return;
        items.put(itemId, new ItemPreset(nameZh, loreZh));
        saveAsync();
    }

    private void saveAsync() {
        new Thread(this::save, "PresetSaver").start();
    }

    private void save() {
        if (file == null) file = getPresetFile();
        file.getParentFile().mkdirs();
        try {
            String json = GSON.toJson(items);
            Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Error saving item presets: {}", e.getMessage());
        }
    }

    private static File getPresetFile() {
        File configDir = FabricLoader.getInstance().getConfigDir().toFile();
        File modDir = new File(configDir, "translex");
        modDir.mkdirs();
        return new File(modDir, "preset_items.json");
    }

    public static class ItemPreset {
        public String name;
        public String lore;

        public ItemPreset(String name, String lore) {
            this.name = name;
            this.lore = lore;
        }
    }
}
