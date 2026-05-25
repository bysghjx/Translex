package top.iencand.translex.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.moandjiezana.toml.Toml;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("TranslexConfig");

    public String apiKey = "YOUR_API_KEY_HERE";
    public String apiUrl = "https://api.deepseek.com/chat/completions";
    public String modelName = "deepseek-v4-flash";
    public String translationPrompt = "Translate to Simplified Chinese (简体中文). Keep item names in English. Keep Minecraft color codes (§) unchanged. Reply with a JSON string array only.";

    public boolean enableMessageIdSystem = true;

    public boolean enableCachePersistence = true;
    public boolean enablePeriodicSave = true;
    public int periodicSaveInterval = 24000;

    public boolean enableChatCompact = true;
    public int compactTimeSeconds = 120;
    public String compactColorCode = "GRAY";
    public String buttonStyle = "NORMAL"; // "NORMAL" or "COMPACT"

    public Formatting getCompactColor() {
        try {
            return Formatting.byName(compactColorCode.toUpperCase());
        } catch (Exception e) {
            return Formatting.GRAY;
        }
    }

    private static transient final Gson GSON = new GsonBuilder().create();

    private static transient File configFile;
    private static ModConfig instance;
    private static final List<ConfigReloadListener> listeners = new CopyOnWriteArrayList<>();

    private ModConfig() {
    }

    public static ModConfig get() {
        if (instance == null) {
            loadConfig();
        }
        return instance;
    }

    private static File getConfigFile() {
        if (configFile == null) {
            File configDir = FabricLoader.getInstance().getConfigDir().toFile();
            File modConfigDir = new File(configDir, "translex");
            modConfigDir.mkdirs();
            configFile = new File(modConfigDir, "config.toml");
        }
        return configFile;
    }

    private static File getLegacyConfigFile() {
        File configDir = FabricLoader.getInstance().getConfigDir().toFile();
        File modConfigDir = new File(configDir, "translex");
        return new File(modConfigDir, "config.json");
    }

    public static File getCacheFile() {
        File configDir = FabricLoader.getInstance().getConfigDir().toFile();
        File modConfigDir = new File(configDir, "translex");
        File cacheDir = new File(modConfigDir, "cache");
        cacheDir.mkdirs();
        return new File(cacheDir, "translation_cache.json");
    }

    public static void addListener(ConfigReloadListener listener) {
        listeners.add(listener);
    }

    public static void reload() {
        loadConfig();
        ModConfig config = instance;
        if (config != null) {
            for (ConfigReloadListener listener : listeners) {
                try {
                    listener.onConfigReload(config);
                } catch (Exception e) {
                    LOGGER.error("Error in config reload listener", e);
                }
            }
        }
    }

    private static void loadConfig() {
        configFile = getConfigFile();
        LOGGER.info("Attempting to load config from: {}", configFile.getAbsolutePath());

        if (configFile.exists()) {
            loadFromToml(configFile);
            File legacyFile = getLegacyConfigFile();
            if (legacyFile.exists()) {
                File backup = new File(legacyFile.getParentFile(), "config.json.bak");
                if (legacyFile.renameTo(backup)) {
                    LOGGER.info("Old config.json renamed to config.json.bak");
                }
            }
        } else {
            File legacyFile = getLegacyConfigFile();
            if (legacyFile.exists()) {
                LOGGER.info("Migrating legacy config from: {}", legacyFile.getAbsolutePath());
                loadFromLegacyJson(legacyFile);
                saveConfig();
                File backup = new File(legacyFile.getParentFile(), "config.json.bak");
                if (legacyFile.renameTo(backup)) {
                    LOGGER.info("Old config.json renamed to config.json.bak");
                }
            } else {
                LOGGER.info("Config file not found, creating default.");
                instance = new ModConfig();
                saveConfig();
            }
        }
    }

    private static void loadFromToml(File file) {
        try {
            Toml toml = new Toml().read(file);

            if (toml.isEmpty()) {
                LOGGER.warn("Config file is empty. Using defaults.");
                instance = new ModConfig();
                return;
            }

            instance = new ModConfig();
            instance.apiKey = toml.getString("apiKey", instance.apiKey);
            instance.apiUrl = toml.getString("apiUrl", instance.apiUrl);
            instance.modelName = toml.getString("modelName", instance.modelName);
            instance.translationPrompt = toml.getString("translationPrompt", instance.translationPrompt);
            instance.enableMessageIdSystem = toml.getBoolean("enableMessageIdSystem", instance.enableMessageIdSystem);
            instance.enableCachePersistence = toml.getBoolean("enableCachePersistence", instance.enableCachePersistence);
            instance.enablePeriodicSave = toml.getBoolean("enablePeriodicSave", instance.enablePeriodicSave);
            instance.periodicSaveInterval = toml.getLong("periodicSaveInterval", (long) instance.periodicSaveInterval).intValue();
            instance.enableChatCompact = toml.getBoolean("enableChatCompact", instance.enableChatCompact);
            instance.compactTimeSeconds = toml.getLong("compactTimeSeconds", (long) instance.compactTimeSeconds).intValue();
            instance.compactColorCode = toml.getString("compactColorCode", instance.compactColorCode);
            instance.buttonStyle = toml.getString("buttonStyle", instance.buttonStyle);

            checkAndUpgradePrompt();
            LOGGER.info("Config loaded successfully.");
            saveConfig();
        } catch (Exception e) {
            LOGGER.error("Error reading TOML config: {}", e.getMessage());
            instance = new ModConfig();
        }
    }

    private static void loadFromLegacyJson(File file) {
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            instance = GSON.fromJson(reader, ModConfig.class);
            if (instance == null) {
                instance = new ModConfig();
            }
            checkAndUpgradePrompt();
            LOGGER.info("Successfully migrated from legacy JSON config.");
            LOGGER.info("Config will be saved in TOML format at: {}", getConfigFile().getAbsolutePath());
        } catch (Exception e) {
            LOGGER.error("Error migrating legacy config: {}", e.getMessage());
            instance = new ModConfig();
        }
    }

    private static void checkAndUpgradePrompt() {
        String v1 = "Translate the following Hypixel SkyBlock message to Simplified Chinese. Keep item names (e.g., \"Hyperion\") in English. Only provide the translation.";
        String v2 = "You are a professional Hypixel SkyBlock translator. \n" +
                "### CRITICAL RULES:\n" +
                "1. Target Language: Always translate to **Simplified Chinese (简体中文)**. NEVER use Korean, Japanese, or any other languages.\n" +
                "2. Input Format: If the input is a JSON array, return ONLY a JSON string array of the SAME length.\n" +
                "3. Style: Keep item names (e.g., \"Hyperion\") in English. Keep Minecraft color codes (e.g. §7, §a) unchanged.\n" +
                "4. Format: No markdown, no conversation, no explanations. Reply ONLY with the translated content.";

        if (instance.translationPrompt == null) return;
        String current = normalize(instance.translationPrompt);
        if (current.equals(normalize(v1)) || current.equals(normalize(v2))) {
            LOGGER.info("Detected old default prompt. Upgrading to concise version...");
            instance.translationPrompt = "Translate to Simplified Chinese (简体中文). Keep item names in English. Keep Minecraft color codes (§) unchanged. Reply with a JSON string array only.";
            saveConfig();
        }
    }

    /**
     * 折叠所有空白字符到单个空格，消除 TOML 多行字符串和 JSON 转义的格式差异。
     */
    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    public static void saveConfig() {
        if (instance == null) {
            LOGGER.error("Cannot save config, instance is null!");
            return;
        }
        configFile = getConfigFile();
        try {
            String toml = buildTomlContent();
            Files.writeString(configFile.toPath(), toml, StandardCharsets.UTF_8);
            LOGGER.info("Config saved successfully to: {}", configFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Error writing config file: {}", e.getMessage(), e);
        }
    }

    private static String buildTomlContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Translex Mod Configuration\n");
        sb.append("# Edit this file and run /translex reload to apply changes\n");
        sb.append("\n");

        sb.append("# Your AI API Key (required)\n");
        sb.append("apiKey = \"").append(escapeToml(instance.apiKey)).append("\"\n");
        sb.append("\n");

        sb.append("# API endpoint URL\n");
        sb.append("apiUrl = \"").append(escapeToml(instance.apiUrl)).append("\"\n");
        sb.append("\n");

        sb.append("# Model name\n");
        sb.append("modelName = \"").append(escapeToml(instance.modelName)).append("\"\n");
        sb.append("\n");

        sb.append("# System prompt sent to the AI — keep it concise to save tokens\n");
        writeTomlString(sb, "translationPrompt", instance.translationPrompt);
        sb.append("\n");

        sb.append("# Use Message ID mode (true) or Legacy text mode (false) — requires restart\n");
        sb.append("enableMessageIdSystem = ").append(instance.enableMessageIdSystem).append("\n");
        sb.append("\n");

        sb.append("# --- Cache ---\n");
        sb.append("# Persist translation cache to disk\n");
        sb.append("enableCachePersistence = ").append(instance.enableCachePersistence).append("\n");
        sb.append("# Periodically auto-save the cache\n");
        sb.append("enablePeriodicSave = ").append(instance.enablePeriodicSave).append("\n");
        sb.append("# Auto-save interval in ticks (24000 ticks = 20 minutes)\n");
        sb.append("periodicSaveInterval = ").append(instance.periodicSaveInterval).append("\n");
        sb.append("\n");

        sb.append("# --- Chat Compact ---\n");
        sb.append("# Fold duplicate chat messages\n");
        sb.append("enableChatCompact = ").append(instance.enableChatCompact).append("\n");
        sb.append("# Time window in seconds for folding duplicates\n");
        sb.append("compactTimeSeconds = ").append(instance.compactTimeSeconds).append("\n");
        sb.append("# Color for the fold counter (GRAY, DARK_GRAY, GREEN, etc.)\n");
        sb.append("compactColorCode = \"").append(escapeToml(instance.compactColorCode)).append("\"\n");
        sb.append("\n");

        sb.append("# Translation button style: \"NORMAL\" or \"COMPACT\" ([T])\n");
        sb.append("buttonStyle = \"").append(escapeToml(instance.buttonStyle)).append("\"\n");

        return sb.toString();
    }

    private static void writeTomlString(StringBuilder sb, String key, String value) {
        if (value.contains("\n")) {
            sb.append(key).append(" = '''\n");
            sb.append(value);
            if (!value.endsWith("\n")) sb.append("\n");
            sb.append("'''\n");
        } else {
            sb.append(key).append(" = \"").append(escapeToml(value)).append("\"\n");
        }
    }

    private static String escapeToml(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static void forceSave() {
        saveConfig();
    }
}
