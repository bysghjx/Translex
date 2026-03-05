package top.iencand.translex.client.message;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.ext.IChatHudExt;
import top.iencand.translex.client.util.ChatProcessor;

import java.util.HashMap;
import java.util.Map;

public class MessageManager {
    private final IChatHudExt chatHud;
    private final Map<String, MessageData> cache = new HashMap<>();

    public MessageManager(IChatHudExt chatHud) {
        this.chatHud = chatHud;
    }

    public Text compactMessage(Text message) {
        ModConfig config = ModConfig.get();
        if (!config.enableChatCompact || message == null) return message;
        if (!ChatProcessor.shouldProcess(message)) return message;

        String fingerprint = ChatProcessor.getFoldFingerprint(message);
        long now = System.currentTimeMillis();

        // 清理 120 秒过期的缓存
        cache.entrySet().removeIf(e -> (now - e.getValue().lastTime) > (config.compactTimeSeconds * 1000L));

        if (cache.containsKey(fingerprint)) {
            MessageData data = cache.get(fingerprint);
            data.count++;
            data.lastTime = now;

            // --- 核心逻辑：定时置顶判断 ---
            // 如果距离上一次“跳”到最下面已经超过了 1000 毫秒
            if (now - data.lastPushTime > 1000) {
                // 执行置顶：先删掉旧的
                removeOldByFingerprint(fingerprint);
                // 更新最后推送时间
                data.lastPushTime = now;
                // 返回带计数的消息，它会自然地进入 addMessage 流程出现在最底部
                return formatWithCount(message, data.count);
            } else {
                // 在 1 秒的间隔内：执行“静默原地更新”
                updateMessageInPlace(fingerprint, message, data.count);
                // 返回 null 拦截这次 addMessage，不让它产生滚动动画
                return null;
            }
        } else {
            // 第一次出现，记录初始推送时间
            cache.put(fingerprint, new MessageData(now));
            return message;
        }
    }

    /**
     * 原地静默更新数字：找到列表里那一行，直接换掉 Text
     */
    private void updateMessageInPlace(String fingerprint, Text baseText, int count) {
        var lines = this.chatHud.translex$getMessages();
        for (int i = 0; i < lines.size(); i++) {
            if (ChatProcessor.getFoldFingerprint(lines.get(i).content()).equals(fingerprint)) {
                var oldLine = lines.get(i);
                lines.set(i, new net.minecraft.client.gui.hud.ChatHudLine(
                        oldLine.creationTick(),
                        formatWithCount(baseText, count),
                        oldLine.signature(),
                        oldLine.indicator()
                ));
                this.chatHud.translex$refreshMessages();
                return;
            }
        }
    }

    private void removeOldByFingerprint(String fingerprint) {
        this.chatHud.translex$getMessages().removeIf(line ->
                ChatProcessor.getFoldFingerprint(line.content()).equals(fingerprint)
        );
        this.chatHud.translex$refreshMessages();
    }

    private Text formatWithCount(Text base, int count) {
        if (count <= 1) return base;
        return base.copy().append(Text.literal(" (x" + count + ")").formatted(ModConfig.get().getCompactColor()));
    }

    public void clear() { cache.clear(); }

    private static class MessageData {
        int count = 1;
        long lastTime;      // 最后一次收到消息的时间
        long lastPushTime;  // 最后一次执行“置顶（addMessage）”的时间

        MessageData(long time) {
            this.lastTime = time;
            this.lastPushTime = time; // 初始时认为已经推送过一次
        }
    }
}