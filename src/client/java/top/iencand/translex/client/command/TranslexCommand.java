package top.iencand.translex.client.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.iencand.translex.client.Translate.TranslationManager;
import top.iencand.translex.client.config.ButtonStyleManager;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.listener.ChatTranslateHandler;
import top.iencand.translex.client.util.I18nHelper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class TranslexCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("TranslexCommand");
    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("§[0-9a-fk-or]");

    private final TranslationManager translationManager;
    private final ChatTranslateHandler chatTranslateHandler;

    public TranslexCommand(TranslationManager translationManager, ChatTranslateHandler chatTranslateHandler) {
        this.translationManager = translationManager;
        this.chatTranslateHandler = chatTranslateHandler;
    }

    public void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, environment) -> {
            dispatcher.register(literal("translex")
                    // /translex translate <message_id>
                    .then(literal("translate")
                            .then(argument("message_id", IntegerArgumentType.integer())
                                    .executes(this::executeTranslate)))
                    // /translex text <raw_message>
                    .then(literal("text")
                            .then(argument("message", StringArgumentType.greedyString())
                                    .executes(this::executeText)))
                    // /translex reload
                    .then(literal("reload")
                            .executes(this::executeReload))
                    // /translex button
                    .then(literal("compat")
                            .executes(this::executeButton))
            );
        });
        LOGGER.info("Translex command registered (translate, text, reload, button).");
    }

    private int executeTranslate(CommandContext<FabricClientCommandSource> context) {
        int messageId = IntegerArgumentType.getInteger(context, "message_id");
        MinecraftClient client = MinecraftClient.getInstance();

        if (chatTranslateHandler == null) {
            client.execute(() -> context.getSource().sendError(
                    Text.literal(I18nHelper.getPrefixed("translex.error.mode_not_available"))));
            return 0;
        }

        Text originalMessage = chatTranslateHandler.getMessageById(messageId);

        if (originalMessage == null) {
            client.execute(() -> {
                String errorMsg = I18nHelper.translate("translex.error.not_found", messageId);
                context.getSource().sendError(
                        Text.literal(I18nHelper.translate("translex.prefix") + errorMsg));
            });
            return 0;
        }

        String messageTextToTranslate = originalMessage.getString();
        String playerNameWithSeparator = "";

        int firstColonSpace = messageTextToTranslate.indexOf(": ");
        if (firstColonSpace != -1) {
            playerNameWithSeparator = messageTextToTranslate.substring(0, firstColonSpace + 2);
            messageTextToTranslate = messageTextToTranslate.substring(firstColonSpace + 2);
        } else {
            messageTextToTranslate = originalMessage.getString();
        }

        translationManager.translateAsync(messageId, messageTextToTranslate, playerNameWithSeparator);
        return Command.SINGLE_SUCCESS;
    }

    private int executeText(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String fullMessageText;

        try {
            fullMessageText = StringArgumentType.getString(context, "message");
        } catch (IllegalArgumentException e) {
            source.sendError(Text.literal(I18nHelper.getPrefixed("translex.error.usage_legacy")));
            return 0;
        }

        String cleanedMessageText = removeColorCodes(fullMessageText).trim();

        if (cleanedMessageText.isEmpty()) {
            source.sendError(Text.literal(I18nHelper.getPrefixed("translex.error.content_empty")));
            return 0;
        }

        String contextString = cleanedMessageText.substring(0, Math.min(cleanedMessageText.length(), 30)) + "...";
        translationManager.translateTextAsync(cleanedMessageText, contextString);

        return Command.SINGLE_SUCCESS;
    }

    private int executeReload(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();

        try {
            ModConfig.reload();
            ModConfig config = ModConfig.get();

            source.sendFeedback(Text.literal(I18nHelper.getPrefixed("translex.info.config_reloaded")));

            String modeKey = config.enableMessageIdSystem
                    ? "translex.info.mode_message_id"
                    : "translex.info.mode_command_text";
            source.sendFeedback(Text.literal(I18nHelper.getPrefixed(modeKey)));

        } catch (Exception e) {
            source.sendError(Text.literal(I18nHelper.getPrefixed("translex.error.config_load")));
            LOGGER.error("Error reloading config via command", e);
            return 0;
        }

        return Command.SINGLE_SUCCESS;
    }

    private int executeButton(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String newStyle = ButtonStyleManager.toggle();
        String styleName = "COMPACT".equals(newStyle) ? "[T]" : "[翻译]";
        source.sendFeedback(Text.literal(
                I18nHelper.getPrefixed("translex.info.button_style", styleName)));
        return Command.SINGLE_SUCCESS;
    }

    private static String removeColorCodes(String text) {
        if (text == null) return null;
        Matcher matcher = COLOR_CODE_PATTERN.matcher(text);
        return matcher.replaceAll("");
    }
}
