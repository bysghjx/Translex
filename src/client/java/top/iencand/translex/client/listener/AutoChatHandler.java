package top.iencand.translex.client.listener;

import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.util.Formatting;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import top.iencand.translex.client.config.ButtonStyleManager;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.util.I18nHelper;
import top.iencand.translex.client.web.ConsoleBroadcaster;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 混合模式处理器，根据消息长度自动选择 Message-ID 模式或纯文本模式。
 *
 * <p>策略说明：
 * <ul>
 *   <li>短消息（去除颜色码后 &lt; 80 字符）→ 纯文本模式，直接发送完整文本</li>
 *   <li>长消息（≥ 80 字符）→ Message-ID 模式，避免命令长度超限</li>
 * </ul>
 */
public class AutoChatHandler implements MessageLookup {

    /** 切换为 ID 模式的阈值（去除颜色码后的字符数） */
    private static final int AUTO_THRESHOLD_CHARS = 80;

    // 命令基址常量
    private static final String TRANSLATE_COMMAND_BASE = "translex translate";
    private static final String TEXT_COMMAND_BASE = "translex text";

    /** LRU 缓存：最近收到的消息，按消息 ID 索引，最多 100 条 */
    private final Map<Integer, Text> recentMessages = new LinkedHashMap<Integer, Text>(100, 0.75f, true) {
        private static final int MAX_ENTRIES = 100;
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Text> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private final AtomicInteger messageCounter = new AtomicInteger(0);

    public AutoChatHandler() {
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            // 跳过 ActionBar 消息
            if (overlay) return message;
            if (message == null || message.getString().trim().isEmpty()) {
                return message;
            }

            // 如果翻译按钮被禁用，直接返回原始消息
            if (!ButtonStyleManager.isButtonEnabled()) {
                return message;
            }

            String fullText = message.getString();

            // 去除颜色码后计算长度，决定使用 ID 模式还是文本模式
            String stripped = fullText.replaceAll("§[0-9a-fk-or]", "").trim();
            boolean useIdMode = stripped.length() >= AUTO_THRESHOLD_CHARS;
            if (ModConfig.get().debug) {
                ConsoleBroadcaster.broadcast("DEBUG",
                        "Auto mode: " + stripped.length() + " chars → " + (useIdMode ? "ID" : "text")
                        + " (threshold " + AUTO_THRESHOLD_CHARS + ")");
            }

            // Always assign an ID so getMessageById works for both modes
            int messageId = messageCounter.getAndIncrement();
            recentMessages.put(messageId, message);

            // Build button
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

            ClickEvent clickEvent;
            String hoverKey;
            if (useIdMode) {
                clickEvent = new ClickEvent.RunCommand("/" + TRANSLATE_COMMAND_BASE + " " + messageId);
                hoverKey = "translex.gui.translate_button_hover";
            } else {
                // Escape double quotes in message text to avoid breaking the command
                String escaped = fullText.replace("\\", "\\\\").replace("\"", "\\\"");
                clickEvent = new ClickEvent.RunCommand(
                        "/" + TEXT_COMMAND_BASE + " \"" + escaped + "\"");
                hoverKey = "translex.gui.translate_button_hover_legacy";
            }

            String hoverText = I18nHelper.translate(hoverKey, messageId);
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

    @Override
    public Text getMessageById(int messageId) {
        return recentMessages.get(messageId);
    }
}
