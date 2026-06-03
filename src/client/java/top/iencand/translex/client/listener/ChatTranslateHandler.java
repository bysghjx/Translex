package top.iencand.translex.client.listener;

import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.util.Formatting;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import top.iencand.translex.client.config.ButtonStyleManager;
import top.iencand.translex.client.util.I18nHelper;

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

    public static final String TRANSLATE_COMMAND_BASE = "translex translate";

    public ChatTranslateHandler() {
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            // 跳过 action bar（overlay = true），不添加 [翻译] 按钮
            if (overlay) return message;
            if (message == null || message.getString().trim().isEmpty()) {
                return message;
            }

            int messageId = messageCounter.getAndIncrement();
            recentMessages.put(messageId, message);

            // 1. 根据按钮样式获取文本
            String buttonText;
            Formatting buttonColor;
            if (ButtonStyleManager.isCompact()) {
                buttonText = I18nHelper.translate("translex.gui.translate_button_compact");
                buttonColor = Formatting.GREEN;
            } else {
                buttonText = I18nHelper.translate("translex.gui.translate_button");
                buttonColor = Formatting.GREEN;
            }

            // 2. 创建按钮
            MutableText translateButton = Text.literal(buttonText)
                    .setStyle(Style.EMPTY.withColor(buttonColor));

            ClickEvent clickEvent = new ClickEvent.RunCommand("/" + TRANSLATE_COMMAND_BASE + " " + messageId);

            // 3. 获取悬停提示文本
            String hoverText = I18nHelper.translate("translex.gui.translate_button_hover", messageId);
            HoverEvent hoverEvent = new HoverEvent.ShowText(Text.literal(hoverText));

            translateButton = translateButton.copy().setStyle(translateButton.getStyle()
                    .withClickEvent(clickEvent)
                    .withHoverEvent(hoverEvent));

            MutableText finalMessageToShow = Text.empty();
            if (ButtonStyleManager.isCompact()) {
                finalMessageToShow.append(message);
                finalMessageToShow.append(Text.literal(" "));
                finalMessageToShow.append(translateButton);
            } else {
                finalMessageToShow.append(translateButton);
                finalMessageToShow.append(Text.literal(" "));
                finalMessageToShow.append(message);
            }

            return finalMessageToShow;
        });
    }

    public Text getMessageById(int messageId) {
        return recentMessages.get(messageId);
    }
}