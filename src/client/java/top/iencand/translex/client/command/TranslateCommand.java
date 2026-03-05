package top.iencand.translex.client.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import top.iencand.translex.client.listener.ChatTranslateHandler;
import top.iencand.translex.client.Translate.TranslationManager;
import top.iencand.translex.client.util.I18nHelper; // 确保导入工具类

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class TranslateCommand {

    private final ChatTranslateHandler chatTranslateHandler;
    private final TranslationManager translationManager;

    public TranslateCommand(ChatTranslateHandler handler, TranslationManager manager) {
        this.chatTranslateHandler = handler;
        this.translationManager = manager;
    }

    public void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, environment) -> {
            dispatcher.register(literal("translex")
                    .then(argument("message_id", IntegerArgumentType.integer())
                            .executes(this::executeCommand)));
        });
    }

    private int executeCommand(CommandContext<FabricClientCommandSource> context) {
        int messageId = IntegerArgumentType.getInteger(context, "message_id");
        MinecraftClient client = MinecraftClient.getInstance();

        // 1. 获取原始消息
        Text originalMessage = chatTranslateHandler.getMessageById(messageId);

        // 2. 错误处理：ID 不存在
        if (originalMessage == null) {
            client.execute(() -> {
                // 使用 i18n 获取错误提示，并注入 messageId
                String errorMsg = I18nHelper.translate("translex.error.not_found", messageId);
                // 使用标准的 sendError 会自带前缀颜色
                context.getSource().sendError(Text.literal(I18nHelper.translate("translex.prefix") + errorMsg));
            });
            return 0;
        }

        // 3. 提取文本逻辑 (保持原有逻辑)
        String messageTextToTranslate = originalMessage.getString();
        String playerNameWithSeparator = "";

        int firstColonSpace = messageTextToTranslate.indexOf(": ");
        if (firstColonSpace != -1) {
            playerNameWithSeparator = messageTextToTranslate.substring(0, firstColonSpace + 2);
            messageTextToTranslate = messageTextToTranslate.substring(firstColonSpace + 2);
        } else {
            messageTextToTranslate = originalMessage.getString();
        }

        // 4. 调用异步翻译 (内部会处理 I18n 前缀)
        translationManager.translateAsync(messageId, messageTextToTranslate, playerNameWithSeparator);

        return Command.SINGLE_SUCCESS;
    }
}