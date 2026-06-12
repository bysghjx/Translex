package top.iencand.translex.client.listener;

import net.minecraft.text.Text;

/**
 * 通用消息查询接口，用于在不同翻译模式实现中按 ID 查找聊天消息。
 * 各模式（AutoChatHandler、ChatTranslateHandler）均实现此接口。
 */
@FunctionalInterface
public interface MessageLookup {
    /** 根据消息 ID 获取原始聊天消息 */
    Text getMessageById(int messageId);
}
