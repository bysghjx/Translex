package top.iencand.translex.client.translate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent library mapping SkyBlock item IDs to their full translated lore.
 * Used by "permanent" output mode and the tooltip replacement Mixin.
 *
 * <p>Stores {@code Map<String, List<Text>>} in memory and serializes
 * the underlying strings to {@code item_cache.json}.</p>
 */
public class ItemPresetLibrary {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemPresetLibrary");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // --- storage: itemId → lore lines (as string for JSON serialization) ---
    private final ConcurrentHashMap<String, List<String>> items = new ConcurrentHashMap<>();
    private File file;

    // --- singleton for Mixin access ---
    private static ItemPresetLibrary INSTANCE;

    public static ItemPresetLibrary getInstance() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------
    // Load / Save
    // ---------------------------------------------------------------

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
        new Thread(this::save, "PresetSaver").start();
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

    private static File getPresetFile() {
        File configDir = FabricLoader.getInstance().getConfigDir().toFile();
        File modDir = new File(configDir, "translex");
        modDir.mkdirs();
        return new File(modDir, "item_cache.json");
    }

    // ---------------------------------------------------------------
    // Access — for Mixin
    // ---------------------------------------------------------------

    /**
     * Returns the full translated tooltip as {@code List<String>} (plain text per line).
     * Called by the tooltip replacement Mixin — styles are applied from original lines.
     */
    public List<String> getTooltip(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        return items.get(itemId);
    }

    /**
     * Store the full translated tooltip for a given item ID.
     * Called by TranslationManager after a successful translation in permanent mode.
     */
    public void putTooltip(String itemId, List<String> lines) {
        if (itemId == null || itemId.isEmpty() || lines == null) return;
        items.put(itemId, lines);
        saveAsync();
    }

    // ---------------------------------------------------------------
    // Legacy compat — used by ChatRenderer / command path
    // ---------------------------------------------------------------

    /** Get preset as joined string (for chat rendering). */
    public ItemPreset get(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        List<String> lines = items.get(itemId);
        if (lines == null) return null;
        return new ItemPreset(lines.get(0), lines);
    }

    /** Get preset lore as List<Text> with gray formatting (for chat rendering). */
    public List<Text> getAsText(String itemId) {
        List<String> lines = getTooltip(itemId);
        if (lines == null) return null;
        List<Text> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(Text.literal(line).formatted(Formatting.GRAY));
        }
        return result;
    }

    /** Store a single-lore-string preset (backward compat). */
    public void put(String itemId, String nameZh, String loreZh) {
        if (itemId == null || itemId.isEmpty()) return;
        List<String> lines = (loreZh != null)
                ? Arrays.asList(loreZh.split("\n"))
                : List.of();
        items.put(itemId, lines);
        saveAsync();
    }

    /** Store a multi-line lore preset. */
    public void putLines(String itemId, String nameZh, List<String> loreLines) {
        if (itemId == null || itemId.isEmpty()) return;
        items.put(itemId, loreLines);
        saveAsync();
    }

    /** Remove a single item preset by ID. */
    public void remove(String itemId) {
        if (itemId == null) return;
        items.remove(itemId);
        saveAsync();
    }

    /** Clear all presets. */
    public void clear() {
        items.clear();
        saveAsync();
    }

    // ---------------------------------------------------------------
    // ItemPreset
    // ---------------------------------------------------------------

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
