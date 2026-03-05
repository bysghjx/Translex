package top.iencand.translex.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Formatting;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class ModConfig {
    public String apiKey = "YOUR_API_KEY_HERE";
    public String apiUrl = "https://api.siliconflow.cn/v1/chat/completions";
    public String modelName = "deepseek-ai/DeepSeek-V3";
    public String translationPrompt = "You are a professional Hypixel SkyBlock translator. \n" +
            "### CRITICAL RULES:\n" +
            "1. Target Language: Always translate to **Simplified Chinese (简体中文)**. NEVER use Korean, Japanese, or any other languages.\n" +
            "2. Input Format: If the input is a JSON array, return ONLY a JSON string array of the SAME length.\n" +
            "3. Style: Keep item names (e.g., \"Hyperion\") in English. Keep Minecraft color codes (e.g. §7, §a) unchanged.\n" +
            "4. Format: No markdown, no conversation, no explanations. Reply ONLY with the translated content.";

    public boolean enableMessageIdSystem = true; // 是否启用消息 ID 系统

    public boolean enableCachePersistence = true; // 是否启用缓存持久化
    public boolean enablePeriodicSave = true; // 是否启用周期性保存
    // 周期保存间隔 (ticks)，默认 20分钟 * 60秒/分钟 * 20tick/秒 = 24000
    public int periodicSaveInterval = 24000;

    // --- 新增：消息折叠相关配置 ---
    public boolean enableChatCompact = true;     // 是否启用消息折叠
    public int compactTimeSeconds = 120;         // 重复消息折叠的时间阈值（秒）
    public String compactColorCode = "GRAY";     // 折叠计数器 (x2) 的颜色名称

    /**
     * 获取配置的折叠颜色枚举
     */
    public Formatting getCompactColor() {
        try {
            return Formatting.byName(compactColorCode.toUpperCase());
        } catch (Exception e) {
            return Formatting.GRAY; // 解析失败时默认返回灰色
        }
    }

    // --- Gson 实例 (transient 不会被序列化) ---
    private static transient final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // --- 配置文件路径 (transient 不会被序列化) ---
    private static transient File configFile;

    // --- 单例实例 ---
    private static ModConfig instance;

    // 私有构造函数，强制使用静态方法获取实例
    private ModConfig() {
    }

    // 获取单例实例
    public static ModConfig get() {
        if (instance == null) {
            loadConfig(); // 如果实例不存在，先尝试加载
        }
        return instance;
    }

    // 获取配置文件的 File 对象
    private static File getConfigFile() {
        if (configFile == null) {
            // 获取 Mod 的配置目录 (例如 .minecraft/config/)
            File configDir = FabricLoader.getInstance().getConfigDir().toFile();
            // 在 Mod 配置目录下的一个子目录中创建配置文件，例如 config/translex/config.json
            File modConfigDir = new File(configDir, "translex");
            // 确保目录存在
            modConfigDir.mkdirs();
            configFile = new File(modConfigDir, "config.json");
        }
        return configFile;
    }

    // 获取缓存文件的 File 对象
    public static File getCacheFile() {
        File configDir = FabricLoader.getInstance().getConfigDir().toFile();
        File modConfigDir = new File(configDir, "translex");
        File cacheDir = new File(modConfigDir, "cache");
        cacheDir.mkdirs(); // 确保目录存在
        return new File(cacheDir, "translation_cache.json");
    }

    /**
     * 从文件加载配置。
     * 如果文件不存在或加载失败，将创建默认配置。
     */
    public static void loadConfig() {
        configFile = getConfigFile();
        System.out.println("[Translex Config] Attempting to load config from: " + configFile.getAbsolutePath());

        if (configFile.exists()) {
            try (Reader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
                // 1. 原有的反序列化逻辑
                instance = GSON.fromJson(reader, ModConfig.class);

                if (instance == null) {
                    System.err.println("[Translex Config] Failed to load config: File content is empty.");
                    instance = new ModConfig();
                } else {
                    // --- 核心：自动更新提示词逻辑 ---
                    // 定义旧版默认值用于比对
                    String oldDefault = "Translate the following Hypixel SkyBlock message to Simplified Chinese. Keep item names (e.g., \"Hyperion\") in English. Only provide the translation.";

                    // 定义新版默认值
                    String newDefault = "You are a professional Hypixel SkyBlock translator. \n" +
                            "### CRITICAL RULES:\n" +
                            "1. Target Language: Always translate to **Simplified Chinese (简体中文)**. NEVER use Korean, Japanese, or any other languages.\n" +
                            "2. Input Format: If the input is a JSON array, return ONLY a JSON string array of the SAME length.\n" +
                            "3. Style: Keep item names (e.g., \"Hyperion\") in English. Keep Minecraft color codes (e.g. §7, §a) unchanged.\n" +
                            "4. Format: No markdown, no conversation, no explanations. Reply ONLY with the translated content.";

                    // 如果用户还没改过提示词（还是旧版默认值），则自动更新
                    if (instance.translationPrompt != null && instance.translationPrompt.trim().equals(oldDefault.trim())) {
                        System.out.println("[Translex Config] Detected old default prompt. Upgrading to batch-aware version...");
                        instance.translationPrompt = newDefault;
                        saveConfig(); // 升级后自动保存，防止下次重复触发
                    }

                    System.out.println("[Translex Config] Config loaded successfully.");
                    saveConfig(); // 同步可能新增的配置项
                }
            } catch (JsonSyntaxException e) {
                System.err.println("[Translex Config] Invalid JSON format! Using default.");
                instance = new ModConfig();
            } catch (IOException e) {
                System.err.println("[Translex Config] Error reading config: " + e.getMessage());
                instance = new ModConfig();
            }
        } else {
            System.out.println("[Translex Config] Config file not found, creating default.");
            instance = new ModConfig();
            saveConfig();
        }
    }

    /**
     * 将当前配置保存到文件。
     */
    public static void saveConfig() {
        if (instance == null) {
            System.err.println("[Translex Config] Cannot save config, instance is null!");
            return;
        }
        configFile = getConfigFile(); // 确保获取了文件对象
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
            // 将当前实例序列化为 JSON 并写入文件
            GSON.toJson(instance, writer);
            System.out.println("[Translex Config] Config saved successfully to: " + configFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("[Translex Config] Error writing config file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void forceSave() {
        saveConfig();
    }
}