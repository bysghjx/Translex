package top.iencand.translex.client.config;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class ModConfig {
    public String apiKey = "YOUR_API_KEY_HERE";
    public String apiUrl = "https://api.siliconflow.cn/v1/chat/completions";
    public String modelName = "deepseek-ai/DeepSeek-V3";
    public String translationPrompt = "Translate the following Hypixel SkyBlock message to Simplified Chinese. Keep item names (e.g., \"Hyperion\") in English. Only provide the translation.";

    public boolean enableMessageIdSystem = true; // 是否启用消息 ID 系统

    public boolean enableCachePersistence = true; // 是否启用缓存持久化
    public boolean enablePeriodicSave = true; // 是否启用周期性保存
    // 周期保存间隔 (ticks)，默认 20分钟 * 60秒/分钟 * 20tick/秒 = 24000
    // 请注意：原 Forge 代码中 periodicSaveInterval 的默认值是 2400，这只有 2分钟。
    // 20分钟应该是 24000 ticks。我这里使用 24000 作为示例。
    public int periodicSaveInterval = 24000;

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

    // 获取缓存文件的 File 对象 (可以在这里定义，或者在 TranslationManager 中处理)
    // 为了集中管理文件路径，放在这里比较好
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
                // 从文件读取 JSON 并反序列化为 ModConfig 对象
                instance = GSON.fromJson(reader, ModConfig.class);
                if (instance == null) {
                    System.err.println("[Translex Config] Failed to load config: File content is empty or invalid.");
                    instance = new ModConfig(); // 加载失败，使用默认配置
                } else {
                    System.out.println("[Translex Config] Config loaded successfully.");
                }
            } catch (FileNotFoundException e) {
                // 文件不存在，这通常在第一次运行时发生，会被 configFile.exists() 捕获
                System.err.println("[Translex Config] Config file not found (unexpected here): " + e.getMessage());
                instance = new ModConfig(); // 文件不存在，使用默认配置
            } catch (JsonSyntaxException e) {
                // JSON 格式错误
                System.err.println("[Translex Config] Failed to parse config file (Invalid JSON): " + e.getMessage());
                e.printStackTrace();
                System.err.println("[Translex Config] Using default config. Consider deleting the corrupted config file: " + configFile.getAbsolutePath());
                instance = new ModConfig(); // JSON 错误，使用默认配置
            } catch (IOException e) {
                System.err.println("[Translex Config] Error reading config file: " + e.getMessage());
                e.printStackTrace();
                instance = new ModConfig(); // IO 错误，使用默认配置
            }
        } else {
            System.out.println("[Translex Config] Config file not found, creating default config.");
            instance = new ModConfig(); // 文件不存在，使用默认配置
            saveConfig(); // 保存默认配置到文件
        }
        // 如果加载成功或使用了默认配置，确保实例不为 null
        if (instance == null) {
            instance = new ModConfig();
            System.err.println("[Translex Config] Instance was still null after load attempt, creating new default.");
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

    // 可以添加一个方法来强制保存，例如在 Mod 停止时
    public static void forceSave() {
        saveConfig();
    }
}
