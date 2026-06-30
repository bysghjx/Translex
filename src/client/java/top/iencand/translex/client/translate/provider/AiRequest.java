package top.iencand.translex.client.translate.provider;

/**
 * 一次翻译请求的供应商无关参数集合。
 *
 * <p>由 {@link top.iencand.translex.client.translate.TranslationRequester} 组装，
 * 交给 {@link AiProvider#buildRequest} 转换为各供应商特定的 HTTP 请求。</p>
 *
 * @param apiKey             API 密钥
 * @param apiUrl             端点 URL
 * @param model              模型名
 * @param systemPrompt       强制 system prompt
 * @param optionalUserPrompt 可选用户补充指令（空则不发）
 * @param userContent        实际待翻译载荷（字典 JSON）
 * @param maxTokens          最大输出 token（Anthropic 必需；OpenAI 忽略）
 * @param anthropicVersion   anthropic-version 头取值（仅 Anthropic 使用）
 */
public record AiRequest(
        String apiKey,
        String apiUrl,
        String model,
        String systemPrompt,
        String optionalUserPrompt,
        String userContent,
        int maxTokens,
        String anthropicVersion) {
}
