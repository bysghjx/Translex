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

@Environment(EnvType.CLIENT)
public class LegacyChatHandler {

    public static final String TRANSLATE_COMMAND_BASE = "translex text";

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

            // 1. Get full message text
            String fullMessageText = message.getString();

            // 2. Create button based on style
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

            // 3. Hover event
            String hoverText = I18nHelper.translate("translex.gui.translate_button_hover_legacy");
            HoverEvent hoverEvent = new HoverEvent.ShowText(Text.literal(hoverText));

            // 4. Click event
            ClickEvent clickEvent = new ClickEvent.RunCommand(
                    "/" + TRANSLATE_COMMAND_BASE + " " + fullMessageText
            );

            // 5. Apply style
            translateButton.setStyle(translateButton.getStyle()
                    .withHoverEvent(hoverEvent)
                    .withClickEvent(clickEvent));

            // 6. Combine message
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

        System.out.println("[Translex] LegacyChatHandler registered for MODIFY_GAME event.");
    }
}
