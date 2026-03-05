package top.iencand.translex.client.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import top.iencand.translex.client.Translate.TranslationManager;
import top.iencand.translex.client.util.I18nHelper; // 导入工具类

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class LegacyTranslateCommand {

    private final TranslationManager translationManager;
    public static final String COMMAND_NAME = "translate";
    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("§[0-9a-fk-or]");

    public LegacyTranslateCommand(TranslationManager translationManager) {
        this.translationManager = translationManager;
    }

    /**
     * 注册 /translate <message> 命令
     */
    public void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, environment) -> {
            dispatcher.register(literal(COMMAND_NAME)
                    .then(argument("message", greedyString())
                            .executes(this::executeCommand)
                    )
            );
        });
    }

    private int executeCommand(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String fullMessageTextFromCommand;

        try {
            fullMessageTextFromCommand = getString(context, "message");
        } catch (IllegalArgumentException e) {
            // 错误处理：参数缺失 (translex.error.usage_legacy)
            source.sendError(Text.literal(I18nHelper.getPrefixed("translex.error.usage_legacy")));
            return 0;
        }

        // 1. 清理文本
        String cleanedMessageText = removeColorCodes(fullMessageTextFromCommand).trim();

        // 2. 检查清理后内容 (translex.error.content_empty)
        if (cleanedMessageText.isEmpty()) {
            source.sendError(Text.literal(I18nHelper.getPrefixed("translex.error.content_empty")));
            return 0;
        }

        // 3. 构建上下文预览并调用异步翻译
        String contextString = cleanedMessageText.substring(0, Math.min(cleanedMessageText.length(), 30)) + "...";
        translationManager.translateTextAsync(cleanedMessageText, contextString);

        return Command.SINGLE_SUCCESS;
    }

    private static String removeColorCodes(String text) {
        if (text == null) return null;
        Matcher matcher = COLOR_CODE_PATTERN.matcher(text);
        return matcher.replaceAll("");
    }
}