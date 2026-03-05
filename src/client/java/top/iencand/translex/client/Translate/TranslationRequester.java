package top.iencand.translex.client.Translate;

import com.google.gson.*;
import okhttp3.*;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.MinecraftClient;
import top.iencand.translex.client.util.I18nHelper;

public class TranslationRequester {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
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

        if (apiKey == null || apiKey.isEmpty()) {
            callback.onTranslationComplete(cacheKey, "§c" + I18nHelper.translate("translex.error.api_key_missing"), displayIdentifier);
            return;
        }

        JsonObject requestBodyJson = new JsonObject();
        requestBodyJson.addProperty("model", model);
        JsonArray messages = new JsonArray();

        // 系统指令
        messages.add(createMsg("system", systemPrompt));
        // 用户输入的原文列表
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
                String errorMsg = "§c" + I18nHelper.translate("translex.error.network.io", e.getMessage());
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
                        resultMessage = "§c" + I18nHelper.translate("translex.error.network.http", response.code());
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

    private JsonObject createMsg(String role, String content) {
        JsonObject obj = new JsonObject();
        obj.addProperty("role", role);
        obj.addProperty("content", content);
        return obj;
    }
}