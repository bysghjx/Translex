package top.iencand.translex.client.Translate; // 更新包名

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import okhttp3.*; // 导入 OkHttp 相关类
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit; // 用于 OkHttpClient 配置

// 导入 Gson 相关的类
// import com.google.gson.Gson; // 已在上面导入
// import com.google.gson.JsonObject; // 已在上面导入
// import com.google.gson.JsonParser; // 已在上面导入
// import com.google.gson.JsonSyntaxException; // 已在上面导入

// TranslationCallback 接口现在也在同一个包中，不需要额外的导入语句

public class TranslationRequester {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client; // OkHttp 客户端实例
    private final Gson gson = new Gson(); // 添加 Gson 实例

    // 构造函数，创建 OkHttpClient 实例
    public TranslationRequester() {
        // 配置 OkHttpClient，例如设置超时时间
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                // 可以添加其他配置，例如缓存、拦截器等
                .build();
    }

    /**
     * 异步发送翻译请求。
     * 这个方法会使用 OkHttp 的异步 API，在 OkHttp 内部的线程池中执行。
     * @param apiKey AI API Key.
     * @param apiUrl API Endpoint URL.
     * @param model AI Model name.
     * @param targetLanguagePrompt System prompt for the AI.
     * @param messageTextToTranslate 需要翻译的纯文本内容.
     * @param cacheKey 用于缓存的键 (通常是原始文本).
     * @param displayIdentifier 用于在日志和最终聊天消息中标识请求 (例如 "ID: 123" 或 "次数: 456").
     * @param callback 请求完成后调用的回调函数.
     */
    public void requestTranslation(
            String apiKey,
            String apiUrl,
            String model,
            String targetLanguagePrompt,
            String messageTextToTranslate,
            String cacheKey,
            String displayIdentifier,
            TranslationCallback callback) { // 使用新的包中的 TranslationCallback

        // --- 输入验证 (可以在调用方进行，也可以在此处再次验证) ---
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("YOUR_ACTUAL_API_KEY")) {
            callback.onTranslationComplete(cacheKey, "§c[翻译 Mod]§r API Key 未设置！", displayIdentifier);
            return;
        }
        if (messageTextToTranslate == null || messageTextToTranslate.trim().isEmpty()) {
            callback.onTranslationComplete(cacheKey, "§c[翻译 Mod]§r 消息内容为空，无需翻译。", displayIdentifier);
            return;
        }

        // --- 构建 API 请求体 ---
        JsonObject requestBodyJson = new JsonObject();
        requestBodyJson.addProperty("model", model);
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", targetLanguagePrompt);
        messages.add(systemMessage);
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", messageTextToTranslate);
        messages.add(userMessage);
        requestBodyJson.add("messages", messages);

        RequestBody body = RequestBody.create(requestBodyJson.toString(), JSON); // 使用 OkHttp 的 RequestBody

        Request request = new Request.Builder()
                .url(apiUrl)
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json; utf-8") // 在 MediaType 中已设置
                .addHeader("Accept", "application/json")
                .build();

        System.out.println("Mod Translation Requester (" + displayIdentifier + ") - Sending API request.");

        // --- 使用 OkHttp 异步发送请求 ---
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                System.err.println("Mod Translation Requester (" + displayIdentifier + ") - Network request failed: " + e.getMessage());
                e.printStackTrace();
                // 在失败时调用回调，传递错误信息
                callback.onTranslationComplete(cacheKey, "§c[翻译失败]§r 网络错误: " + e.getMessage(), displayIdentifier);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = null;
                String finalTranslatedTextForDisplay = null;

