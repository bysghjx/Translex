package top.iencand.translex.client.Translate;

import com.google.gson.*;
import okhttp3.*;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.MinecraftClient;
import top.iencand.translex.client.util.I18nHelper;

/**
 * 负责与 AI 接口进行底层的 HTTP 通信。
 */
public class TranslationRequester {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

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
                cacheKey, displayIdentifier, callback, true);
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
            boolean allowRetry) {

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

        RequestBody body = RequestBody.create(requestBodyJson.toString(), JSON);
        Request request = new Request.Builder()
                .url(apiUrl)
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (e instanceof SocketTimeoutException && allowRetry) {
                    MinecraftClient.getInstance().execute(() ->
                            callback.onTranslationComplete(cacheKey,
                                    "§e" + I18nHelper.translate("translex.info.retrying"), displayIdentifier));

                    requestTranslationInternal(apiKey, apiUrl, model, systemPrompt, userContent,
                            cacheKey, displayIdentifier, callback, false);
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
                MinecraftClient.getInstance().execute(() -> callback.onTranslationComplete(cacheKey, errorMsg, displayIdentifier));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String resultMessage = "§c" + I18nHelper.translate("translex.error.api.processing");

                try (ResponseBody responseBody = response.body()) {
                    String bodyString = responseBody != null ? responseBody.string() : "";

                    if (response.isSuccessful() && !bodyString.isEmpty()) {
                        JsonObject jsonResponse = JsonParser.parseString(bodyString).getAsJsonObject();
                        if (jsonResponse.has("choices") && jsonResponse.getAsJsonArray("choices").size() > 0) {
                            String content = jsonResponse.getAsJsonArray("choices").get(0)
                                    .getAsJsonObject().getAsJsonObject("message")
                                    .get("content").getAsString().trim();

                            resultMessage = content.isEmpty() ? "§c" + I18nHelper.translate("translex.error.api.empty_content") : content;
                        }
                    } else {
                        String httpDetail = getHttpErrorDetail(response.code());
                        resultMessage = "§c" + I18nHelper.translate("translex.error.network.http", httpDetail);
                    }
                } catch (Exception e) {
                    resultMessage = "§c" + I18nHelper.translate("translex.error.api.json_syntax");
                } finally {
                    final String finalRes = resultMessage;
                    MinecraftClient.getInstance().execute(() -> callback.onTranslationComplete(cacheKey, finalRes, displayIdentifier));
                }
            }
        });
    }

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

    private JsonObject createMsg(String role, String content) {
        JsonObject obj = new JsonObject();
        obj.addProperty("role", role);
        obj.addProperty("content", content);
        return obj;
    }
}
