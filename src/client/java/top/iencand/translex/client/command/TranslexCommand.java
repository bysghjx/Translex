package top.iencand.translex.client.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.awt.Desktop; import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.iencand.translex.client.translate.cache.TemporaryTooltipCache;
import top.iencand.translex.client.translate.TranslationManager;
import top.iencand.translex.client.config.ButtonStyleManager;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.listener.MessageLookup;
import top.iencand.translex.client.util.I18nHelper;
import top.iencand.translex.client.web.WebServer;

import java.net.URI;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * /translex 命令系统的注册与执行。
 * 提供 translate、text、reload、compat（快捷切换按钮样式）、mode、reset、config 等子命令。
 */
public class TranslexCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("TranslexCommand");
    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("§[0-9a-fk-or]");

    private final TranslationManager translationManager;
    private final MessageLookup messageLookup;

    public TranslexCommand(TranslationManager translationManager, MessageLookup messageLookup) {
        this.translationManager = translationManager;
        this.messageLookup = messageLookup;
    }

    public void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, environment) -> {
            dispatcher.register(literal("translex")
                    .executes(this::executeHelp)
                    // /translex translate <message_id>
                    .then(literal("translate")
                            .then(argument("message_id", IntegerArgumentType.integer())
                                    .executes(this::executeTranslate)))
                    // /translex text <raw_message>
                    .then(literal("text")
                            .then(argument("message", StringArgumentType.greedyString())
                                    .executes(this::executeText)))
                    // /translex say <chinese_message> — 中译英并自动发送到聊天
                    .then(literal("say")
                            .then(argument("message", StringArgumentType.greedyString())
                                    .executes(this::executeSay)))
                    // /translex reload
                    .then(literal("reload")
                            .executes(this::executeReload))
                    // /translex button (compat alias)
                    .then(literal("compat")
                            .executes(this::executeButton))
                    // /translex button
                    .then(literal("button")
                            .executes(this::executeToggleButton))
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
                    // /translex debug
                    .then(literal("debug")
                            .executes(this::executeDebug))
                    // /translex config
                    .then(literal("config")
                            .executes(this::executeConfig))
            );
        });
        LOGGER.info("Translex command registered (translate, text, say, reload, compat, button, mode, reset, config).");
    }

    // ===============================================================
    // 帮助（/translex 无参数）
    // ===============================================================

    private int executeHelp(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Component.literal("§a━━━ Translex Help ━━━"));
        source.sendFeedback(Component.literal("§e/translex §7— Show this help"));
        source.sendFeedback(Component.literal("§e/translex translate <id> §7— Translate message by ID"));
        source.sendFeedback(Component.literal("§e/translex text <message> §7— Translate arbitrary text"));
        source.sendFeedback(Component.literal("§e/translex say <message> §7— Translate to English & send to chat"));
        source.sendFeedback(Component.literal("§e/translex reload §7— Reload configuration from disk"));
        source.sendFeedback(Component.literal("§e/translex compat §7— Toggle button style [翻译]/[T]"));
        source.sendFeedback(Component.literal("§e/translex button §7— Toggle translation button on/off"));
        source.sendFeedback(Component.literal("§e/translex mode <chat|temporary|permanent> §7— Set output mode"));
        source.sendFeedback(Component.literal("§e/translex reset [itemId] §7— Clear preset library entries"));
        source.sendFeedback(Component.literal("§e/translex debug §7— Toggle debug mode (no API key needed)"));
        source.sendFeedback(Component.literal("§e/translex config §7— Open web configuration panel"));
        return Command.SINGLE_SUCCESS;
    }

    // ===============================================================
    // 翻译 by ID（/translex translate &lt;id&gt;）
    // ===============================================================

    /**
     * 通过消息 ID 翻译聊天消息。
     * 从 messageLookup 中获取原始文本，分离玩家名称前缀后提交翻译。
     */
    private int executeTranslate(CommandContext<FabricClientCommandSource> context) {
        int messageId = IntegerArgumentType.getInteger(context, "message_id");
        Minecraft client = Minecraft.getInstance();

        if (messageLookup == null) {
            client.execute(() -> context.getSource().sendError(
                    Component.literal(I18nHelper.getPrefixed("translex.error.mode_not_available"))));
            return 0;
        }

        Component originalMessage = messageLookup.getMessageById(messageId);
        if (originalMessage == null) {
            client.execute(() -> {
                String errorMsg = I18nHelper.translate("translex.error.not_found", messageId);
                context.getSource().sendError(
                        Component.literal(I18nHelper.translate("translex.prefix") + errorMsg));
            });
            return 0;
        }

        String messageTextToTranslate = originalMessage.getString();
        String playerNameWithSeparator = "";

        // 尝试分离"玩家名: "前缀，保留在最终输出中
        int firstColonSpace = messageTextToTranslate.indexOf(": ");
        if (firstColonSpace != -1) {
            playerNameWithSeparator = messageTextToTranslate.substring(0, firstColonSpace + 2);
            messageTextToTranslate = messageTextToTranslate.substring(firstColonSpace + 2);
        } else {
            messageTextToTranslate = originalMessage.getString();
        }

        // 检查 API Key 是否已配置
        if (isApiKeyMissing()) {
            return showApiKeyHint(context);
        }

        translationManager.translateAsync(messageId, messageTextToTranslate, playerNameWithSeparator);
        return Command.SINGLE_SUCCESS;
    }

    // ===============================================================
    // 纯文本翻译（/translex text &lt;message&gt;）
    // ===============================================================

    /**
     * 翻译用户输入的原始文本（不依赖消息 ID 追踪）。
     * 自动去除颜色码后提交翻译。
     */
    private int executeText(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String fullMessageText;

        try {
            fullMessageText = StringArgumentType.getString(context, "message");
        } catch (IllegalArgumentException e) {
            source.sendError(Component.literal(I18nHelper.getPrefixed("translex.error.usage_legacy")));
            return 0;
        }

        // 去掉一对包裹整段的引号（AutoChatHandler 短消息按钮会把文本包成 "..." 传入；
        // Brigadier 的 greedyString 不会自动去引号，否则引号会被当作内容一起翻译）。
        fullMessageText = stripWrappingQuotes(fullMessageText);

        String cleanedMessageText = removeColorCodes(fullMessageText).trim();

        if (cleanedMessageText.isEmpty()) {
            source.sendError(Component.literal(I18nHelper.getPrefixed("translex.error.content_empty")));
            return 0;
        }

        if (isApiKeyMissing()) {
            return showApiKeyHint(context);
        }

        translationManager.translateTextAsync(cleanedMessageText, "TX_" + System.currentTimeMillis());
        return Command.SINGLE_SUCCESS;
    }

    // ===============================================================
    // 反向翻译并发送（/translex say &lt;message&gt;）
    // ===============================================================

    /**
     * 把用户输入译成英文并自动发送到服务器聊天（与翻译别人消息为中文反向）。
     */
    private int executeSay(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String message;

        try {
            message = StringArgumentType.getString(context, "message");
        } catch (IllegalArgumentException e) {
            source.sendError(Component.literal(I18nHelper.getPrefixed("translex.error.usage_legacy")));
            return 0;
        }

        message = stripWrappingQuotes(message).trim();
        if (message.isEmpty()) {
            source.sendError(Component.literal(I18nHelper.getPrefixed("translex.error.content_empty")));
            return 0;
        }

        if (isApiKeyMissing()) {
            return showApiKeyHint(context);
        }

        source.sendFeedback(Component.literal(I18nHelper.getPrefixed("translex.say.translating")));
        translationManager.sayAsync(message);
        return Command.SINGLE_SUCCESS;
    }

    // ===============================================================
    // 重载配置（/translex reload）
    // ===============================================================

    private int executeReload(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();

        try {
            ModConfig.reload();
            ModConfig config = ModConfig.get();

            source.sendFeedback(Component.literal(I18nHelper.getPrefixed("translex.info.config_reloaded")));

            // 显示当前翻译模式
            String modeName = switch (config.translationMode) {
                case "message_id" -> "Message ID";
                case "text" -> "Component";
                default -> "Auto";
            };
            source.sendFeedback(Component.literal(I18nHelper.getPrefixed(
                    "translex.info.translation_mode", modeName)));

        } catch (Exception e) {
            source.sendError(Component.literal(I18nHelper.getPrefixed("translex.error.config_load")));
            LOGGER.error("Error reloading config via command", e);
            return 0;
        }

        return Command.SINGLE_SUCCESS;
    }

    // ===============================================================
    // 按钮切换（/translex compat）
    // ===============================================================

    private int executeButton(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String newStyle = ButtonStyleManager.toggle();
        String styleName = "COMPACT".equals(newStyle) ? "[T]" : "[翻译]";
        source.sendFeedback(Component.literal(
                I18nHelper.getPrefixed("translex.info.button_style", styleName)));
        return Command.SINGLE_SUCCESS;
    }

    // ===============================================================
    // 按钮启用/禁用切换（/translex button）
    // ===============================================================

    private int executeToggleButton(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        boolean enabled = ButtonStyleManager.toggleButtonEnabled();
        String status = enabled
                ? I18nHelper.translate("translex.info.button_enabled")
                : I18nHelper.translate("translex.info.button_disabled");
        source.sendFeedback(Component.literal(
                I18nHelper.getPrefixed("translex.info.button_status", status)));
        return Command.SINGLE_SUCCESS;
    }

    // ===============================================================
    // 输出模式设置（/translex mode &lt;chat|temporary|permanent&gt;）
    // ===============================================================

    private int executeMode(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String mode = StringArgumentType.getString(context, "mode").toLowerCase();

        switch (mode) {
            case "chat", "temporary", "permanent" -> {
                ModConfig.get().outputMode = mode;
                ModConfig.forceSave();
                source.sendFeedback(Component.literal(
                        I18nHelper.getPrefixed("translex.info.output_mode", mode)));
            }
            default -> source.sendError(Component.literal(
                    I18nHelper.getPrefixed("translex.error.invalid_mode", mode)));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ===============================================================
    // 重置预设库（/translex reset [itemId]）
    // ===============================================================

    private int executeReset(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String itemId = StringArgumentType.getString(context, "itemId");

        // 移除该 itemId 下的所有 lore 变体（组合键以 itemId# 开头）
        int removed = translationManager.getPresetLibrary().removeByItemId(itemId);
        source.sendFeedback(Component.literal(
                I18nHelper.getPrefixed("translex.info.preset_reset", itemId)));
        return Command.SINGLE_SUCCESS;
    }

    private int executeResetAll(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();

        translationManager.getPresetLibrary().clear();
        TemporaryTooltipCache.clear();
        source.sendFeedback(Component.literal(
                I18nHelper.getPrefixed("translex.info.preset_reset_all")));
        return Command.SINGLE_SUCCESS;
    }

    // ===============================================================
    // 调试模式切换（/translex debug）
    // ===============================================================

    private int executeDebug(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        ModConfig config = ModConfig.get();
        config.debug = !config.debug;
        ModConfig.forceSave();

        if (config.debug) {
            source.sendFeedback(Component.literal(
                    I18nHelper.getPrefixed("translex.info.debug_enabled")));
            source.sendFeedback(Component.literal(
                    "  §7— " + I18nHelper.translate("translex.info.debug_hint")));
        } else {
            source.sendFeedback(Component.literal(
                    I18nHelper.getPrefixed("translex.info.debug_disabled")));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ===============================================================
    // 打开 Web 配置面板（/translex config）
    // ===============================================================

    private int executeConfig(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        int port = WebServer.getPort();
        String token = WebServer.getToken();
        String url = "http://127.0.0.1:" + port + "/?token=" + token;

        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            LOGGER.warn("Failed to open config URL in browser: {}", e.getMessage());
        }
        source.sendFeedback(Component.literal(
                I18nHelper.getPrefixed("translex.info.config_opened", url)));
        return Command.SINGLE_SUCCESS;
    }

    // ===============================================================
    // 辅助方法
    // ===============================================================

    /** 检查 API Key 是否缺失或为默认占位值（调试模式下跳过检查） */
    private static boolean isApiKeyMissing() {
        if (ModConfig.get().debug) return false;
        String key = ModConfig.get().apiKey;
        return key == null || key.isBlank() || key.equals("YOUR_API_KEY_HERE");
    }

    /** 显示 API Key 未配置的提示消息 */
    private static int showApiKeyHint(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        source.sendError(Component.literal(
                I18nHelper.getPrefixed("translex.error.api_key_unset")));
        source.sendFeedback(Component.literal(
                "  §e/translex config §7— " + I18nHelper.translate("translex.error.api_key_hint")));
        return 0;
    }

    /** 移除所有 Minecraft 颜色代码（§ 前缀的 ANSI 格式码） */
    private static String removeColorCodes(String text) {
        if (text == null) return null;
        Matcher matcher = COLOR_CODE_PATTERN.matcher(text);
        return matcher.replaceAll("");
    }

    /**
     * 去掉包裹整段文本的一对引号，并还原 \" \\ 转义。
     * AutoChatHandler 短消息按钮把文本 escape 后包成 {@code "..."} 拼进命令，
     * Brigadier greedyString 不会自动剥离，需在此还原，避免引号被当内容翻译。
     */
    private static String stripWrappingQuotes(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            t = t.substring(1, t.length() - 1);
            // 还原按钮拼接时做的转义（先 \\ → \，再 \" → "）
            t = t.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return t;
    }
}
