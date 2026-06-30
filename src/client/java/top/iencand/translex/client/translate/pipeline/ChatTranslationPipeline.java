package top.iencand.translex.client.translate.pipeline;

import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.translate.TranslationPrompts;
import top.iencand.translex.client.translate.TranslationRequester;
import top.iencand.translex.client.translate.TranslationSplitter;
import top.iencand.translex.client.translate.cache.TranslationCacheManager;
import top.iencand.translex.client.translate.render.ChatRenderer;
import top.iencand.translex.client.util.I18nHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

/**
 * 聊天翻译管线 facade。
 *
 * <p>独立的 {@link BatchDispatcher}（chat 配置）+ {@link TranslationSplitter} + {@link ChatRenderer}。
 * <b>不走持久缓存</b>（聊天内容多为一次性，缓存意义不大；折叠去重由聊天 handler 自行处理）。
 * 处理 {@code /translex translate <id>} 和 {@code /translex text} 两类来源，结果渲染到聊天栏。
 * 另提供 {@code /translex say}（反向：中译英并自动发送到服务器聊天）。</p>
 */
public class ChatTranslationPipeline {

    private final TranslationSplitter splitter = new TranslationSplitter();
    private final ChatRenderer renderer = new ChatRenderer();
    private final TranslationRequester requester;
    private final BatchDispatcher dispatcher;

    public ChatTranslationPipeline(TranslationRequester sharedRequester) {
        this.requester = sharedRequester;
        PipelineConfig config = new PipelineConfig(
                "TL_CHAT",
                1500,
                () -> TranslationPrompts.chatSystemPrompt(ModConfig.get().targetLanguage, ModConfig.get().properNounMode),
                () -> ModConfig.get().userChatPrompt,
                "Translex-Dispatcher-Chat"
        );
        this.dispatcher = new BatchDispatcher(config, sharedRequester);
    }

    /** 聊天消息翻译（不查缓存，直接发送）。 */
    public void translateChatMessageAsync(String text, String displayId) {
        if (text == null || text.isBlank()) return;
        TranslationSplitter.SplitResult split = splitter.split(text, TranslationCacheManager::applyGlossaryStatic, false);
        if (!split.needsTranslation()) {
            renderer.renderResult(text, mergePreTranslated(split), displayId);
            return;
        }
        dispatcher.submit(split.untranslatedText())
                .thenAccept(translated -> {
                    if (isError(translated)) {
                        renderer.renderError(translated, displayId);
                        return;
                    }
                    String full = splitter.merge(split, translated);
                    renderer.renderResult(text, full, displayId);
                });
    }

    /**
     * 自由文本翻译（{@code /translex text}）。
     * 已划入聊天管线并去掉持久缓存查询/写入。
     */
    public void translateTextAsync(String text, String displayId) {
        if (text == null || text.isBlank()) return;
        TranslationSplitter.SplitResult split = splitter.split(text, TranslationCacheManager::applyGlossaryStatic, false);
        if (!split.needsTranslation()) {
            renderer.renderResult(text, mergePreTranslated(split), displayId);
            return;
        }
        dispatcher.submit(split.untranslatedText())
                .thenAccept(translated -> {
                    if (isError(translated)) {
                        renderer.renderError(translated, displayId);
                        return;
                    }
                    String full = splitter.merge(split, translated);
                    renderer.renderResult(text, full, displayId);
                });
    }

    /** AI 返回的失败结果以 §c（红色）开头。 */
    private static boolean isError(String result) {
        return result != null && result.startsWith("§c");
    }

    private static String mergePreTranslated(TranslationSplitter.SplitResult split) {
        return String.join("\n", split.preTranslated());
    }

    /**
     * 反向翻译（{@code /translex say <中文>}）：把输入译成英文并自动发送到服务器聊天。
     *
     * <p>与"看别人消息译成中文"完全反向、互不干扰：用固定英文目标的 system prompt，
     * 单条直发（不经批处理 dispatcher、不查缓存、不做词库/数字处理），
     * 译文通过 {@code ClientPlayNetworkHandler.sendChatMessage} 发送。
     * 回调在 MC 主线程执行，发送线程安全。</p>
     */
    public void sayAsync(String chineseText) {
        if (chineseText == null || chineseText.isBlank()) return;
        final String input = chineseText.strip();

        requester.requestTranslation(
                ModConfig.get().apiKey,
                ModConfig.get().apiUrl,
                ModConfig.get().modelName,
                TranslationPrompts.saySystemPrompt(),
                null,                 // 无可选 user prompt
                input,
                "SAY",
                "say",
                (cacheKey, rawResult, displayId, rawBody) -> {
                    Minecraft mc = Minecraft.getInstance();
                    // 失败结果以 §c 开头：仅在本地提示，不发送到服务器
                    if (rawResult == null || rawResult.startsWith("§c")) {
                        if (mc.player != null) {
                            mc.player.sendSystemMessage(Component.literal(I18nHelper.getPrefixed("translex.say.failed")));
                        }
                        return;
                    }
                    String english = cleanSayResult(rawResult);
                    if (english.isEmpty()) return;

                    ClientPacketListener handler = mc.getConnection();
                    if (handler != null) {
                        handler.sendChat(english);
                    }
                });
    }

    /** 清理反向翻译结果：去颜色码、首尾引号与空白，截断到聊天上限 256 字符。 */
    private static String cleanSayResult(String raw) {
        String s = raw.replaceAll("§[0-9a-fk-or]", "").strip();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).strip();
        }
        if (s.length() > 256) s = s.substring(0, 256);
        return s;
    }

    public void shutdown() {
        dispatcher.shutdown();
    }
}
