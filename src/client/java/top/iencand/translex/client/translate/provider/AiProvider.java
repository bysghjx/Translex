package top.iencand.translex.client.translate.provider;

import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * AI 供应商适配器。把供应商无关的 {@link AiRequest} 转换为具体的 HTTP 请求，
 * 并把响应体解析回供应商无关的 {@link AiResponse}。
 *
 * <p>新增供应商只需实现本接口并在 {@link AiProviders} 注册，
 * 无需改动 {@link top.iencand.translex.client.translate.TranslationRequester}。</p>
 */
public interface AiProvider {

    /** 供应商唯一标识（小写），用于配置中 {@code provider} 字段及注册表查找。 */
    String id();

    /** 人类可读名称，用于 UI 显示。 */
    String displayName();

    /**
     * 构造该供应商的 HTTP 请求（含 URL、方法、头、请求体）。
     *
     * @param req  供应商无关请求参数
     * @param body 已根据 {@link #buildRequestBody} 构造好的请求体
     */
    Request buildRequest(AiRequest req, RequestBody body);

    /** 构造请求体 JSON 字符串（交给调用方包装成 {@link RequestBody}，便于抓包展示同一份内容）。 */
    String buildRequestBody(AiRequest req);

    /**
     * 解析成功的 HTTP 响应体。
     *
     * @param bodyString 响应体原文
     * @return 解析结果；无法解析时返回 {@link AiResponse#failure()}
     */
    AiResponse parseResponse(String bodyString);
}
