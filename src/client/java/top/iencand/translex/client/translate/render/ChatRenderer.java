package top.iencand.translex.client.translate.render;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import top.iencand.translex.client.util.I18nHelper;

/**
 * 专门负责在 Minecraft 聊天栏中渲染翻译结果和错误信息。
 * 移除了点击复制功能，仅保留悬停信息提示。
 */
public class ChatRenderer {

    /**
     * 渲染正常的翻译结果
     * @param originalText 翻译前的原句（用于悬停显示）
     * @param translatedText 翻译后的结果
     * @param displayId 任务唯一标识 ID
     */
    public void renderResult(String originalText, String translatedText, String displayId) {
        render(originalText, translatedText, displayId, false);
    }

    /**
     * 渲染错误信息
     * @param errorDetail 错误描述内容
     * @param displayId 任务唯一标识 ID
     */
    public void renderError(String errorDetail, String displayId) {
        render(null, errorDetail, displayId, true);
    }

    /**
     * 核心渲染逻辑
     */
    private void render(String original, String content, String id, boolean isError) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().gui == null) return;

            // 1. 构造前缀 [Translex » ]
            MutableComponent prefix = Component.literal(I18nHelper.translate("translex.prefix.name")).withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(I18nHelper.translate("translex.prefix.separator")).withStyle(ChatFormatting.BLUE));

            // 2. 构造悬停内容 (任务 ID + 原句预览)
            String hoverKey = isError ? "translex.error.hover" : "translex.hover.metadata";
            MutableComponent hoverText = Component.literal(I18nHelper.translate(hoverKey, id));

            if (!isError && original != null) {
                hoverText.append(Component.literal("\n\n").append(Component.literal(original).withStyle(ChatFormatting.GRAY)));
            }

            // 仅保留悬停事件，移除 ClickEvent
            prefix.setStyle(prefix.getStyle()
                    .withHoverEvent(new HoverEvent.ShowText(hoverText))
            );

            // 3. 构造正文 (错误为红色，正常为白色)
            MutableComponent body = Component.literal(content).withStyle(isError ? ChatFormatting.RED : ChatFormatting.WHITE);

            // 4. 发送到聊天框
            Minecraft.getInstance().gui.getChat().addClientSystemMessage(prefix.append(body));
        });
    }
}