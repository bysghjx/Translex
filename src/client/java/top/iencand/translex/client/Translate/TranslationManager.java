package top.iencand.translex.client.Translate;

// 导入 Fabric 1.21.5 对应的 Minecraft 客户端类
import net.minecraft.client.MinecraftClient;
// 导入 Fabric 1.21.5 对应的文本组件类
import net.minecraft.text.Text;

// 导入 Java IO 相关的类
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// 导入 Gson 相关的类
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

// 导入 TranslationRequester
//import top.iencand.translex.client.Translate.TranslationRequester;
//import top.iencand.translex.client.Translate.TranslationCallback;

// Fabric 不使用 @SideOnly 注解
// @SideOnly(Side.CLIENT)
public class TranslationManager {
    // TODO: 从 Mod 配置中读取这些值

    private String apiKey;
    private String targetLanguagePrompt;
    private String apiUrl;
    private String model;
    private final AtomicLong translationCounter = new AtomicLong(0);

    // 翻译缓存
    private final Map<String, String> translationCache = new ConcurrentHashMap<>();
    // 缓存文件字段
    private File cacheFile;
    // Gson 实例
    private final Gson gson = new Gson();

    // *** 新增：TranslationRequester 实例 ***
    private final TranslationRequester translationRequester;

    // 构造函数，初始化 TranslationRequester
    public TranslationManager() {
        this.translationRequester = new TranslationRequester();
    }

    /**
     * 异步执行翻译请求 (用于消息 ID 方式)
     * @param messageId 消息的唯一ID (用于日志和上下文)
     * @param messageTextToTranslate 需要翻译的纯文本内容
     * @param playerNameWithSeparator 用于显示结果时的上下文 (不再直接用于显示)
     */
    public void translateAsync(final int messageId, final String messageTextToTranslate, final String playerNameWithSeparator) {
        // --- 输入验证 ---
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("YOUR_ACTUAL_API_KEY")) {
            MinecraftClient.getInstance().execute(() -> {
                MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(
                        Text.literal("§c[翻译 Mod]§r 请先在 Mod 配置中设置 AI API Key！"));
            });
            return;
        }
        if (messageTextToTranslate == null || messageTextToTranslate.trim().isEmpty()) {
            MinecraftClient.getInstance().execute(() -> {
                MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(
                        Text.literal("§c[翻译 Mod]§r 消息内容为空，无需翻译 (ID: " + messageId + ")。"));
            });
            return;
        }

        System.out.println("Mod 触发翻译 (ID: " + messageId + ")，内容: " + messageTextToTranslate);
        final long currentCount = translationCounter.incrementAndGet();
        final String cacheKey = messageTextToTranslate;

        // --- 缓存命中检查 ---
        if (translationCache.containsKey(cacheKey)) {
            final String cachedResult = translationCache.get(cacheKey);
            System.out.println("Mod 翻译 (ID: " + messageId + ", 第 " + currentCount + " 次) - 缓存命中.");
            MinecraftClient.getInstance().execute(() -> {
                String finalOutputString = "§7[翻译 ID: " + messageId + "]§r " + cachedResult;
                Text resultComponent = Text.literal(finalOutputString);
                MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(resultComponent);
            });
            return; // 缓存命中，直接返回
        }

