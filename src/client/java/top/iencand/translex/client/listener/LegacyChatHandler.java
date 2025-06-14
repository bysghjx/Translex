package top.iencand.translex.client.listener;


import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
// 导入 Fabric 客户端消息事件
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
// 导入 Minecraft 1.21.5 对应的文本组件和样式类
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
// 导入 Fabric 1.21.5 的 ClickEvent 和 HoverEvent
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.util.Formatting; // 导入 Fabric 1.21.5 的颜色格式

// Fabric 不使用 @SideOnly 注解
@Environment(EnvType.CLIENT) // 标记为客户端代码
public class LegacyChatHandler { // 可以保留原类名 LegacyChatHandler

    // Mod 定义的用于触发翻译的客户端命令基础部分
    // 注意：这个命令需要在 Fabric 的客户端命令系统中注册 (由 LegacyTranslateCommand 完成)
    // 并且 ClickEvent.runCommand 需要命令以斜杠开头
    public static final String TRANSLATE_COMMAND_BASE = "translate"; // 确保与 LegacyTranslateCommand 注册的命令名一致 (带斜杠用于 ClickEvent)

    // 构造函数，可以为空或用于初始化
    public LegacyChatHandler() {
        System.out.println("[Translex] LegacyChatHandler created.");
    }

    /**
     * 注册客户端聊天消息接收事件监听器。
     * 这个方法应该在 ClientModInitializer 中被调用。
     */
    public void registerEvents() {
        // 注册 MODIFY_GAME 事件，用于在游戏聊天框显示前修改消息
        // 使用 lambda 表达式实现 ClientReceiveMessageEvents.ModifyChatMessage 接口
        // 回调函数接收 message (Text) 和 overlay (boolean) 参数
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            // overlay 为 true 表示是叠加消息 (例如 actionbar)，通常不需要翻译按钮
            if (overlay) {
                return message; // 不处理叠加消息，返回原消息
            }

            // message 参数已经是 Text 类型，直接使用
            if (message == null || message.getString().trim().isEmpty()) {
                return message; // 如果是空消息，不添加按钮，直接返回原始消息
            }

            // TODO: 添加逻辑判断是否需要为这条消息添加翻译功能 (例如，过滤系统消息)
            // 在 MODIFY_GAME 事件中，很难像 Forge 那样区分玩家消息和系统消息的内部类型。
            // 一个简单的判断是检查消息的开头是否符合玩家消息格式 (如 "<玩家名>:")，但这不总是可靠。
            // 如果需要精确过滤，可能需要更复杂的逻辑或 Mixin。
            // 简单起见，我们先为所有非空标准聊天消息添加按钮。

            // --- 核心逻辑：获取文本，创建按钮，附加按钮 ---

            // 1. 获取消息的纯文本内容 (用于放入命令参数)
            // 使用 Text.getString() 获取纯文本
            String fullMessageText = message.getString();

            // 2. 创建“翻译按钮”组件 (使用 Fabric Text API)
            // 使用 Text.literal 创建文本，并使用 formatted() 或 setStyle() 设置颜色
            MutableText translateButton = Text.literal("[翻译]") // 创建文本 "[翻译]"
                    .formatted(Formatting.GREEN); // 设置绿色样式 (更简洁的方式)
            // 或者使用 setStyle: .setStyle(Style.EMPTY.withColor(Formatting.GREEN));

            // 3. 创建 HoverEvent (使用 Fabric HoverEvent)
            // 使用 HoverEvent.ShowText 静态工厂方法
            // TODO: 迁移悬停提示文本到语言文件
            HoverEvent hoverEvent = new HoverEvent.ShowText(
                    Text.literal("§a点击翻译这条消息") // 悬停提示文本，使用 Text.literal
            );// 悬停提示文本，使用 Text.literal

            // 4. 创建 ClickEvent (使用 Fabric ClickEvent)，命令包含完整消息文本
            // 使用 ClickEvent.runCommand 静态工厂方法
            ClickEvent clickEvent = new ClickEvent.RunCommand(
                    "/" + TRANSLATE_COMMAND_BASE + " " + fullMessageText // 将完整消息文本放入命令参数
            );

            // 5. 将 HoverEvent 和 ClickEvent 应用到按钮组件的样式 (使用 Fabric Style API)
            // 使用 copy() 创建一个可变副本，然后设置样式
            translateButton.setStyle(translateButton.getStyle() // 获取当前样式
                    .withHoverEvent(hoverEvent) // 添加悬停事件
                    .withClickEvent(clickEvent)); // 添加点击事件

            // 6. 构建新的组合消息：按钮 + 空格 + 原始消息
            // 创建一个可变文本组件用于拼接
            MutableText newMessageWithButton = Text.empty(); // 创建一个空的 MutableText
            // **拼接顺序决定显示顺序**
            newMessageWithButton.append(translateButton); // 先添加按钮
            newMessageWithButton.append(Text.literal(" ")); // 添加一个空格分隔 (使用 Text.literal)
            newMessageWithButton.append(message); // 最后附加原始消息组件 (它保留了原有的颜色、格式和事件)

            System.out.println("[Translex LegacyChatHandler] Added translate button to message: " + fullMessageText.substring(0, Math.min(fullMessageText.length(), 50)) + "...");

            // 7. 返回新构建的组合消息，这将替换原始消息在聊天框中显示
            return newMessageWithButton;
        });

        System.out.println("[Translex] LegacyChatHandler registered for MODIFY_GAME event.");
    }

    // 这个类不存储消息 ID，所以不需要 getMessageById 方法
}
