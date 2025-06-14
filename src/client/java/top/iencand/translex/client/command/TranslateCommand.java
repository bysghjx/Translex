package top.iencand.translex.client.command;

import com.mojang.brigadier.Command; // 导入 Command 常量
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
// 导入正确的客户端命令注册回调
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource; // 导入客户端命令源
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import top.iencand.translex.client.listener.ChatTranslateHandler;
import top.iencand.translex.client.Translate.TranslationManager;


// 导入 Brigadier 静态方法 (用于构建命令)

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class TranslateCommand { // 建议改名为 MyChatTranslateCommand 或类似，以区分 LegacyTranslateCommand

    private final ChatTranslateHandler chatTranslateHandler;
    private final TranslationManager translationManager;

    public TranslateCommand(ChatTranslateHandler handler, TranslationManager manager) {
        this.chatTranslateHandler = handler;
        this.translationManager = manager;
    }

    /**
     * 注册客户端命令 /translex <message_id>。
     * 这个方法应该在 ClientModInitializer 中被调用。
     */
    public void register() {
        // 注册到客户端命令事件
        // *** 修正回调方法签名，需要 environment 参数 ***
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, environment) -> {
            // 注册 `/translex <message_id>` 命令
            // *** 修正命令构建方式，使用 Brigadier 静态方法 literal() 和 argument() ***
            dispatcher.register(literal("translex") // 定义命令名称 "/translex"
                    .then(argument("message_id", IntegerArgumentType.integer()) // 定义一个名为 message_id 的整数参数
                            // executes 方法的上下文是 FabricClientCommandSource，这部分是正确的
                            .executes(this::executeCommand))); // 将执行委托给 executeCommand 方法
        });
    }

    /**
     * 命令 /translex <message_id> 的执行逻辑。
     * @param context 命令执行上下文，类型为 FabricClientCommandSource
     * @return 命令执行结果，通常 Command.SINGLE_SUCCESS 表示成功
     */
    private int executeCommand(CommandContext<FabricClientCommandSource> context) {
        int messageId = IntegerArgumentType.getInteger(context, "message_id");
        MinecraftClient client = MinecraftClient.getInstance();

        // 获取原始消息
        Text originalMessage = chatTranslateHandler.getMessageById(messageId);

        if (originalMessage == null) {
            // 在主线程发送消息
            client.execute(() -> {
                // 通过命令源发送错误消息，这是更标准的方式
                context.getSource().sendError(
                        Text.literal("§c[翻译 Mod]§r 未找到要翻译的消息 (ID: " + messageId + ")。可能已过期。"));
            });
            // 返回 0 表示失败
            return 0; // 或者 Command.SINGLE_SUCCESS 以外的任何值，如 0
        }

        // 提取需要翻译的文本 (纯文本)
        String messageTextToTranslate = originalMessage.getString();

        // TODO: 实现更智能的文本提取（例如过滤名字），目前先用全部纯文本
        String playerNameWithSeparator = ""; // 用于上下文 (现在不再直接用于显示前缀)
        // 示例：简单提取名字和内容
        int firstColonSpace = messageTextToTranslate.indexOf(": ");
        if (firstColonSpace != -1) {
            playerNameWithSeparator = messageTextToTranslate.substring(0, firstColonSpace + 2);
            messageTextToTranslate = messageTextToTranslate.substring(firstColonSpace + 2);
        } else {
            // 如果没有冒号，使用原始文本作为要翻译的内容
            // playerNameWithSeparator = originalMessage.getString().substring(0, Math.min(originalMessage.getString().length(), 30)) + "... "; // 这个上下文信息可能不再需要
            messageTextToTranslate = originalMessage.getString();
        }


        // 调用 TranslationManager 的方法执行异步翻译
        // translationManager 内部已经处理了调度回主线程并在聊天框显示结果
        translationManager.translateAsync(messageId, messageTextToTranslate, playerNameWithSeparator);

        // 命令执行成功，但翻译是异步的，不发送默认成功消息
        // context.getSource().sendFeedback(Text.literal("§a[翻译 Mod]§r 已发送翻译请求..."), false); // 第二个参数 false 表示不发送到服务器日志

        // 返回 Command.SINGLE_SUCCESS 表示成功
        return Command.SINGLE_SUCCESS;
    }
}