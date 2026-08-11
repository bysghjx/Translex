package top.iencand.translex.client.translate;

import com.google.gson.*;
import okhttp3.*;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.translate.provider.AiProvider;
import top.iencand.translex.client.translate.provider.AiProviders;
import top.iencand.translex.client.translate.provider.AiRequest;
import top.iencand.translex.client.translate.provider.AiResponse;
import top.iencand.translex.client.util.I18nHelper;
import top.iencand.translex.client.web.ConsoleBroadcaster;
import top.iencand.translex.client.web.MetricsCollector;

/**
 * AI API 的底层 HTTP 通信客户端。
 * 使用 OkHttp 发送请求，支持超时设置、错误码分类和重试策略。
 *
 * <p>请求体构造、请求头、响应解析全部委托给 {@link AiProvider} 适配器
 * （由 {@link ModConfig#provider} 选定，经 {@link AiProviders} 解析），
 * 因此本类不再绑定任何特定供应商格式。</p>
 */
public class TranslationRequester {
    /** 禁用 HTML 转义的 Gson：避免 &lt;s0&gt; 被序列化为 \\u003cs0\\u003e，浪费 token。 */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_BACKOFF_MS = {1000, 2000, 4000};

    /** 重试专用调度器：复用单条 daemon 线程，避免每次重试新建一个 Timer 线程。 */
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "translex-retry");
        t.setDaemon(true);
        return t;
    });

    public void requestTranslation(
            String apiKey,
            String apiUrl,
            String model,
            String systemPrompt,
            String optionalUserPrompt,
            String userContent,
            String cacheKey,
            String displayIdentifier,
            TranslationCallback callback) {
        requestTranslationInternal(apiKey, apiUrl, model, systemPrompt, optionalUserPrompt, userContent,
                cacheKey, displayIdentifier, callback, 0);
    }

    private void requestTranslationInternal(
            String apiKey,
            String apiUrl,
            String model,
            String systemPrompt,
            String optionalUserPrompt,
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
                    callback.onTranslationComplete(cacheKey, mockResult, displayIdentifier, mockResult));
            return;
        }

        if (apiKey == null || apiKey.isEmpty()) {
            callback.onTranslationComplete(cacheKey, "§c" + I18nHelper.translate("translex.error.api_key_missing"), displayIdentifier, null);
            return;
        }

        ModConfig cfg = ModConfig.get();
        AiProvider provider = AiProviders.get(cfg.provider);
        AiRequest aiReq = new AiRequest(apiKey, apiUrl, model, systemPrompt, optionalUserPrompt,
                userContent, cfg.maxTokens, cfg.anthropicVersion);

        RequestBody body = RequestBody.create(provider.buildRequestBody(aiReq), JSON);
        Request request = provider.buildRequest(aiReq, body);

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
                                    "§e" + I18nHelper.translate("translex.info.retrying"), displayIdentifier, null));

                    scheduleRetry(delay, apiKey, apiUrl, model, systemPrompt, optionalUserPrompt, userContent,
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
                final String netDetail = detail;
                Minecraft.getInstance().execute(() -> callback.onTranslationComplete(cacheKey, errorMsg, displayIdentifier,
                        "[network error] " + netDetail));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String resultMessage = "§c" + I18nHelper.translate("translex.error.api.processing");
                String rawBody = "";

                try (ResponseBody responseBody = response.body()) {
                    String bodyString = responseBody != null ? responseBody.string() : "";
                    rawBody = bodyString;

                    int httpCode = response.code();

                    // Retryable HTTP errors with exponential backoff
                    if ((httpCode == 429 || httpCode == 502 || httpCode == 503 || httpCode == 504)
                            && retryCount < MAX_RETRIES) {
                        long delay = parseRetryAfter(response, RETRY_BACKOFF_MS[retryCount]);
                        ConsoleBroadcaster.broadcast("WARN",
                                "HTTP " + httpCode + ", retry " + (retryCount + 1) + "/"
                                + MAX_RETRIES + " in " + delay + "ms");
                        response.close();
                        scheduleRetry(delay, apiKey, apiUrl, model, systemPrompt, optionalUserPrompt, userContent,
                                cacheKey, displayIdentifier, callback, retryCount + 1);
                        return;
                    }

                    if (response.isSuccessful() && !bodyString.isEmpty()) {
                        AiResponse parsed = provider.parseResponse(bodyString);
                        if (parsed.success()) {
                            resultMessage = parsed.content();
                            // 未翻译检测：响应与输入一致时标记 warning（不阻塞流程，仍显示原文）
                            if (isUntranslated(userContent, parsed.content())) {
                                String snippet = parsed.content().length() > 120
                                        ? parsed.content().substring(0, 120) + "…" : parsed.content();
                                ConsoleBroadcaster.broadcast("WARN",
                                        "模型原样返回，可能未翻译 — " + displayIdentifier
                                                + " | content=" + snippet);
                            }
                            if (parsed.prompt() > 0 || parsed.completion() > 0) {
                                MetricsCollector.get().recordActualTokenUsage(parsed.prompt(), parsed.completion());
                                MetricsCollector.get().stageTokenData(parsed.prompt(), parsed.completion(),
                                        parsed.total(), parsed.cached(), parsed.reasoning());
                            }
                        } else {
                            // 解析不出内容：把原始响应体（截断）暴露出来，便于定位是模型错误/空content/error JSON
                            String snippet = bodyString.length() > 800 ? bodyString.substring(0, 800) + "…" : bodyString;
                            ConsoleBroadcaster.broadcast("ERROR",
                                    "Empty/unparseable content from provider '" + provider.id()
                                    + "' (HTTP " + response.code() + "). Raw body: " + snippet);
                            resultMessage = "§c" + I18nHelper.translate("translex.error.api.empty_content");
                        }
                    } else {
                        String snippet = bodyString.length() > 800 ? bodyString.substring(0, 800) + "…" : bodyString;
                        ConsoleBroadcaster.broadcast("ERROR",
                                "HTTP " + response.code() + " from provider '" + provider.id()
                                + "'. Raw body: " + snippet);
                        String httpDetail = getHttpErrorDetail(response.code());
                        resultMessage = "§c" + I18nHelper.translate("translex.error.network.http", httpDetail);
                    }
                } catch (Exception e) {
                    resultMessage = "§c" + I18nHelper.translate("translex.error.api.json_syntax");
                } finally {
                    final String finalRes = resultMessage;
                    final String finalRawBody = rawBody;
                    Minecraft.getInstance().execute(() ->
                            callback.onTranslationComplete(cacheKey, finalRes, displayIdentifier, finalRawBody));
                }
            }
        });
    }

    private void scheduleRetry(long delayMs,
            String apiKey, String apiUrl, String model, String systemPrompt,
            String optionalUserPrompt, String userContent, String cacheKey, String displayIdentifier,
            TranslationCallback callback, int retryCount) {
        retryScheduler.schedule(() -> requestTranslationInternal(apiKey, apiUrl, model, systemPrompt,
                optionalUserPrompt, userContent, cacheKey, displayIdentifier, callback, retryCount),
                delayMs, TimeUnit.MILLISECONDS);
    }

    /** 释放重试调度器：取消尚未执行的 pending 重试。 */
    public void shutdown() {
        retryScheduler.shutdownNow();
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

    /**
     * 检测 AI 是否原样返回了输入（未翻译）。
     * 解析两边 JSON 对象，若所有 value 与原文逐字相同则判定为未翻译；
     * JSON 解析失败时回退到 trim 后的字符串比较。
     * 用于在模型退化（如 flash 模型复制输入）时标记 warning，而非静默当成成功。
     */
    private static boolean isUntranslated(String userContent, String aiContent) {
        if (userContent == null || aiContent == null) return false;
        if (userContent.isBlank() || aiContent.isBlank()) return false;
        // 快速路径：整体完全相同
        if (userContent.trim().equals(aiContent.trim())) return true;
        // JSON 路径：逐 value 比较（容忍键序差异）
        try {
            JsonObject u = JsonParser.parseString(userContent).getAsJsonObject();
            JsonObject a = JsonParser.parseString(aiContent).getAsJsonObject();
            if (!u.keySet().equals(a.keySet())) return false;
            for (String key : u.keySet()) {
                if (!u.get(key).getAsString().equals(a.get(key).getAsString())) {
                    return false; // 任一 value 不同 → 有翻译
                }
            }
            return true; // 所有 value 都相同 → 未翻译
        } catch (Exception e) {
            return false; // 非 JSON 或解析失败，不判定
        }
    }
}
