package top.iencand.translex.client.listener;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import top.iencand.translex.client.config.ButtonStyleManager;
import top.iencand.translex.client.util.I18nHelper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于消息 ID 的聊天翻译处理器。
 * 为每条收到的聊天消息分配唯一 ID，添加可点击的 [翻译] 按钮，
 * 按钮通过 /translex translate &lt;id&gt; 命令触发翻译。
 *
 * <p>适用于 message_id 模式，每条消息都通过 ID 追踪。</p>
 */
public class ChatTranslateHandler implements MessageLookup {

    /** LRU 缓存：最近收到的消息，最多 100 条 */
    private final Map<Integer, Component> recentMessages = new LinkedHashMap<Integer, Component>(100, 0.75f, true) {
        private static final int MAX_ENTRIES = 100;
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Component> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private final AtomicInteger messageCounter = new AtomicInteger(0);

    /** 翻译命令基址常量 */
    public static final String TRANSLATE_COMMAND_BASE = "translex translate";

    public ChatTranslateHandler() {
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            // 跳过 action bar（overlay = true），不添加 [翻译] 按钮
            if (overlay) return message;
            if (message == null || message.getString().trim().isEmpty()) {
                return message;
            }

            // 如果翻译按钮被禁用，直接返回原始消息
            if (!ButtonStyleManager.isButtonEnabled()) {
                return message;
            }

            int messageId = messageCounter.getAndIncrement();
            recentMessages.put(messageId, message);

            // 1. 根据按钮样式获取文本
            String buttonText;
            ChatFormatting buttonColor;
            if (ButtonStyleManager.isCompact()) {
                buttonText = I18nHelper.translate("translex.gui.translate_button_compact");
                buttonColor = ChatFormatting.GREEN;
            } else {
                buttonText = I18nHelper.translate("translex.gui.translate_button");
                buttonColor = ChatFormatting.GREEN;
            }

            // 2. 创建按钮
            MutableComponent translateButton = Component.literal(buttonText)
                    .setStyle(Style.EMPTY.withColor(buttonColor));

            ClickEvent clickEvent = new ClickEvent.RunCommand("/" + TRANSLATE_COMMAND_BASE + " " + messageId);

            // 3. 获取悬停提示文本
            String hoverText = I18nHelper.translate("translex.gui.translate_button_hover", messageId);
            HoverEvent hoverEvent = new HoverEvent.ShowText(Component.literal(hoverText));

            translateButton = translateButton.copy().setStyle(translateButton.getStyle()
                    .withClickEvent(clickEvent)
                    .withHoverEvent(hoverEvent));

            MutableComponent finalMessageToShow = Component.empty();
            if (ButtonStyleManager.isCompact()) {
                finalMessageToShow.append(message);
                finalMessageToShow.append(Component.literal(" "));
                finalMessageToShow.append(translateButton);
            } else {
                finalMessageToShow.append(translateButton);
                finalMessageToShow.append(Component.literal(" "));
                finalMessageToShow.append(message);
            }

            return finalMessageToShow;
        });
    }

    @Override
    public Component getMessageById(int messageId) {
        return recentMessages.get(messageId);
    }
}