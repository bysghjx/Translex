package top.iencand.translex.client.translate;

/**
 * 翻译请求完成后的回调接口。
 * 在 {@link TranslationRequester} 收到 AI 响应后调用，将结果传递回请求发起方。
 */
@FunctionalInterface
public interface TranslationCallback {
    /**
     * 翻译请求完成时调用。
     * @param cacheKey 用于缓存查找的键
     * @param translatedTextForDisplay 翻译结果文本（已带颜色码和前缀，可直接显示）
     * @param displayIdentifier 显示标识符，用于关联请求与响应
     * @param rawResponseBody 服务器返回的原始响应体（用于抓包页展示）；
     *                        网络层失败/无 body 时可能为简短说明或 null
     */
    void onTranslationComplete(String cacheKey, String translatedTextForDisplay,
                               String displayIdentifier, String rawResponseBody);
}
