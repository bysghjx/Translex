package top.iencand.translex.client.Translate;

// 定义一个回调接口，用于在请求完成后报告结果
@FunctionalInterface // 可以标记为函数式接口，如果只有一个抽象方法
interface TranslationCallback {
    // 当翻译请求完成时调用
    // translatedTextForDisplay 包含翻译结果或错误信息，已经带颜色码和前缀
    void onTranslationComplete(String cacheKey, String translatedTextForDisplay, String displayIdentifier);
}
