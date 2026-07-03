package top.iencand.translex.client.translate;

import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import top.iencand.translex.client.translate.cache.TranslationCacheManager;
import top.iencand.translex.client.translate.model.ItemPresetLibrary;
import top.iencand.translex.client.translate.pipeline.ChatTranslationPipeline;
import top.iencand.translex.client.translate.pipeline.ItemTranslationPipeline;

import java.io.File;
import java.util.List;

/**
 * 翻译入口门面。
 *
 * <p>已彻底分离为两条独立管线：
 * <ul>
 *   <li>{@link ChatTranslationPipeline} —— 聊天消息 / {@code /translex text}，不走持久缓存，
 *       独立 dispatcher / prompt（translationPrompt）/ 进度行（TL_CHAT）。</li>
 *   <li>{@link ItemTranslationPipeline} —— 物品 lore 逐行模板翻译，走行级 shard 缓存 + 输出模式存储，
 *       独立 dispatcher / prompt（itemTranslationPrompt）/ 进度行（TL_ITEM）。</li>
 * </ul>
 * 两条管线共享一个无状态的 {@link TranslationRequester}（复用 OkHttp 连接池）。
 * 本类仅做转发，保持对外契约不变（命令、监听器、Mixin 调用方零改动）。</p>
 */
public class TranslationManager {

    private final TranslationRequester sharedRequester = new TranslationRequester();
    private final ChatTranslationPipeline chatPipeline = new ChatTranslationPipeline(sharedRequester);
    private final ItemTranslationPipeline itemPipeline = new ItemTranslationPipeline(sharedRequester);

    public TranslationManager() {
    }

    public void initializePersistence(File file) {
        itemPipeline.initializePersistence(file);
    }

    // -------- 聊天管线转发 --------

    public void translateChatMessageAsync(String text, String displayId) {
        chatPipeline.translateChatMessageAsync(text, displayId);
    }

    public void translateTextAsync(String text, String displayId) {
        chatPipeline.translateTextAsync(text, displayId);
    }

    public void translateAsync(int id, String text, String playerPrefix) {
        chatPipeline.translateChatMessageAsync(text, String.valueOf(id));
    }

    /** 反向翻译并自动发送到聊天（{@code /translex say <中文>}）。 */
    public void sayAsync(String text) {
        chatPipeline.sayAsync(text);
    }

    // -------- 物品管线转发 --------

    public void translateItemLoreTemplates(List<Component> originalLines, String itemId,
                                            String itemDisplayName, ItemStack stack, boolean force) {
        itemPipeline.translateItemLoreTemplates(originalLines, itemId, itemDisplayName, stack, force);
    }

    // -------- 共享组件访问（命令 / ClientStateManager 依赖） --------

    public ItemPresetLibrary getPresetLibrary() { return itemPipeline.getPresetLibrary(); }
    public TranslationCacheManager getCacheManager() { return itemPipeline.getCacheManager(); }

    public void shutdown() {
        chatPipeline.shutdown();
        itemPipeline.shutdown();
    }

    /** 使物品翻译会话失效：进行中的异步回调将被丢弃，不写入新会话的缓存。 */
    public void invalidateItemSession() {
        itemPipeline.invalidateSession();
    }
}
