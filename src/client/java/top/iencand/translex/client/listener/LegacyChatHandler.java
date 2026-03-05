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
import top.iencand.translex.client.util.I18nHelper; // 确保导入 I18nHelper

@Environment(EnvType.CLIENT)
public class LegacyChatHandler {

    // 注意：这里的命令基础名是 translate，与 ChatTranslateHandler 的 translex 不同
    public static final String TRANSLATE_COMMAND_BASE = "translate";

    public LegacyChatHandler() {
        System.out.println("[Translex] LegacyChatHandler created.");
    }

    public void registerEvents() {
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            if (overlay) {
                return message;
            }

            if (message == null || message.getString().trim().isEmpty()) {
                return message;
            }

            // --- 核心逻辑重构 ---

            // 1. 获取消息的纯文本内容 (保持原有逻辑，用于命令参数)
            String fullMessageText = message.getString();

            // 2. 创建“翻译按钮” (使用 I18n 文本 + 蓝色样式)
            String buttonText = I18nHelper.translate("translex.gui.translate_button");
            MutableText translateButton = Text.literal(buttonText)
                    .setStyle(Style.EMPTY.withColor(Formatting.GREEN)); // 改为蓝色

            // 3. 创建 HoverEvent (使用 I18n 文本)
            // 既然这个类不使用 ID，我们可以使用一个通用的提示 Key
            // 如果 JSON 里没有专门给 Legacy 用的，我们可以复用之前的 Key 但不传参数，或者定义一个新的
            String hoverText = I18nHelper.translate("translex.gui.translate_button_hover_legacy");
            HoverEvent hoverEvent = new HoverEvent.ShowText(Text.literal(hoverText));

            // 4. 创建 ClickEvent (保持原有逻辑)
            ClickEvent clickEvent = new ClickEvent.RunCommand(
                    "/" + TRANSLATE_COMMAND_BASE + " " + fullMessageText
            );

            // 5. 应用样式
            translateButton.setStyle(translateButton.getStyle()
                    .withHoverEvent(hoverEvent)
                    .withClickEvent(clickEvent));

            // 6. 组合消息
            MutableText newMessageWithButton = Text.empty();
            newMessageWithButton.append(translateButton);
            newMessageWithButton.append(Text.literal(" "));
            newMessageWithButton.append(message);

            return newMessageWithButton;
        });

        System.out.println("[Translex] LegacyChatHandler registered for MODIFY_GAME event.");
    }
}