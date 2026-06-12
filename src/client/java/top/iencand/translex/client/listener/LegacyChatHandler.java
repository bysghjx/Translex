package top.iencand.translex.client.listener;

import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.util.Formatting;
import top.iencand.translex.client.config.ButtonStyleManager;
import top.iencand.translex.client.util.I18nHelper;
import top.iencand.translex.client.web.ConsoleBroadcaster;

@Environment(EnvType.CLIENT)
public class LegacyChatHandler {

    public static final String TRANSLATE_COMMAND_BASE = "translex text";

    public LegacyChatHandler() {
        ConsoleBroadcaster.broadcast("DEBUG", "旧版聊天处理器已创建");
    }

    public void registerEvents() {
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            if (overlay) {
                return message;
            }

            if (message == null || message.getString().trim().isEmpty()) {
                return message;
            }

            // 如果翻译按钮被禁用，直接返回原始消息
            if (!ButtonStyleManager.isButtonEnabled()) {
                return message;
            }

            // 1. 获取完整消息文本
            String fullMessageText = message.getString();

            // 2. 根据样式创建按钮
            String buttonText;
            Formatting buttonColor;
            if (ButtonStyleManager.isCompact()) {
                buttonText = I18nHelper.translate("translex.gui.translate_button_compact");
                buttonColor = Formatting.GREEN;
            } else {
                buttonText = I18nHelper.translate("translex.gui.translate_button");
                buttonColor = Formatting.GREEN;
            }
            MutableText translateButton = Text.literal(buttonText)
                    .setStyle(Style.EMPTY.withColor(buttonColor));

            // 3. 设置悬停事件
            String hoverText = I18nHelper.translate("translex.gui.translate_button_hover_legacy");
            HoverEvent hoverEvent = new HoverEvent.ShowText(Text.literal(hoverText));

            // 4. 设置点击事件（直接发送完整消息文本到命令）
            ClickEvent clickEvent = new ClickEvent.RunCommand(
                    "/" + TRANSLATE_COMMAND_BASE + " " + fullMessageText
            );

            // 5. 应用样式
            translateButton.setStyle(translateButton.getStyle()
                    .withHoverEvent(hoverEvent)
                    .withClickEvent(clickEvent));

            // 6. 组合消息
            MutableText newMessageWithButton = Text.empty();
            if (ButtonStyleManager.isCompact()) {
                newMessageWithButton.append(message);
                newMessageWithButton.append(Text.literal(" "));
                newMessageWithButton.append(translateButton);
            } else {
                newMessageWithButton.append(translateButton);
                newMessageWithButton.append(Text.literal(" "));
                newMessageWithButton.append(message);
            }

            return newMessageWithButton;
        });

        ConsoleBroadcaster.broadcast("DEBUG", "旧版聊天处理器已注册 MODIFY_GAME 事件");
    }
}
