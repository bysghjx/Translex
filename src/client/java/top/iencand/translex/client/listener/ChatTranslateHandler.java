package top.iencand.translex.client.listener;

import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.util.Formatting;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import top.iencand.translex.client.util.I18nHelper; // 确保导入 I18nHelper

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatTranslateHandler {

    private final Map<Integer, Text> recentMessages = new LinkedHashMap<Integer, Text>(100, 0.75f, true) {
        private static final int MAX_ENTRIES = 100;
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Text> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private final AtomicInteger messageCounter = new AtomicInteger(0);

    public static final String TRANSLATE_COMMAND_BASE = "translex";

    public ChatTranslateHandler() {
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, pack) -> {
            if (message == null || message.getString().trim().isEmpty()) {
                return message;
            }

            int messageId = messageCounter.getAndIncrement();
            recentMessages.put(messageId, message);

            // --- i18n 重构部分 ---

            // 1. 获取按钮文本 (translex.gui.translate_button)
            String buttonText = I18nHelper.translate("translex.gui.translate_button");

            // 2. 创建按钮，颜色建议也根据需要微调（这里维持 GREEN）
            MutableText translateButton = Text.literal(buttonText)
                    .setStyle(Style.EMPTY.withColor(Formatting.GREEN));

            ClickEvent clickEvent = new ClickEvent.RunCommand("/" + TRANSLATE_COMMAND_BASE + " " + messageId);

            // 3. 获取悬停提示文本 (translex.gui.translate_button_hover)
            // 该 Key 包含占位符 %s，自动填入 messageId
            String hoverText = I18nHelper.translate("translex.gui.translate_button_hover", messageId);
            HoverEvent hoverEvent = new HoverEvent.ShowText(Text.literal(hoverText));

            // --- 保持原有拼接逻辑 ---
            translateButton = translateButton.copy().setStyle(translateButton.getStyle()
                    .withClickEvent(clickEvent)
                    .withHoverEvent(hoverEvent));

            MutableText finalMessageToShow = Text.empty();
            finalMessageToShow.append(translateButton);
            finalMessageToShow.append(Text.literal(" "));
            finalMessageToShow.append(message);

            return finalMessageToShow;
        });
    }

    public Text getMessageById(int messageId) {
        return recentMessages.get(messageId);
    }
}