package top.iencand.translex.client.listener; // 确保包名正确

// 导入 Fabric 1.21.5 对应的文本组件和样式类
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
// 导入 Fabric 1.21.5 的 ClickEvent 和 HoverEvent
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent; // 确保导入的是 net.minecraft.text.HoverEvent
import net.minecraft.util.Formatting; // 导入 Fabric 1.21.5 的颜色格式

// 导入 Fabric 的客户端聊天修改事件
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
// 不需要单独导入 ModifyChatMessage 接口，因为我们直接在 register 方法中使用 lambda 实现了它

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

// Fabric 不使用 @SideOnly 注解

public class ChatTranslateHandler {

    // --- 消息存储 (适配 Text 类型) ---
    // 使用 LinkedHashMap 作为 LRU 缓存，限制存储的消息数量
    private final Map<Integer, Text> recentMessages = new LinkedHashMap<Integer, Text>(100, 0.75f, true) {
        private static final int MAX_ENTRIES = 100;
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Text> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    // 用于生成消息的唯一 ID
    private final AtomicInteger messageCounter = new AtomicInteger(0);

    // Mod 定义的用于触发翻译的客户端命令基础部分 (保持不变，但需要在 Fabric 命令系统中注册)
    // 注意：这个命令需要在 Fabric 的客户端命令系统中注册
    public static final String TRANSLATE_COMMAND_BASE = "translex"; // Fabric 命令不带斜杠

    // 构造函数
    public ChatTranslateHandler() {
        // 注册客户端修改游戏聊天消息事件
        // 使用 lambda 表达式实现 ClientReceiveMessageEvents.ModifyChatMessage 接口
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, pack) -> {
            // message 参数已经是 Text 类型，直接使用
            if (message == null || message.getString().trim().isEmpty()) {
                return message; // 如果是空消息，不添加按钮，直接返回原始消息
            }

            // TODO: 添加逻辑判断是否需要为这条消息添加翻译功能 (例如，过滤系统消息)
            // MODIFY_GAME 事件会处理所有游戏消息。
            // 你可能需要在这里根据消息内容或结构来判断是否添加按钮。
            // 简单起见，我们先为所有非空消息添加按钮。

            // --- 核心逻辑：生成ID，存储消息，并构建带按钮的新消息 ---

            // 1. 生成唯一 ID
            int messageId = messageCounter.getAndIncrement();

            // 2. 存储完整的原始消息组件 (存储 Text 类型)
            // 注意：这里存储的是原始的 Text 组件，它保留了原有的颜色、格式和事件。
            recentMessages.put(messageId, message);
            System.out.println("[ChatTranslate] 截获并存储消息 (ID: " + messageId + ")，尝试构建带翻译按钮的新消息.");

            // 3. 创建“翻译按钮”组件 (使用 Fabric Text API)
            // 使用 Text.literal 创建文本，并使用 setStyle 或 withColor 等方法设置样式
            MutableText translateButton = Text.literal("[翻译]").setStyle(Style.EMPTY.withColor(Formatting.GREEN)); // 创建绿色 [翻译] 按钮

            // 4. 创建点击事件 (包含消息 ID) (使用 Fabric ClickEvent)
            // 正确的使用 1.21.5 Text.ClickEvent 的静态工厂方法 runCommand()
            ClickEvent clickEvent = new ClickEvent.RunCommand("/" + TRANSLATE_COMMAND_BASE + " " + messageId);

            // 5. 创建悬停提示事件 (可选) (使用 Fabric HoverEvent)
            // *** 正确的使用 1.21.5 Text.HoverEvent 的静态工厂方法 showText() ***
            HoverEvent hoverEvent = new HoverEvent.ShowText(
                    Text.literal("§a点击翻译这条消息 (ID: " + messageId + ")") // 悬停提示文本，使用 Text.literal
            );

            // 6. **只给“翻译按钮”设置样式和事件** (使用 Fabric Style API)
            // 使用 copy() 创建一个可变副本，然后设置样式
            translateButton = translateButton.copy().setStyle(translateButton.getStyle()
                    .withClickEvent(clickEvent)
                    .withHoverEvent(hoverEvent));
            // 还可以添加其他样式，比如粗体： .withBold(true)

            // 7. 构建最终显示的消息：按钮 + 空格 + 原始消息
            // 创建一个可变文本组件用于拼接
            MutableText finalMessageToShow = Text.empty(); // 创建一个空的 MutableText
            // **拼接顺序决定显示顺序**
            finalMessageToShow.append(translateButton); // 先添加按钮
            finalMessageToShow.append(Text.literal(" ")); // 添加一个空格分隔 (使用 Text.literal)
            finalMessageToShow.append(message); // 再添加原始消息组件 (它保留了原有的颜色、格式和事件)

            System.out.println("[ChatTranslate] 构建完成新消息，包含 ID: " + messageId);

            // 8. 返回修改后的消息
            return finalMessageToShow;
        });
    }

    // --- 提供方法给命令处理器获取存储的消息 (适配 Text 类型) ---
    /**
     * 根据消息 ID 获取存储的原始 Text 消息。
     * @param messageId 消息的唯一 ID
     * @return 存储的 Text 组件，如果找不到则返回 null
     */
    public Text getMessageById(int messageId) {
        return recentMessages.get(messageId);
    }

    // --- 不再需要 copyAndAddStyle 方法，可以删除了 ---
}