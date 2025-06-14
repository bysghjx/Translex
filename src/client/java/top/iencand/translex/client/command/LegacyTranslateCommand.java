package top.iencand.translex.client.command;

import com.mojang.brigadier.Command; // 导入 Brigadier Command 常量
import com.mojang.brigadier.context.CommandContext; // 导入命令上下文
// 导入 Brigadier 参数类型
import com.mojang.brigadier.arguments.StringArgumentType;

// 导入 Fabric 客户端命令相关类
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource; // 导入客户端命令源

// 导入 Minecraft 核心类
import net.minecraft.text.Text; // 导入 Text 组件类

// 导入您的翻译相关类
import top.iencand.translex.client.Translate.TranslationManager;

// 导入 Java 正则表达式类
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 导入 Brigadier 静态方法 (用于构建命令)
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

// Fabric 不使用 @SideOnly 注解
// @SideOnly(Side.CLIENT)
public class LegacyTranslateCommand { // 使用原始类名

    private final TranslationManager translationManager; // 持有翻译管理器的引用

    // 命令名称常量 (不带斜杠，用于 Brigadier 注册)
    // ClickEvent.runCommand 需要命令以斜杠开头，例如 "/translate"
    public static final String COMMAND_NAME = "translate";

    // 用于移除 § 颜色码的 Pattern (保持不变)
    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("§[0-9a-fk-or]");

    // 构造函数，接收所需的组件实例
    public LegacyTranslateCommand(TranslationManager translationManager) {
        this.translationManager = translationManager;
        System.out.println("[Translex] LegacyTranslateCommand created.");
    }

    /**
     * 注册 /translate <message> 客户端命令。
     * 这个方法应该在 ClientModInitializer 中被调用。
     */
    public void register() {
        // 注册到客户端命令事件
        // 使用 lambda 表达式实现 ClientCommandRegistrationCallback.EVENT 回调接口
        // 回调方法接收 CommandDispatcher 和 CommandEnvironment
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, environment) -> {
            // 注册命令树
            // 使用 literal(COMMAND_NAME) 注册不带斜杠的命令名
            // 游戏客户端会自动处理玩家输入的斜杠
            dispatcher.register(literal(COMMAND_NAME) // 定义命令名称 "translate"
                    // .requires(source -> true) // 可选：如果需要权限检查，但客户端命令通常不需要
                    .then(argument("message", greedyString()) // 定义一个名为 message 的参数，捕获后面所有文本
                            // 将执行委托给 executeCommand 方法
                            // executes 方法的回调函数签名是 int execute(CommandContext<Source> context)
                            .executes(this::executeCommand)
                    )
            );
            System.out.println("[Translex] LegacyTranslateCommand registered: /" + COMMAND_NAME);
        });
    }

    /**
     * 执行 /translate <message> 命令的逻辑。
     * 这个方法将作为 Brigadier executes 方法的回调。
     * @param context 命令执行上下文，类型为 FabricClientCommandSource
     * @return 命令执行结果，通常 Command.SINGLE_SUCCESS 表示成功，0 表示失败
     * @throws CommandSyntaxException 如果命令语法错误 (这里主要由 Brigadier 参数解析处理)
     */
    private int executeCommand(CommandContext<FabricClientCommandSource> context) {
        // 从上下文中获取 Fabric 客户端命令源
        FabricClientCommandSource source = context.getSource();

        String fullMessageTextFromCommand;
        try {
            // 从上下文中获取 greedyString 参数 "message"
            fullMessageTextFromCommand = getString(context, "message");
        } catch (IllegalArgumentException e) {
            // 如果没有提供参数，greedyString() 可能会抛出异常，尽管通常不会
            // TODO: 迁移到语言文件
            source.sendError(Text.literal("§c[翻译 Mod]§r 用法: /translate <消息>"));
            return 0; // 返回 0 表示命令执行失败
        }

        // --- 原 LegacyTranslateCommand.processCommand 中的核心逻辑 ---
        // 1. 清理文本 (移除颜色代码，首尾空格)
        String cleanedMessageText = removeColorCodes(fullMessageTextFromCommand);
        cleanedMessageText = cleanedMessageText.trim();

        if (cleanedMessageText.isEmpty()) {
            // TODO: 迁移到语言文件
            source.sendError(Text.literal("§c[翻译 Mod]§r 命令参数为空或只有颜色代码。"));
            return 0; // 返回 0 表示失败
        }

        // 2. 调用 TranslationManager 的方法进行异步翻译
        // 使用 translateTextAsync，传递清理后的文本和上下文
        // context 参数在新的 TranslationManager 中不再直接用于显示，但保留签名
        String contextString = cleanedMessageText.substring(0, Math.min(cleanedMessageText.length(), 30)) + "...";
        translationManager.translateTextAsync(cleanedMessageText, contextString);

        // 命令处理完成，不发送默认成功消息
        // source.sendFeedback(Text.literal("§a[翻译 Mod]§r 已发送翻译请求..."), false); // 第二个参数 false 表示不发送到服务器日志

        return Command.SINGLE_SUCCESS; // Brigadier 通常返回 Command.SINGLE_SUCCESS (值为 1) 表示成功
    }

    // --- 辅助方法：移除 § 颜色码 ---
    private static String removeColorCodes(String text) {
        if (text == null) return null;
        Matcher matcher = COLOR_CODE_PATTERN.matcher(text);
        return matcher.replaceAll("");
    }

    // 在 Fabric Brigadier 客户端命令中，不再需要覆盖 Forge 的 getCommandUsage, canCommandSenderUseCommand 等方法。
}