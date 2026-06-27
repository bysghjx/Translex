package top.iencand.translex.client.translate;

import com.google.gson.*;
import okhttp3.*;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.util.I18nHelper;
import top.iencand.translex.client.web.ConsoleBroadcaster;
import top.iencand.translex.client.web.MetricsCollector;

/**
 * AI API 的底层 HTTP 通信客户端。
 * 使用 OkHttp 发送请求，支持超时设置、错误码分类和重试策略。
 */
public class TranslationRequester {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_BACKOFF_MS = {1000, 2000, 4000};

    public void requestTranslation(
            String apiKey,
            String apiUrl,
            String model,
            String systemPrompt,
            String userContent,
            String cacheKey,
            String displayIdentifier,
            TranslationCallback callback) {
        requestTranslationInternal(apiKey, apiUrl, model, systemPrompt, userContent,
                cacheKey, displayIdentifier, callback, 0);
    }

    private void requestTranslationInternal(
            String apiKey,
            String apiUrl,
            String model,
            String systemPrompt,
            String userContent,
            String cacheKey,
            String displayIdentifier,
            TranslationCallback callback,
            int retryCount) {

        // Debug mode: when enabled and no valid API key, return mock translation
        if (ModConfig.get().debug && (apiKey == null || apiKey.isEmpty() || apiKey.equals("YOUR_API_KEY_HERE"))) {
            String mockResult = buildDebugMockResponse(userContent);
            ConsoleBroadcaster.broadcast("DEBUG",
                    "Debug mock translation — " + cacheKey + " (" + displayIdentifier + ")");
            Minecraft.getInstance().execute(() ->
                    callback.onTranslationComplete(cacheKey, mockResult, displayIdentifier));
            return;
        }

        if (apiKey == null || apiKey.isEmpty()) {
            callback.onTranslationComplete(cacheKey, "§c" + I18nHelper.translate("translex.error.api_key_missing"), displayIdentifier);
            return;
        }

        JsonObject requestBodyJson = new JsonObject();
        requestBodyJson.addProperty("model", model);
        JsonArray messages = new JsonArray();

        messages.add(createMsg("system", systemPrompt));
        messages.add(createMsg("user", userContent));

        requestBodyJson.add("messages", messages);

        // 禁用 reasoning/thinking 模式（避免输出 token 数暴涨 5-10 倍）
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", "disabled");
        requestBodyJson.add("thinking", thinking);

        RequestBody body = RequestBody.create(requestBodyJson.toString(), JSON);
        Request request = new Request.Builder()
                .url(apiUrl)
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                boolean retryable = e instanceof SocketTimeoutException;
                if (retryable && retryCount < MAX_RETRIES) {
                    long delay = RETRY_BACKOFF_MS[retryCount];
                    ConsoleBroadcaster.broadcast("WARN",
                            "Request failed (" + e.getClass().getSimpleName() + "), retry "
                            + (retryCount + 1) + "/" + MAX_RETRIES + " in " + delay + "ms");

                    Minecraft.getInstance().execute(() ->
                            callback.onTranslationComplete(cacheKey,
                                    "§e" + I18nHelper.translate("translex.info.retrying"), displayIdentifier));

                    scheduleRetry(delay, apiKey, apiUrl, model, systemPrompt, userContent,
                            cacheKey, displayIdentifier, callback, retryCount + 1);
                    return;
                }

                String detail;
                if (e instanceof UnknownHostException) {
                    detail = I18nHelper.translate("translex.error.io.dns");
                } else if (e instanceof ConnectException) {
                    detail = I18nHelper.translate("translex.error.io.connect");
                } else if (e instanceof SocketTimeoutException) {
                    detail = I18nHelper.translate("translex.error.io.timeout");
                } else if (e instanceof SSLHandshakeException) {
                    detail = I18nHelper.translate("translex.error.io.ssl");
                } else {
                    detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                }

                String errorMsg = "§c" + I18nHelper.translate("translex.error.network.io", detail);
                ConsoleBroadcaster.broadcast("ERROR", "Network error — " + detail);
                Minecraft.getInstance().execute(() -> callback.onTranslationComplete(cacheKey, errorMsg, displayIdentifier));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String resultMessage = "§c" + I18nHelper.translate("translex.error.api.processing");

                try (ResponseBody responseBody = response.body()) {
                    String bodyString = responseBody != null ? responseBody.string() : "";

                    int httpCode = response.code();

                    // Retryable HTTP errors with exponential backoff
                    if ((httpCode == 429 || httpCode == 502 || httpCode == 503 || httpCode == 504)
                            && retryCount < MAX_RETRIES) {
                        long delay = parseRetryAfter(response, RETRY_BACKOFF_MS[retryCount]);
                        ConsoleBroadcaster.broadcast("WARN",
                                "HTTP " + httpCode + ", retry " + (retryCount + 1) + "/"
                                + MAX_RETRIES + " in " + delay + "ms");
                        response.close();
                        scheduleRetry(delay, apiKey, apiUrl, model, systemPrompt, userContent,
                                cacheKey, displayIdentifier, callback, retryCount + 1);
                        return;
                    }

                    if (response.isSuccessful() && !bodyString.isEmpty()) {
                        JsonObject jsonResponse = JsonParser.parseString(bodyString).getAsJsonObject();
                        if (jsonResponse.has("choices") && jsonResponse.getAsJsonArray("choices").size() > 0) {
                            String content = jsonResponse.getAsJsonArray("choices").get(0)
                                    .getAsJsonObject().getAsJsonObject("message")
                                    .get("content").getAsString().trim();

                            resultMessage = content.isEmpty() ? "§c" + I18nHelper.translate("translex.error.api.empty_content") : content;

                            // 提取 API 返回的全部 token 用量
                            if (jsonResponse.has("usage")) {
                                JsonObject usage = jsonResponse.getAsJsonObject("usage");
                                long prompt     = usage.has("prompt_tokens")     ? usage.get("prompt_tokens").getAsLong()     : 0;
                                long completion = usage.has("completion_tokens") ? usage.get("completion_tokens").getAsLong() : 0;
                                long total      = usage.has("total_tokens")      ? usage.get("total_tokens").getAsLong()      : prompt + completion;
                                long cached     = 0;
                                long reasoning  = 0;
                                if (usage.has("prompt_tokens_details")) {
                                    JsonObject details = usage.getAsJsonObject("prompt_tokens_details");
                                    cached = details.has("cached_tokens") ? details.get("cached_tokens").getAsLong() : 0;
                                }
                                if (usage.has("completion_tokens_details")) {
                                    JsonObject details = usage.getAsJsonObject("completion_tokens_details");
                                    reasoning = details.has("reasoning_tokens") ? details.get("reasoning_tokens").getAsLong() : 0;
                                }
                                if (prompt > 0 || completion > 0) {
                                    MetricsCollector.get().recordActualTokenUsage(prompt, completion);
                                    MetricsCollector.get().stageTokenData(prompt, completion, total, cached, reasoning);
                                }
                            }
                        }
                    } else {
                        String httpDetail = getHttpErrorDetail(response.code());
                        resultMessage = "§c" + I18nHelper.translate("translex.error.network.http", httpDetail);
                    }
                } catch (Exception e) {
                    resultMessage = "§c" + I18nHelper.translate("translex.error.api.json_syntax");
                } finally {
                    final String finalRes = resultMessage;
                    Minecraft.getInstance().execute(() -> callback.onTranslationComplete(cacheKey, finalRes, displayIdentifier));
                }
            }
        });
    }

    private void scheduleRetry(long delayMs,
            String apiKey, String apiUrl, String model, String systemPrompt,
            String userContent, String cacheKey, String displayIdentifier,
            TranslationCallback callback, int retryCount) {
        new java.util.Timer().schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                requestTranslationInternal(apiKey, apiUrl, model, systemPrompt,
                        userContent, cacheKey, displayIdentifier, callback, retryCount);
            }
        }, delayMs);
    }

    /** Parse Retry-After header (seconds or HTTP-date). Falls back to defaultDelay. */
    private static long parseRetryAfter(Response response, long defaultDelay) {
        String header = response.header("Retry-After");
        if (header == null) return defaultDelay;
        try {
            return Long.parseLong(header) * 1000;
        } catch (NumberFormatException ignored) {
            return defaultDelay;
        }
    }

    /** 获取 HTTP 错误码对应的本地化描述文本 */
    private String getHttpErrorDetail(int code) {
        return switch (code) {
            case 400 -> I18nHelper.translate("translex.error.http.400");
            case 401 -> I18nHelper.translate("translex.error.http.401");
            case 402 -> I18nHelper.translate("translex.error.http.402");
            case 403 -> I18nHelper.translate("translex.error.http.403");
            case 404 -> I18nHelper.translate("translex.error.http.404");
            case 408 -> I18nHelper.translate("translex.error.http.408");
            case 413 -> I18nHelper.translate("translex.error.http.413");
            case 422 -> I18nHelper.translate("translex.error.http.422");
            case 429 -> I18nHelper.translate("translex.error.http.429");
            case 500 -> I18nHelper.translate("translex.error.http.500");
            case 502 -> I18nHelper.translate("translex.error.http.502");
            case 503 -> I18nHelper.translate("translex.error.http.503");
            case 504 -> I18nHelper.translate("translex.error.http.504");
            default -> String.valueOf(code);
        };
    }

    /** 创建一条聊天消息的 JSON 对象（角色 + 内容） */
    private JsonObject createMsg(String role, String content) {
        JsonObject obj = new JsonObject();
        obj.addProperty("role", role);
        obj.addProperty("content", content);
        return obj;
    }

    /**
     * 调试模式：将输入 JSON 字典中的每个值加上调试前缀后原样返回，
     * 模拟 AI 翻译的响应格式，无需 API Key 即可测试翻译流程。
     *
     * @param inputJson 原始请求的 JSON 字典字符串，如 {@code {"0":"Hello","1":"World"}}
     * @return 模拟的翻译结果 JSON，如 {@code {"0":"§e[DEBUG]§r Hello","1":"§e[DEBUG]§r World"}}
     */
    private String buildDebugMockResponse(String inputJson) {
        JsonObject result = new JsonObject();
        try {
            JsonObject input = JsonParser.parseString(inputJson).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : input.entrySet()) {
                String original = entry.getValue().getAsString();
                result.addProperty(entry.getKey(), "§e[DEBUG]§r " + original);
            }
        } catch (Exception e) {
            // If parsing fails, return a simple debug marker
            result.addProperty("0", "§e[DEBUG]§r " + inputJson);
        }
        return result.toString();
    }
}
