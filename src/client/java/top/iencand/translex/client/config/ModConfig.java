package top.iencand.translex.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.moandjiezana.toml.Toml;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import top.iencand.translex.client.web.ConsoleBroadcaster;

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
    public String translationPrompt = "You are a translator. Translate each value in the JSON object to Simplified Chinese (简体中文). Preserve placeholders like {0} {1} exactly as-is. Keep proper names and item names in English. Keep § color codes. Reply with a JSON object with the same keys.";

    /** 翻译模式："auto"（自动）、"message_id"（基于消息ID）或 "text"（纯文本）。默认为 "auto"。 */
    public String translationMode = "auto";

    public boolean enableCachePersistence = true;
    public boolean enablePeriodicSave = true;
    public int periodicSaveInterval = 24000;

    public boolean enableChatCompact = true;
    public int compactTimeSeconds = 120;
    public String compactColorCode = "GRAY";
    public String buttonStyle = "NORMAL"; // "NORMAL" or "COMPACT"

    /** 是否在聊天消息中添加翻译按钮。默认为 true。 */
    public boolean enableTranslateButton = true;

    /** 输出模式："chat"（聊天栏，默认）、"temporary"（临时）或 "permanent"（永久保存）。 */
    public String outputMode = "chat";

    /** 调试模式：启动时自动打开 Web 仪表盘的网络抓包页面。 */
    public boolean debug = false;

    /**
     * 获取折叠计数使用的颜色。
     * @return Formatting 枚举值，解析失败时返回 GRAY
     */
    public Formatting getCompactColor() {
        try {
            return Formatting.byName(compactColorCode.toUpperCase());
        } catch (Exception e) {
            return Formatting.GRAY;
        }
    }

    public String getOutputMode() {
        return outputMode;
    }

    /** 设置输出模式并立即保存配置 */
    public void setOutputMode(String mode) {
        this.outputMode = mode;
        saveConfig();
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
            ConsoleBroadcaster.broadcast("INFO", "Config reloaded from disk — " + config.modelName + " @ " + config.apiUrl);
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
            // translationMode: new string field, with backward compat for old boolean enableMessageIdSystem
            if (toml.contains("translationMode")) {
                instance.translationMode = toml.getString("translationMode", instance.translationMode);
            } else if (toml.contains("enableMessageIdSystem")) {
                boolean old = toml.getBoolean("enableMessageIdSystem", true);
                instance.translationMode = old ? "message_id" : "text";
            }
            instance.enableCachePersistence = toml.getBoolean("enableCachePersistence", instance.enableCachePersistence);
            instance.enablePeriodicSave = toml.getBoolean("enablePeriodicSave", instance.enablePeriodicSave);
            instance.periodicSaveInterval = toml.getLong("periodicSaveInterval", (long) instance.periodicSaveInterval).intValue();
            instance.enableChatCompact = toml.getBoolean("enableChatCompact", instance.enableChatCompact);
            instance.compactTimeSeconds = toml.getLong("compactTimeSeconds", (long) instance.compactTimeSeconds).intValue();
            instance.compactColorCode = toml.getString("compactColorCode", instance.compactColorCode);
            instance.buttonStyle = toml.getString("buttonStyle", instance.buttonStyle);
            instance.enableTranslateButton = toml.getBoolean("enableTranslateButton", instance.enableTranslateButton);
            instance.outputMode = toml.getString("outputMode", instance.outputMode);
            instance.debug = toml.getBoolean("debug", instance.debug);

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
        // Every previous factory-default or upgrade-target prompt that should
        // be refreshed to the current default when detected.
        String[] oldDefaults = {
            // v1.x — original deepseek prompt
            "Translate the following Hypixel SkyBlock message to Simplified Chinese. Keep item names (e.g., \"Hyperion\") in English. Only provide the translation.",
            // v1.x — professional SkyBlock translator (long)
            "You are a professional Hypixel SkyBlock translator. ### CRITICAL RULES: 1. Target Language: Always translate to **Simplified Chinese (简体中文)**. NEVER use Korean, Japanese, or any other languages. 2. Input Format: If the input is a JSON array, return ONLY a JSON string array of the SAME length. 3. Style: Keep item names (e.g., \"Hyperion\") in English. Keep Minecraft color codes (e.g. §7, §a) unchanged. 4. Format: No markdown, no conversation, no explanations. Reply ONLY with the translated content.",
            // v1.x — short JSON-array variant
            "Translate to Simplified Chinese (简体中文). Keep item names in English. Keep Minecraft color codes (§) unchanged. Reply with a JSON string array only.",
            // v1.x — minimal JSON-object variant
            "Translate to Simplified Chinese. Preserve: item names, color codes (§), line breaks. Output JSON only.",
            // v1.6.x — previous upgrade target (stale, needs re-upgrade)
            "You are a translator. Translate each value in the JSON object to Simplified Chinese (简体中文). Keep proper names and item names in English. Keep color codes (§). Reply with a JSON object with the same keys — every value translated.",
        };

        if (instance.translationPrompt == null) return;
        String current = normalize(instance.translationPrompt);

        // Auto-skip: if already at the current default, nothing to do
        String currentDefault = normalize(new ModConfig().translationPrompt);
        if (current.equals(currentDefault)) return;

        for (String old : oldDefaults) {
            if (current.equals(normalize(old))) {
                LOGGER.info("Detected old default prompt. Upgrading to current version...");
                instance.translationPrompt = new ModConfig().translationPrompt;
                saveConfig();
                return;
            }
        }
    }

    /**
     * 折叠所有空白字符到单个空格，用于消除 TOML 多行字符串和 JSON 转义之间的格式差异，
     * 以便准确比较提示词内容是否匹配旧版本默认值。
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

        sb.append("# Translation mode: \"auto\" (default), \"message_id\", or \"text\"\n");
        sb.append("translationMode = \"").append(escapeToml(instance.translationMode)).append("\"\n");
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
        sb.append("\n");

        sb.append("# Show translation button on chat messages\n");
        sb.append("enableTranslateButton = ").append(instance.enableTranslateButton).append("\n");
        sb.append("\n");

        sb.append("# Translation output mode: \"chat\", \"temporary\", or \"permanent\"\n");
        sb.append("outputMode = \"").append(escapeToml(instance.outputMode)).append("\"\n");
        sb.append("\n");

        sb.append("# Debug mode: auto-open web dashboard to network traces tab on startup\n");
        sb.append("debug = ").append(instance.debug).append("\n");

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