        // --- 调用 TranslationRequester 发送请求 ---
        translationRequester.requestTranslation(
                this.apiKey,
                this.apiUrl,
                this.model,
                this.targetLanguagePrompt,
                messageTextToTranslate,
                cacheKey, // 传递 cacheKey 给 Requester
                "ID: " + messageId, // 传递用于显示的标识符
                // 提供回调函数来处理请求结果
                (key, translatedTextForDisplay, identifier) -> {
                    // 这个 lambda 在 OkHttp 的工作线程中执行
                    // 缓存结果
                    if (translatedTextForDisplay != null && !translatedTextForDisplay.startsWith("§c[翻译失败]")) { // 只缓存成功的翻译结果
                        translationCache.put(key, translatedTextForDisplay);
                        System.out.println("Mod 翻译 (" + identifier + ") - 缓存写入成功 (来自 Requester 回调).");
                    }
                    // 将结果调度回主线程显示
                    MinecraftClient.getInstance().execute(() -> {
                        String finalOutputString = "§7[翻译" + identifier + "]§r " + translatedTextForDisplay;
                        Text resultComponent = Text.literal(finalOutputString);
                        MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(resultComponent);
                    });
                }
        );
    }

    /**
     * 异步执行翻译请求 (用于命令 + 文本方式)
     * @param messageTextToTranslate 需要翻译的纯文本内容
     * @param context 用于显示结果时的上下文 (不再直接用于显示)
     */
    public void translateTextAsync(final String messageTextToTranslate, final String context) {
        // --- 检查配置 ---
        if (this.apiKey == null || this.apiKey.isEmpty() || this.apiKey.equals("YOUR_API_KEY_HERE") ||
                this.apiUrl == null || this.apiUrl.isEmpty() ||
                this.model == null || this.model.isEmpty() ||
                this.targetLanguagePrompt == null || this.targetLanguagePrompt.isEmpty()) {
            MinecraftClient.getInstance().execute(() -> {
                MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(
                        Text.literal("§c[翻译 Mod]§r API 配置未完全设置！"));
            });
            return;
        }
        if (messageTextToTranslate == null || messageTextToTranslate.trim().isEmpty()) {
            MinecraftClient.getInstance().execute(() -> {
                MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(
                        Text.literal("§c[翻译 Mod]§r 消息内容为空，无需翻译。"));
            });
            return;
        }

        // --- 计数器和缓存检查 ---
        final long currentCount = translationCounter.incrementAndGet();
        System.out.println("Mod 触发文本翻译，内容: " + messageTextToTranslate);
        final String cacheKey = messageTextToTranslate;
        if (translationCache.containsKey(cacheKey)) {
            final String cachedResult = translationCache.get(cacheKey);
            System.out.println("Mod 文本翻译 (第 " + currentCount + " 次) - 缓存命中.");
            MinecraftClient.getInstance().execute(() -> {
                String finalOutputString = "§7[翻译次数: " + currentCount + "]§r " + cachedResult;
                Text resultComponent = Text.literal(finalOutputString);
                MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(resultComponent);
            });
            return;
        }
        System.out.println("Mod 文本翻译 (第 " + currentCount + " 次) - 缓存未命中，发送 API 请求.");

        // --- 调用 TranslationRequester 发送请求 ---
        translationRequester.requestTranslation(
                this.apiKey,
                this.apiUrl,
                this.model,
                this.targetLanguagePrompt,
                messageTextToTranslate,
                cacheKey, // 传递 cacheKey 给 Requester
                "次数: " + currentCount, // 传递用于显示的标识符
                // 提供回调函数来处理请求结果
                (key, translatedTextForDisplay, identifier) -> {
                    // 这个 lambda 在 OkHttp 的工作线程中执行
                    // 缓存结果
                    if (translatedTextForDisplay != null && !translatedTextForDisplay.startsWith("§c[翻译失败]")) { // 只缓存成功的翻译结果
                        translationCache.put(key, translatedTextForDisplay);
                        System.out.println("Mod 翻译 (" + identifier + ") - 缓存写入成功 (来自 Requester 回调).");
                    }
                    // 将结果调度回主线程显示
                    MinecraftClient.getInstance().execute(() -> {
                        String finalOutputString = "§7[翻译" + identifier + "]§r " + translatedTextForDisplay;
                        Text resultComponent = Text.literal(finalOutputString);
                        MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(resultComponent);
                    });
                }
        );
    }

    // 辅助方法：打印字符代码 (保持不变)
    private void printCharCodes(String prefix, String text) {
        if (text == null) {
            System.out.println(prefix + " Codes: null");
            return;
        }
        StringBuilder sb = new StringBuilder(prefix + " Codes: ");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(String.format("'%c'(%d) ", c, (int) c));
        }
        System.out.println(sb.toString());
    }

    // 用于从配置加载设置的方法 (保持不变)
    public void updateSettings(String apiKey, String targetLanguagePrompt, String model, String apiUrl) {
        this.apiKey = apiKey;
        this.targetLanguagePrompt = targetLanguagePrompt;
        this.apiUrl = apiUrl;
        this.model = model;
        System.out.println("TranslationManager settings updated.");
    }

    // 设置缓存文件的方法 (保持不变)
    public void setCacheFile(File cacheFile) {
        this.cacheFile = cacheFile;
        System.out.println("Translation cache file set to: " + (cacheFile != null ? cacheFile.getAbsolutePath() : "null"));
        if (this.cacheFile != null && this.cacheFile.getParentFile() != null) {
            boolean dirsCreated = this.cacheFile.getParentFile().mkdirs();
            if (dirsCreated) {
                System.out.println("Created cache directory: " + this.cacheFile.getParentFile().getAbsolutePath());
            }
        }
    }

    // 加载缓存方法 (保持不变)
    public void loadCache() {
        System.out.println("Attempting to load translation cache...");
        if (cacheFile == null) {
            System.err.println("Translation cache file is not set, cannot load cache.");
            return;
        }
        System.out.println("Cache file path: " + cacheFile.getAbsolutePath());
        if (!cacheFile.exists()) {
            System.out.println("Translation cache file does not exist, starting with empty cache.");
            return;
        }
        System.out.println("Cache file exists, attempting to read...");
        try (FileReader reader = new FileReader(cacheFile)) {
            Type cacheType = new TypeToken<ConcurrentHashMap<String, String>>() {}.getType();
            Map<String, String> loadedCache = gson.fromJson(reader, cacheType);
            if (loadedCache != null) {
                translationCache.clear();
                translationCache.putAll(loadedCache);
                System.out.println("Translation cache loaded successfully. Entries: " + translationCache.size());
            } else {
                System.err.println("Failed to load translation cache: File content is null or empty.");
            }
        } catch (IOException e) {
            System.err.println("Error reading translation cache file: " + e.getMessage());
            e.printStackTrace();
        } catch (JsonSyntaxException e) {
            System.err.println("Error parsing translation cache file (Invalid JSON): " + e.getMessage());
            e.printStackTrace();
            System.err.println("Cache file might be corrupted. Consider backing it up manually.");
        } catch (Exception e) {
            System.err.println("Unexpected error loading translation cache: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 保存缓存方法 (保持不变)
    public void saveCache() {
        System.out.println("Attempting to save translation cache...");
        if (cacheFile == null) {
            System.err.println("Translation cache file is not set, cannot save cache.");
            return;
        }
        System.out.println("Cache file path: " + cacheFile.getAbsolutePath());
        if (translationCache.isEmpty()) {
            System.out.println("Translation cache is empty, no need to save.");
            if (cacheFile.exists()) {
                System.out.println("Cache is empty, deleting existing cache file...");
                if (cacheFile.delete()) {
                    System.out.println("Existing empty cache file deleted.");
                } else {
                    System.err.println("Failed to delete existing empty cache file!");
                }
            }
            return;
        }
        System.out.println("Cache has " + translationCache.size() + " entries, attempting to save.");
        File tempFile = new File(cacheFile.getAbsolutePath() + ".tmp");
        System.out.println("Temporary cache file path: " + tempFile.getAbsolutePath());

        try {
            if (cacheFile.getParentFile() != null) {
                cacheFile.getParentFile().mkdirs();
            }
            try (FileWriter writer = new FileWriter(tempFile)) {
                gson.toJson(translationCache, writer);
                System.out.println("Translation cache written to temporary file.");
            }
            if (cacheFile.exists()) {
                System.out.println("Deleting existing cache file...");
                if (!cacheFile.delete()) {
                    System.err.println("Failed to delete existing cache file! Attempting to overwrite.");
                }
            }
            System.out.println("Attempting to rename temporary file to cache file...");
            if (tempFile.renameTo(cacheFile)) {
                System.out.println("Translation cache saved successfully.");
            } else {
                System.err.println("Failed to rename temporary cache file! Source: " + tempFile.getAbsolutePath() + ", Target: " + cacheFile.getAbsolutePath());
            }
        } catch (IOException e) {
            System.err.println("Error writing translation cache file: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Unexpected error saving translation cache: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (tempFile.exists()) {
                System.out.println("Temporary cache file still exists: " + tempFile.getAbsolutePath());
                System.out.println("Attempting to delete residual temporary file...");
                if (tempFile.delete()) {
                    System.out.println("Residual temporary file deleted.");
                } else {
                    System.err.println("Failed to delete residual temporary file!");
                }
            }
        }
    }
}
