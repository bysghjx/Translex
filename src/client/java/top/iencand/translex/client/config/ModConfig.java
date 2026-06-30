package top.iencand.translex.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.moandjiezana.toml.Toml;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import top.iencand.translex.client.web.ConsoleBroadcaster;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("TranslexConfig");

    public String apiKey = "YOUR_API_KEY_HERE";
    public String apiUrl = "https://api.deepseek.com/chat/completions";
    public String modelName = "deepseek-v4-flash";

    /** AI 供应商适配器 id（见 AiProviders）：openai / anthropic。决定请求/响应格式。 */
    public String provider = "openai";

    /** 最大输出 token。Anthropic 必填；OpenAI 兼容端点忽略。 */
    public int maxTokens = 4096;

    /** anthropic-version 请求头取值，仅 Anthropic 供应商使用。 */
    public String anthropicVersion = "2023-06-01";

    /** 已保存的连接预设库。当前激活的连接由上面的 apiKey/apiUrl/modelName/provider 等字段持有。 */
    public List<Preset> presets = new ArrayList<>();

    /** 当前激活的预设名（为空表示使用自定义、未保存为预设的连接）。 */
    public String activePreset = "";

    /**
     * 一套可命名、可切换的连接配置。
     */
    public static class Preset {
        public String name = "";
        public String provider = "openai";
        public String apiUrl = "";
        public String apiKey = "";
        public String model = "";
        public int maxTokens = 4096;
        public String anthropicVersion = "2023-06-01";

        public Preset() {}

        public Preset(String name, String provider, String apiUrl, String apiKey,
                      String model, int maxTokens, String anthropicVersion) {
            this.name = name;
            this.provider = provider;
            this.apiUrl = apiUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.maxTokens = maxTokens;
            this.anthropicVersion = anthropicVersion;
        }
    }

    /** 目标语言，聊天/物品共用，注入强制 system prompt 的 "translate into X"。 */
    public String targetLanguage = "Simplified Chinese (简体中文)";

    /** 用户可选的聊天翻译补充指令，非空才作为独立 user 消息发送。 */
    public String userChatPrompt = "";

    /** 用户可选的物品翻译补充指令，非空才作为独立 user 消息发送。 */
    public String userItemPrompt = "";

    /** 专有名词处理模式：keep(保留英文)/translate(全部翻译)/item_only(只留物品名)。 */
    public String properNounMode = "keep";

    /** 翻译模式："auto"（自动）、"message_id"（基于消息ID）或 "text"（纯文本）。默认为 "auto"。 */
    public String translationMode = "auto";

    public boolean enableCachePersistence = true;
    public boolean enablePeriodicSave = true;
    public int periodicSaveInterval = 24000;

    /** 文本翻译内存缓存上限（条目数）。超过后按 LRU 淘汰最久未访问的条目。 */
    public int cacheMaxEntries = 20000;

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
     * @return ChatFormatting 枚举值，解析失败时返回 GRAY
     */
    public ChatFormatting getCompactColor() {
        try {
            return ChatFormatting.getByName(compactColorCode.toUpperCase());
        } catch (Exception e) {
            return ChatFormatting.GRAY;
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
                instance.presets = new ArrayList<>(builtinPresets());
                saveConfig();
            }
        }
        // 首次启动或迁移后，预设库里至少填上两个内置预设
        if (instance.presets == null || instance.presets.isEmpty()) {
            instance.presets = new ArrayList<>(builtinPresets());
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
            instance.provider = toml.getString("provider", instance.provider);
            instance.maxTokens = toml.getLong("maxTokens", (long) instance.maxTokens).intValue();
            instance.anthropicVersion = toml.getString("anthropicVersion", instance.anthropicVersion);
            instance.activePreset = toml.getString("activePreset", instance.activePreset);
            loadPresets(toml);
            if (instance.presets.isEmpty()) {
                instance.presets.addAll(builtinPresets());
            }
            instance.targetLanguage = toml.getString("targetLanguage", instance.targetLanguage);
            instance.userChatPrompt = toml.getString("userChatPrompt", instance.userChatPrompt);
            instance.userItemPrompt = toml.getString("userItemPrompt", instance.userItemPrompt);
            instance.properNounMode = toml.getString("properNounMode", instance.properNounMode);
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
            instance.cacheMaxEntries = toml.getLong("cacheMaxEntries", (long) instance.cacheMaxEntries).intValue();
            instance.enableChatCompact = toml.getBoolean("enableChatCompact", instance.enableChatCompact);
            instance.compactTimeSeconds = toml.getLong("compactTimeSeconds", (long) instance.compactTimeSeconds).intValue();
            instance.compactColorCode = toml.getString("compactColorCode", instance.compactColorCode);
            instance.buttonStyle = toml.getString("buttonStyle", instance.buttonStyle);
            instance.enableTranslateButton = toml.getBoolean("enableTranslateButton", instance.enableTranslateButton);
            instance.outputMode = toml.getString("outputMode", instance.outputMode);
            instance.debug = toml.getBoolean("debug", instance.debug);

            migrateLegacyPrompt(toml);
            LOGGER.info("Config loaded successfully.");
            saveConfig();
        } catch (Exception e) {
            LOGGER.error("Error reading TOML config: {}", e.getMessage());
            instance = new ModConfig();
        }
    }

    private static void loadPresets(Toml toml) {
        instance.presets = new ArrayList<>();
        try {
            List<Toml> tables = toml.getTables("presets");
            if (tables != null) {
                for (Toml t : tables) {
                    Preset p = new Preset();
                    p.name = t.getString("name", "");
                    p.provider = t.getString("provider", "openai");
                    p.apiUrl = t.getString("apiUrl", "");
                    p.apiKey = t.getString("apiKey", "");
                    p.model = t.getString("model", "");
                    p.maxTokens = t.getLong("maxTokens", 4096L).intValue();
                    p.anthropicVersion = t.getString("anthropicVersion", "2023-06-01");
                    if (!p.name.isBlank()) instance.presets.add(p);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load presets from config: {}", e.getMessage());
        }
    }

    /** 内置两个预设（DeepSeek + Anthropic），含默认端点 URL。 */
    private static List<Preset> builtinPresets() {
        List<Preset> list = new ArrayList<>();
        list.add(new Preset("DeepSeek", "openai",
                "https://api.deepseek.com/chat/completions", "YOUR_API_KEY_HERE",
                "deepseek-v4-flash", 4096, "2023-06-01"));
        list.add(new Preset("Anthropic (Claude)", "anthropic",
                "https://api.anthropic.com/v1/messages", "YOUR_API_KEY_HERE",
                "claude-sonnet-4-6", 4096, "2023-06-01"));
        return list;
    }

    private static void loadFromLegacyJson(File file) {
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            instance = GSON.fromJson(reader, ModConfig.class);
            if (instance == null) {
                instance = new ModConfig();
            }
            // 旧 JSON 里的 translationPrompt/itemTranslationPrompt 字段已不存在于新类，GSON 自动忽略。
            LOGGER.info("Successfully migrated from legacy JSON config.");
            LOGGER.info("Config will be saved in TOML format at: {}", getConfigFile().getAbsolutePath());
        } catch (Exception e) {
            LOGGER.error("Error migrating legacy config: {}", e.getMessage());
            instance = new ModConfig();
        }
    }

    /**
     * 一次性迁移旧版 prompt 字段。
     *
     * <p>旧 {@code translationPrompt}/{@code itemTranslationPrompt} 把"格式约束 + 目标语言 + 个性化"
     * 混在一起。重构后格式约束已固化进 {@link top.iencand.translex.client.translate.TranslationPrompts}
     * 的强制 system prompt，目标语言抽到 {@code targetLanguage}。旧值无法可靠剥离出"纯个性化补充"，
     * 强行当 user prompt 发送会与强制 system 冲突（双重约束/双重语言），因此一律丢弃，
     * 让用户在新 UI 的"附加指令"里按需重填。仅记录日志提示。</p>
     */
    private static void migrateLegacyPrompt(Toml toml) {
        boolean hadLegacy = toml.contains("translationPrompt") || toml.contains("itemTranslationPrompt");
        if (hadLegacy) {
            LOGGER.info("Detected legacy translationPrompt/itemTranslationPrompt; format constraints have "
                    + "moved to the system prompt and were dropped. Set userChatPrompt/userItemPrompt in the "
                    + "dashboard if you need extra instructions.");
        }
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

        sb.append("# AI provider adapter: \"openai\" (OpenAI-compatible) or \"anthropic\" (Claude native)\n");
        sb.append("provider = \"").append(escapeToml(instance.provider)).append("\"\n");
        sb.append("# Max output tokens (required by Anthropic; ignored by OpenAI-compatible endpoints)\n");
        sb.append("maxTokens = ").append(instance.maxTokens).append("\n");
        sb.append("# anthropic-version header value (Anthropic only)\n");
        sb.append("anthropicVersion = \"").append(escapeToml(instance.anthropicVersion)).append("\"\n");
        sb.append("# Name of the currently active saved preset (empty = custom connection)\n");
        sb.append("activePreset = \"").append(escapeToml(instance.activePreset)).append("\"\n");
        sb.append("\n");

        sb.append("# Target language for all translations (chat & items). Injected into the forced system prompt.\n");
        sb.append("targetLanguage = \"").append(escapeToml(instance.targetLanguage)).append("\"\n");
        sb.append("\n");

        sb.append("# Optional extra user instructions. Leave empty to send nothing. Format constraints are handled internally.\n");
        writeTomlString(sb, "userChatPrompt", instance.userChatPrompt);
        writeTomlString(sb, "userItemPrompt", instance.userItemPrompt);
        sb.append("\n");

        sb.append("# Proper noun handling: \"keep\" (English), \"translate\" (all), or \"item_only\" (keep item names)\n");
        sb.append("properNounMode = \"").append(escapeToml(instance.properNounMode)).append("\"\n");
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
        sb.append("# Max in-memory cache entries; oldest unused entries are evicted (LRU) beyond this\n");
        sb.append("cacheMaxEntries = ").append(instance.cacheMaxEntries).append("\n");
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

        // ── 连接预设库（TOML array of tables）──
        if (instance.presets != null && !instance.presets.isEmpty()) {
            sb.append("\n");
            sb.append("# --- Saved connection presets ---\n");
            for (Preset p : instance.presets) {
                if (p == null || p.name == null || p.name.isBlank()) continue;
                sb.append("\n[[presets]]\n");
                sb.append("name = \"").append(escapeToml(p.name)).append("\"\n");
                sb.append("provider = \"").append(escapeToml(p.provider)).append("\"\n");
                sb.append("apiUrl = \"").append(escapeToml(p.apiUrl)).append("\"\n");
                sb.append("apiKey = \"").append(escapeToml(p.apiKey)).append("\"\n");
                sb.append("model = \"").append(escapeToml(p.model)).append("\"\n");
                sb.append("maxTokens = ").append(p.maxTokens).append("\n");
                sb.append("anthropicVersion = \"").append(escapeToml(p.anthropicVersion)).append("\"\n");
            }
        }

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
