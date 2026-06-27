package top.iencand.translex.client.message;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.ext.IChatHudExt;
import top.iencand.translex.client.util.ChatProcessor;

import java.util.HashMap;
import java.util.Map;

/**
 * 消息管理器，负责聊天消息的折叠/合并（Compact）功能。
 * 将相同内容的连续消息合并显示，并添加 (xN) 计数，减少聊天栏刷屏。
 *
 * <p>核心策略：
 * <ul>
 *   <li>同内容消息在 1 秒内重复出现 → 静默更新计数（不产生滚动动画）</li>
 *   <li>超过 1 秒再次出现 → 执行”置顶”（删除旧消息，重新添加到最底部）</li>
 *   <li>超过 compactTimeSeconds 未出现 → 清除缓存记录</li>
 * </ul>
 */
public class MessageManager {
    private final IChatHudExt chatHud;
    private final Map<String, MessageData> cache = new HashMap<>();

    public MessageManager(IChatHudExt chatHud) {
        this.chatHud = chatHud;
    }

    /**
     * 对收到的消息执行折叠处理。
     * @param message 原始消息
     * @return 处理后的消息，或 null 表示静默拦截（不显示）
     */
    public Component compactMessage(Component message) {
        ModConfig config = ModConfig.get();
        if (!config.enableChatCompact || message == null) return message;
        if (!ChatProcessor.shouldProcess(message)) return message;

        String fingerprint = ChatProcessor.getFoldFingerprint(message);
        long now = System.currentTimeMillis();

        // 清理过期的缓存条目（超过 compactTimeSeconds 未更新的）
        cache.entrySet().removeIf(e -> (now - e.getValue().lastTime) > (config.compactTimeSeconds * 1000L));

        if (cache.containsKey(fingerprint)) {
            MessageData data = cache.get(fingerprint);
            data.count++;
            data.lastTime = now;

            // 核心逻辑：定时置顶判断
            // 距离上一次”跳”到最下面超过 1000ms 时执行置顶
            if (now - data.lastPushTime > 1000) {
                // 先删掉旧的同内容消息
                removeOldByFingerprint(fingerprint);
                // 更新最后推送时间
                data.lastPushTime = now;
                // 返回带计数的消息，它会通过 addMessage 出现在最底部
                return formatWithCount(message, data.count);
            } else {
                // 1 秒间隔内：执行静默原地更新（只更新数字，不产生滚动动画）
                updateMessageInPlace(fingerprint, message, data.count);
                // 返回 null 拦截这次 addMessage
                return null;
            }
        } else {
            // 首次出现，记录初始推送时间
            cache.put(fingerprint, new MessageData(now));
            return message;
        }
    }

    /**
     * 原地静默更新数字：在聊天列表中找到对应行，直接替换其 Component 内容。
     * 不会产生滚动动画，因为不经过 addMessage 流程。
     */
    private void updateMessageInPlace(String fingerprint, Component baseText, int count) {
        var lines = this.chatHud.translex$getMessages();
        for (int i = 0; i < lines.size(); i++) {
            if (ChatProcessor.getFoldFingerprint(lines.get(i).content()).equals(fingerprint)) {
                var oldLine = lines.get(i);
                lines.set(i, new net.minecraft.client.multiplayer.chat.GuiMessage(
                        oldLine.addedTime(),
                        formatWithCount(baseText, count),
                        oldLine.signature(),
                        oldLine.source(),
                        oldLine.tag()
                ));
                this.chatHud.translex$refreshMessages();
                return;
            }
        }
    }

    /** 通过指纹从聊天列表中删除所有匹配的旧消息 */
    private void removeOldByFingerprint(String fingerprint) {
        this.chatHud.translex$getMessages().removeIf(line ->
                ChatProcessor.getFoldFingerprint(line.content()).equals(fingerprint)
        );
        this.chatHud.translex$refreshMessages();
    }

    /** 格式化带计数的消息文本 */
    private Component formatWithCount(Component base, int count) {
        if (count <= 1) return base;
        return base.copy().append(Component.literal(" (x" + count + ")").withStyle(ModConfig.get().getCompactColor()));
    }

    /** 清空所有折叠缓存 */
    public void clear() { cache.clear(); }

    /**
     * 单条消息的折叠状态数据。
     */
    private static class MessageData {
        int count = 1;          // 当前折叠计数
        long lastTime;          // 最后一次收到同内容消息的时间戳
        long lastPushTime;      // 最后一次执行”置顶（addMessage）”的时间戳

        MessageData(long time) {
            this.lastTime = time;
            this.lastPushTime = time; // 初始时认为已经推送过一次
        }
    }
}