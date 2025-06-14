package top.iencand.translex.client.command; // 更新包名

import com.mojang.brigadier.Command; // 导入 Brigadier Command 常量
import com.mojang.brigadier.context.CommandContext; // 导入命令上下文
// 导入 Fabric 客户端命令相关类
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource; // 导入客户端命令源
// 导入 Minecraft 核心文本组件类
import net.minecraft.text.Text;
// 导入您的 Gson 配置类
import top.iencand.translex.client.config.ModConfig;
// 导入您的 TranslationManager 类
import top.iencand.translex.client.Translate.TranslationManager;

// 导入 Brigadier 静态方法 (用于构建命令)
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

// Fabric 不使用 @SideOnly 注解
// @SideOnly(Side.CLIENT)
public class ReloadConfigCommand {

    // 定义命令名称常量
    private static final String COMMAND_NAME = "translexreload";

    private final TranslationManager translationManager; // 持有 TranslationManager 实例的引用

    // 构造函数，接收 TranslationManager 实例
    public ReloadConfigCommand(TranslationManager translationManager) {
        this.translationManager = translationManager;
    }

    /**
     * 注册客户端命令 /translexreload。
     * 这个方法应该在 ClientModInitializer 中被调用。
     */
    public void register() {
        // 注册到客户端命令事件
        // 使用 lambda 表达式实现 ClientCommandRegistrationCallback.EVENT 回调接口
        // 回调方法接收 CommandDispatcher 和 CommandEnvironment
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, environment) -> {
            // 注册命令树
            dispatcher.register(literal(COMMAND_NAME) // 定义命令名称 "/translexreload" (Fabric 命令注册时 literal 不带斜杠)
                    // executes 方法指定命令执行的回调函数
                    // 回调函数签名通常是 int execute(CommandContext<Source> context)
                    .executes(this::executeCommand)); // 将执行委托给 executeCommand 方法
        });
        System.out.println("[Translex] ReloadConfigCommand registered: /" + COMMAND_NAME);
    }

    /**
     * 命令 /translexreload 的执行逻辑。
     * 这个方法将作为 Brigadier executes 方法的回调。
     * @param context 命令执行上下文，类型为 FabricClientCommandSource
     * @return 命令执行结果，通常 Command.SINGLE_SUCCESS 表示成功，0 表示失败
     */
    private int executeCommand(CommandContext<FabricClientCommandSource> context) {
        // 从上下文中获取 Fabric 客户端命令源
        FabricClientCommandSource source = context.getSource();

        try {
            // --- 核心逻辑：重新加载配置并更新 TranslationManager ---

            // 1. 重新加载配置文件 (调用 Gson 实现的 ModConfig 的静态加载方法)
            System.out.println("[Translex ReloadCommand] Attempting to reload configuration...");
            ModConfig.loadConfig(); // ModConfig.loadConfig() 会从文件加载配置并更新其静态实例
            ModConfig config = ModConfig.get(); // 获取新加载的配置实例

            // 2. 更新 TranslationManager 中的设置
            // 使用从新加载的配置中获取的值来更新 TranslationManager
            translationManager.updateSettings(
                    config.apiKey,
                    config.translationPrompt,
                    config.modelName,
                    config.apiUrl
            );
            System.out.println("[Translex ReloadCommand] TranslationManager settings updated from reloaded config.");

            // 3. 不需要手动保存配置，除非你想强制写入默认值（ModConfig.loadConfig() 在文件不存在时会保存默认配置）

            // 4. 给玩家发送成功提示
            // 使用 source.sendFeedback() 发送消息到聊天框
            source.sendFeedback(Text.literal("§a[翻译 Mod] 配置文件已重新加载 API 设置！")); // 第二个参数 false 表示不发送到服务器日志

            // 报告当前启用模式 (从新加载的配置中获取)
            if (config.enableMessageIdSystem) {
                source.sendFeedback(Text.literal("§a[翻译 Mod] 当前启用模式：消息 ID + 按钮 (§e需要重启游戏切换§a)。"));
            } else {
                source.sendFeedback(Text.literal("§a[翻译 Mod] 当前启用模式：命令 + 文本 (§e需要重启游戏切换§a)。"));
            }
            System.out.println("[Translex ReloadCommand] Reload successful.");

        } catch (Exception e) {
            // 处理加载配置或更新设置时的异常
            System.err.println("[Translex ReloadCommand] Error reloading configuration!");
            e.printStackTrace();
            // 使用 source.sendError() 发送错误消息到聊天框
            source.sendError(Text.literal("§c[翻译 Mod] 重新加载配置文件时出错，请查看日志！"));
            System.err.println("[Translex ReloadCommand] Reload failed.");
            return 0; // Brigadier 通常返回 0 表示命令执行失败
        }

        // 如果命令执行成功，返回 Command.SINGLE_SUCCESS (其值为 1)
        return Command.SINGLE_SUCCESS;
    }

    // 在 Fabric Brigadier 客户端命令中，不再需要覆盖 Forge 的 getCommandUsage, canCommandSenderUseCommand 等方法。
}