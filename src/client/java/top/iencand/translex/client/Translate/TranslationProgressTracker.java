package top.iencand.translex.client.Translate;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import top.iencand.translex.client.ext.IChatHudExt;
import top.iencand.translex.client.util.I18nHelper;

import java.util.List;

/**
 * 负责在聊天栏管理“正在处理...”的进度提示。
 * 使用你最新的 IChatHudExt 接口方法进行操作。
 */
public class TranslationProgressTracker {
    // 隐藏在消息末尾的唯一标识符，用于精准删除
    private static final String LOADING_MARKER_PREFIX = "\u200B§8[TL_WAIT_";
    private static final String LOADING_MARKER_SUFFIX = "]\u200B";

    public void showLoading(String displayId) {
        MinecraftClient.getInstance().execute(() -> {
            if (MinecraftClient.getInstance().inGameHud == null) return;

            // 获取扩展接口
            IChatHudExt chatHud = (IChatHudExt) MinecraftClient.getInstance().inGameHud.getChatHud();

            MutableText prefix = Text.literal(I18nHelper.translate("translex.prefix.name")).formatted(Formatting.GREEN)
                    .append(Text.literal(I18nHelper.translate("translex.prefix.separator")).formatted(Formatting.BLUE));

            MutableText status = Text.literal(I18nHelper.translate("translex.info.processing"))
                    .append(Text.literal("... "))
                    // 插入隐藏标识符，方便后续 removeLoading 识别
                    .append(Text.literal(LOADING_MARKER_PREFIX + displayId + LOADING_MARKER_SUFFIX).formatted(Formatting.DARK_GRAY))
                    .formatted(Formatting.YELLOW);

            // 使用你 Mixin 中的 forceAddMessage，它会设置 isInternalRedirect = true
            // 从而绕过 messageManager 的拦截逻辑，确保进度条一定能显示
            chatHud.translex$forceAddMessage(prefix.append(status));
        });
    }

    public void removeLoading(String displayId) {
        MinecraftClient.getInstance().execute(() -> {
            if (MinecraftClient.getInstance().inGameHud == null) return;

            IChatHudExt chatHud = (IChatHudExt) MinecraftClient.getInstance().inGameHud.getChatHud();
            List<ChatHudLine> messages = chatHud.translex$getMessages();

            String targetTag = LOADING_MARKER_PREFIX + displayId + "]";

            // 遍历底层消息列表并移除包含标识符的消息
            boolean removed = messages.removeIf(line ->
                    line.content().getString().contains(targetTag)
            );

            // 如果成功移除，调用你 Mixin 中的 refresh 方法重绘 UI
            if (removed) {
                chatHud.translex$refreshMessages();
            }
        });
    }
}