                try {
                    responseBody = response.body() != null ? response.body().string() : ""; // 获取响应体字符串

                    if (response.isSuccessful()) { // 检查 HTTP 状态码 (2xx)
                        if (!responseBody.isEmpty()) {
                            System.out.println("Raw API Response (" + displayIdentifier + "): " + responseBody);
                            try {
                                // --- 处理 API 响应 JSON (保持与原代码类似) ---
                                JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
                                boolean hasChoices = jsonResponse.has("choices");
                                int choicesSize = (hasChoices && jsonResponse.get("choices") != null && jsonResponse.get("choices").isJsonArray())
                                        ? jsonResponse.getAsJsonArray("choices").size() : 0;
                                JsonObject firstChoice = (choicesSize > 0 && jsonResponse.getAsJsonArray("choices").get(0) != null && jsonResponse.getAsJsonArray("choices").get(0).isJsonObject())
                                        ? jsonResponse.getAsJsonArray("choices").get(0).getAsJsonObject() : null;
                                boolean hasMessage = (firstChoice != null && firstChoice.has("message") && firstChoice.get("message") != null && firstChoice.get("message").isJsonObject());
                                JsonObject messageObject = hasMessage ? firstChoice.getAsJsonObject("message") : null;
                                boolean hasContent = (messageObject != null && messageObject.has("content"));

                                System.out.println("Mod Translation Requester (" + displayIdentifier + ") - JSON Check: hasChoices=" + hasChoices + ", choicesSize=" + choicesSize + ", hasMessage=" + hasMessage + ", hasContent=" + hasContent);

                                if (hasChoices && choicesSize > 0) {
                                    if (hasMessage && hasContent) {
                                        String rawTranslatedText = null;
                                        if (messageObject.get("content") != null && messageObject.get("content").isJsonPrimitive() && messageObject.get("content").getAsJsonPrimitive().isString()) {
                                            rawTranslatedText = messageObject.get("content").getAsString();
                                        }

                                        if (rawTranslatedText != null) {
                                            // --- 清理文本 (保持与原代码类似) ---
                                            String cleanedText = rawTranslatedText.trim();
                                            while (cleanedText.length() >= 2 && cleanedText.charAt(0) == '§') {
                                                char code = cleanedText.charAt(1);
                                                if ( (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') ||
                                                        (code >= 'k' && code <= 'o') || code == 'r' ) {
                                                    cleanedText = cleanedText.substring(2).trim();
                                                } else {
                                                    break;
                                                }
                                            }
                                            // cleanedText = cleanedText.replaceAll("\\s+", " "); // 可选：压缩内部空格
                                            // --- 结束清理 ---

                                            if (!cleanedText.isEmpty()) {
                                                System.out.println("Mod Translation Requester (" + displayIdentifier + ") - Assigning successful text.");
                                                finalTranslatedTextForDisplay = "§9[AI翻译]§r " + cleanedText;
                                            } else {
                                                System.out.println("Mod Translation Requester (" + displayIdentifier + ") - Assigning empty content error.");
                                                finalTranslatedTextForDisplay = "§c[翻译失败]§r AI返回空内容";
                                            }
                                        } else {
                                            System.out.println("Mod Translation Requester (" + displayIdentifier + ") - Assigning content type error.");
                                            finalTranslatedTextForDisplay = "§c[翻译失败]§r API返回结果内容非文本";
                                        }
                                    } else {
                                        System.out.println("Mod Translation Requester (" + displayIdentifier + ") - Assigning structural error (inner).");
                                        finalTranslatedTextForDisplay = "§c[翻译失败]§r API返回结果格式未知 (字段缺失)";
                                    }
                                } else {
                                    System.out.println("Mod Translation Requester (" + displayIdentifier + ") - Assigning structural error (outer).");
                                    finalTranslatedTextForDisplay = "§c[翻译失败]§r API返回结果格式未知 (Choices缺失或为空)";
                                }
                            } catch (JsonSyntaxException jsonEx) {
                                System.err.println("Mod Translation Requester (" + displayIdentifier + ") - Error parsing API response JSON: " + jsonEx.getMessage());
                                jsonEx.printStackTrace();
                                finalTranslatedTextForDisplay = "§c[翻译失败]§r API响应格式无效(JSON语法错误)";
                            } catch (IllegalStateException | NullPointerException parseEx) {
                                System.err.println("Mod Translation Requester (" + displayIdentifier + ") - Error parsing API response JSON structure: " + parseEx.getMessage());
                                parseEx.printStackTrace();
                                finalTranslatedTextForDisplay = "§c[翻译失败]§r API响应结构意外";
                            } catch (Exception e) {
                                System.err.println("Mod Translation Requester (" + displayIdentifier + ") - Other error processing response: " + e.getMessage());
                                e.printStackTrace();
                                finalTranslatedTextForDisplay = "§c[翻译失败]§r 处理响应时出错";
                            }
                            if (finalTranslatedTextForDisplay == null) {
                                System.err.println("Mod Translation Requester (" + displayIdentifier + ") - finalTranslatedTextForDisplay is null after processing!");
                                finalTranslatedTextForDisplay = "§c[翻译失败]§r API返回结果格式未知 (最终 null)";
                            }
                        } else { // HTTP 状态码表示错误
                            System.err.println("AI API returned error response (" + displayIdentifier + "): " + response.code() + ", Body: " + responseBody);
                            finalTranslatedTextForDisplay = "§c[翻译失败]§r API错误 (" + response.code() + ")";
                            // 可以尝试解析 responseBody 中的错误信息
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Mod Translation Requester (" + displayIdentifier + ") - Unexpected error during response processing: " + e.getMessage());
                    e.printStackTrace();
                    finalTranslatedTextForDisplay = "§c[翻译严重错误]§r " + e.getClass().getSimpleName();
                } finally {
                    // 关闭响应体，释放资源
                    if (response.body() != null) {
                        response.body().close();
                    }
                    // 调用回调函数，将结果报告给调用方 (TranslationManager)
                    // 回调函数将在 OkHttp 的工作线程中执行
                    callback.onTranslationComplete(cacheKey, finalTranslatedTextForDisplay != null ? finalTranslatedTextForDisplay : "§c[翻译处理异常]§r", displayIdentifier);
                }
            }
        });
    }
}