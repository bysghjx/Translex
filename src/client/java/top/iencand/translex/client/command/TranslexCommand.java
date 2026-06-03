package top.iencand.translex.client.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.iencand.translex.client.cache.TemporaryTooltipCache;
import top.iencand.translex.client.translate.TranslationManager;
import top.iencand.translex.client.config.ButtonStyleManager;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.listener.ChatTranslateHandler;
import top.iencand.translex.client.util.I18nHelper;
import top.iencand.translex.client.web.WebServer;

import java.net.URI;

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
                    // /translex button (compat alias)
                    .then(literal("compat")
                            .executes(this::executeButton))
                    // /translex mode [chat|temporary|permanent]
                    .then(literal("mode")
                            .then(argument("mode", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        builder.suggest("chat");
                                        builder.suggest("temporary");
                                        builder.suggest("permanent");
                                        return builder.buildFuture();
                                    })
                                    .executes(this::executeMode)))
                    // /translex reset [itemId]
                    .then(literal("reset")
                            .then(argument("itemId", StringArgumentType.word())
                                    .executes(this::executeReset))
                            .executes(this::executeResetAll))
                    // /translex config
                    .then(literal("config")
                            .executes(this::executeConfig))
            );
        });
        LOGGER.info("Translex command registered (translate, text, reload, button, mode, reset, config).");
    }

    // ---------------------------------------------------------------
    // translate
    // ---------------------------------------------------------------

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

    // ---------------------------------------------------------------
    // text
    // ---------------------------------------------------------------

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

        translationManager.translateTextAsync(cleanedMessageText, "TX_" + System.currentTimeMillis());
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------
    // reload
    // ---------------------------------------------------------------

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

    // ---------------------------------------------------------------
    // button (compat)
    // ---------------------------------------------------------------

    private int executeButton(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String newStyle = ButtonStyleManager.toggle();
        String styleName = "COMPACT".equals(newStyle) ? "[T]" : "[翻译]";
        source.sendFeedback(Text.literal(
                I18nHelper.getPrefixed("translex.info.button_style", styleName)));
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------
    // mode [chat|temporary|permanent]
    // ---------------------------------------------------------------

    private int executeMode(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String mode = StringArgumentType.getString(context, "mode").toLowerCase();

        switch (mode) {
            case "chat", "temporary", "permanent" -> {
                ModConfig.get().outputMode = mode;
                ModConfig.forceSave();
                source.sendFeedback(Text.literal(
                        I18nHelper.getPrefixed("translex.info.output_mode", mode)));
            }
            default -> source.sendError(Text.literal(
                    I18nHelper.getPrefixed("translex.error.invalid_mode", mode)));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------
    // reset [itemId]
    // ---------------------------------------------------------------

    private int executeReset(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String itemId = StringArgumentType.getString(context, "itemId");

        translationManager.getPresetLibrary().remove(itemId);
        source.sendFeedback(Text.literal(
                I18nHelper.getPrefixed("translex.info.preset_reset", itemId)));
        return Command.SINGLE_SUCCESS;
    }

    private int executeResetAll(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();

        translationManager.getPresetLibrary().clear();
        TemporaryTooltipCache.clear();
        source.sendFeedback(Text.literal(
                I18nHelper.getPrefixed("translex.info.preset_reset_all")));
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------
    // config — 打开 Web 配置面板
    // ---------------------------------------------------------------

    private int executeConfig(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        int port = WebServer.getPort();
        String token = WebServer.getToken();
        String url = "http://127.0.0.1:" + port + "/?token=" + token;

        Util.getOperatingSystem().open(URI.create(url));
        source.sendFeedback(Text.literal(
                I18nHelper.getPrefixed("translex.info.config_opened", url)));
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private static String removeColorCodes(String text) {
        if (text == null) return null;
        Matcher matcher = COLOR_CODE_PATTERN.matcher(text);
        return matcher.replaceAll("");
    }
}
