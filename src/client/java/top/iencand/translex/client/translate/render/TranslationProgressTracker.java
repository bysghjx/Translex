package top.iencand.translex.client.translate.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import top.iencand.translex.client.ext.IChatHudExt;
import top.iencand.translex.client.util.I18nHelper;

import java.util.List;

/**
 * 管理聊天 HUD 中的"加载中…"进度指示器。
 * 支持显示、更新（删除后重新添加）和移除通过隐藏标记标签识别的消息。
 */
public class TranslationProgressTracker {
    private static final String LOADING_MARKER_PREFIX = "​§8[TL_WAIT_";
    private static final String LOADING_MARKER_SUFFIX = "]​";

    /** 显示新的加载中消息 */
    public void showLoading(String displayId) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().gui == null) return;

            IChatHudExt chatHud = (IChatHudExt) Minecraft.getInstance().gui.getChat();

            MutableComponent prefix = Component.literal(I18nHelper.translate("translex.prefix.name")).withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(I18nHelper.translate("translex.prefix.separator")).withStyle(ChatFormatting.BLUE));

            MutableComponent status = Component.literal(I18nHelper.translate("translex.info.processing"))
                    .append(Component.literal("... "))
                    .append(Component.literal(LOADING_MARKER_PREFIX + displayId + LOADING_MARKER_SUFFIX).withStyle(ChatFormatting.DARK_GRAY))
                    .withStyle(ChatFormatting.YELLOW);

            chatHud.translex$forceAddMessage(prefix.append(status));
        });
    }

    /**
     * 原地更新已有的加载中消息。
     * 删除旧的标记后添加一条带有更新文本的新消息。
     */
    public void updateLoading(String displayId, String newText) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().gui == null) return;

            IChatHudExt chatHud = (IChatHudExt) Minecraft.getInstance().gui.getChat();
            List<GuiMessage> messages = chatHud.translex$getMessages();

            String targetTag = LOADING_MARKER_PREFIX + displayId + "]";

            // Remove old loading message
            messages.removeIf(line -> line.content().getString().contains(targetTag));

            // Add updated message
            MutableComponent prefix = Component.literal(I18nHelper.translate("translex.prefix.name")).withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(I18nHelper.translate("translex.prefix.separator")).withStyle(ChatFormatting.BLUE));

            MutableComponent status = Component.literal(newText)
                    .append(Component.literal(" "))
                    .append(Component.literal(LOADING_MARKER_PREFIX + displayId + LOADING_MARKER_SUFFIX).withStyle(ChatFormatting.DARK_GRAY))
                    .withStyle(ChatFormatting.YELLOW);

            chatHud.translex$forceAddMessage(prefix.append(status));
            chatHud.translex$refreshMessages();
        });
    }

    /** 通过显示 ID 移除加载中消息 */
    public void removeLoading(String displayId) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().gui == null) return;

            IChatHudExt chatHud = (IChatHudExt) Minecraft.getInstance().gui.getChat();
            List<GuiMessage> messages = chatHud.translex$getMessages();

            String targetTag = LOADING_MARKER_PREFIX + displayId + "]";

            boolean removed = messages.removeIf(line ->
                    line.content().getString().contains(targetTag));

            if (removed) {
                chatHud.translex$refreshMessages();
            }
        });
    }
}
