package top.iencand.translex.client.translate.provider;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import top.iencand.translex.client.config.ModConfig;

/**
 * OpenAI 兼容格式适配器（DeepSeek / OpenAI / 各类兼容端点）。
 *
 * <p>请求：{@code {model, messages:[{role,content}], thinking:{type:disabled}}}，
 * 头 {@code Authorization: Bearer <key>}。响应：{@code choices[0].message.content} + {@code usage}。
 * 与重构前 {@code TranslationRequester} 的硬编码行为完全一致。</p>
 */
public class OpenAiProvider implements AiProvider {

    /** 禁用 HTML 转义：避免 &lt;s0&gt; 被序列化为 \\u003c，浪费 token。 */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Override
    public String id() { return "openai"; }

    @Override
    public String displayName() { return "OpenAI Compatible"; }

    @Override
    public String buildRequestBody(AiRequest req) {
        JsonObject root = new JsonObject();
        root.addProperty("model", req.model());

        JsonArray messages = new JsonArray();
        messages.add(msg("system", req.systemPrompt()));
        if (req.optionalUserPrompt() != null && !req.optionalUserPrompt().isBlank()) {
            messages.add(msg("user", req.optionalUserPrompt()));
        }
        messages.add(msg("user", req.userContent()));
        root.add("messages", messages);

        // 禁用 reasoning/thinking 模式（避免输出 token 数暴涨 5-10 倍）
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", "disabled");
        root.add("thinking", thinking);

        // 采样温度（降低随机性，减少幻觉）；structured output 强制合法 JSON
        ModConfig cfg = ModConfig.get();
        if (cfg.temperature >= 0) root.addProperty("temperature", cfg.temperature);
        if (cfg.structuredOutput) {
            JsonObject fmt = new JsonObject();
            fmt.addProperty("type", "json_object");
            root.add("response_format", fmt);
        }

        return GSON.toJson(root);
    }

    @Override
    public Request buildRequest(AiRequest req, RequestBody body) {
        return new Request.Builder()
                .url(req.apiUrl())
                .post(body)
                .addHeader("Authorization", "Bearer " + req.apiKey())
                .build();
    }

    @Override
    public AiResponse parseResponse(String bodyString) {
        JsonObject root = JsonParser.parseString(bodyString).getAsJsonObject();
        if (!root.has("choices") || root.getAsJsonArray("choices").isEmpty()) {
            return AiResponse.failure();
        }
        String content = root.getAsJsonArray("choices").get(0)
                .getAsJsonObject().getAsJsonObject("message")
                .get("content").getAsString().trim();

        long prompt = 0, completion = 0, total = 0, cached = 0, reasoning = 0;
        if (root.has("usage")) {
            JsonObject usage = root.getAsJsonObject("usage");
            prompt     = usage.has("prompt_tokens")     ? usage.get("prompt_tokens").getAsLong()     : 0;
            completion = usage.has("completion_tokens") ? usage.get("completion_tokens").getAsLong() : 0;
            total      = usage.has("total_tokens")      ? usage.get("total_tokens").getAsLong()      : prompt + completion;
            if (usage.has("prompt_tokens_details")) {
                JsonObject d = usage.getAsJsonObject("prompt_tokens_details");
                cached = d.has("cached_tokens") ? d.get("cached_tokens").getAsLong() : 0;
            }
            if (usage.has("completion_tokens_details")) {
                JsonObject d = usage.getAsJsonObject("completion_tokens_details");
                reasoning = d.has("reasoning_tokens") ? d.get("reasoning_tokens").getAsLong() : 0;
            }
        }
        return new AiResponse(content, !content.isEmpty(), prompt, completion, total, cached, reasoning);
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }
}
