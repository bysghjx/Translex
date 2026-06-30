package top.iencand.translex.client.translate.provider;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Anthropic 原生 Messages API 适配器。
 *
 * <p>与 OpenAI 的差异：{@code system} 为顶层独立字段（不在 messages 内）、
 * {@code max_tokens} 必填、认证用 {@code x-api-key} + {@code anthropic-version} 头、
 * 响应内容在 {@code content[].text}、用量字段为 {@code input_tokens}/{@code output_tokens}。
 * 可选用户补充指令并入第一条 user 消息（Anthropic 要求 messages 以 user 开头且角色交替）。</p>
 */
public class AnthropicProvider implements AiProvider {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Override
    public String id() { return "anthropic"; }

    @Override
    public String displayName() { return "Anthropic (Claude)"; }

    @Override
    public String buildRequestBody(AiRequest req) {
        JsonObject root = new JsonObject();
        root.addProperty("model", req.model());
        root.addProperty("max_tokens", req.maxTokens() > 0 ? req.maxTokens() : 4096);
        root.addProperty("system", req.systemPrompt());

        // 显式禁用 thinking/extended-thinking，避免输出 token 暴涨（翻译任务不需要推理）
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", "disabled");
        root.add("thinking", thinking);

        // Anthropic 要求 messages 以 user 开头，无独立 system 消息。
        // 可选补充指令拼到载荷前，作为单条 user 消息发送。
        StringBuilder userText = new StringBuilder();
        if (req.optionalUserPrompt() != null && !req.optionalUserPrompt().isBlank()) {
            userText.append(req.optionalUserPrompt()).append("\n\n");
        }
        userText.append(req.userContent());

        JsonArray messages = new JsonArray();
        messages.add(msg("user", userText.toString()));
        root.add("messages", messages);

        return GSON.toJson(root);
    }

    @Override
    public Request buildRequest(AiRequest req, RequestBody body) {
        String version = (req.anthropicVersion() != null && !req.anthropicVersion().isBlank())
                ? req.anthropicVersion() : "2023-06-01";
        return new Request.Builder()
                .url(req.apiUrl())
                .post(body)
                .addHeader("x-api-key", req.apiKey())
                .addHeader("anthropic-version", version)
                .build();
    }

    @Override
    public AiResponse parseResponse(String bodyString) {
        JsonObject root = JsonParser.parseString(bodyString).getAsJsonObject();
        if (!root.has("content") || root.getAsJsonArray("content").isEmpty()) {
            return AiResponse.failure();
        }
        // 拼接所有 type=text 的块（通常仅一块）。
        StringBuilder sb = new StringBuilder();
        JsonArray content = root.getAsJsonArray("content");
        for (int i = 0; i < content.size(); i++) {
            JsonObject block = content.get(i).getAsJsonObject();
            if (block.has("text")) sb.append(block.get("text").getAsString());
        }
        String text = sb.toString().trim();

        long prompt = 0, completion = 0, cached = 0;
        if (root.has("usage")) {
            JsonObject usage = root.getAsJsonObject("usage");
            prompt     = usage.has("input_tokens")  ? usage.get("input_tokens").getAsLong()  : 0;
            completion = usage.has("output_tokens") ? usage.get("output_tokens").getAsLong() : 0;
            cached     = usage.has("cache_read_input_tokens")
                    ? usage.get("cache_read_input_tokens").getAsLong() : 0;
        }
        return new AiResponse(text, !text.isEmpty(), prompt, completion, prompt + completion, cached, 0);
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }
}
