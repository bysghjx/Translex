package top.iencand.translex.client.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.iencand.translex.client.ext.IChatHudExt;
import top.iencand.translex.client.message.MessageManager;
import top.iencand.translex.client.spam.SpamFilterData;
import top.iencand.translex.client.spam.SpamHider;
import top.iencand.translex.client.spam.SpamOverlayRenderer;

import java.util.List;

/**
 * 聊天 HUD 的 Mixin 注入点，所有消息进入聊天栏前都经过此管道。
 *
 * <p>26.x 重构：{@code ChatHud} 已更名为 {@link ChatComponent}，所有公开的
 * {@code addClientSystemMessage}/{@code addServerSystemMessage}/{@code addPlayerMessage}
 * 入口最终都委托给私有的四参数
 * {@code addMessage(Component, MessageSignature, GuiMessageSource, GuiMessageTag)}，
 * 因此拦截该私有方法即可覆盖所有入口。
 *
 * <p>处理流水线（按执行顺序）：
 * <ol>
 *   <li>{@link SpamHider} — 检查是否命中过滤规则（HIDDEN/SEPARATE）</li>
 *   <li>{@link MessageManager} — 同内容消息折叠合并计数</li>
 * </ol>
 */
@Mixin(value = ChatComponent.class, priority = Integer.MAX_VALUE)
public abstract class ChatHudMixin implements IChatHudExt {
    @Shadow @Final private List<GuiMessage> allMessages;
    @Shadow protected abstract void refreshTrimmedMessages();

    /** 系统消息入口（供 forceAddMessage 内部调用，走非拦截路径） */
    @Shadow public abstract void addClientSystemMessage(Component message);

    @Unique
    private final MessageManager messageManager = new MessageManager(this);

    @Unique
    private boolean isInternalRedirect = false;

    /**
     * 拦截私有四参数版本的 addMessage，修改 Component 参数。
     * 这是所有消息进入聊天栏的必经之路。
     *
     * <p>处理顺序：
     * <ol>
     *   <li>SpamHider 检查 — 命中则返回哨兵标记取消消息</li>
     *   <li>MessageManager 折叠 — 高频重复消息原地更新计数</li>
     * </ol>
     */
    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/network/chat/Component;"
                   + "Lnet/minecraft/network/chat/MessageSignature;"
                   + "Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;"
                   + "Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    public Component translex$interceptMessage(Component message) {
        if (isInternalRedirect) return message;

        // --- 第 1 层：SpamHider 过滤 ---
        SpamFilterData.FilterState spamResult = SpamHider.getInstance().checkMessage(message);
        if (spamResult == SpamFilterData.FilterState.HIDDEN) {
            return Component.literal("TRANSLEX_SPAM_BLOCKED");
        }
        if (spamResult == SpamFilterData.FilterState.SEPARATE) {
            SpamOverlayRenderer.getInstance().addMessage(message);
            return Component.literal("TRANSLEX_SPAM_BLOCKED");
        }

        // --- 第 2 层：消息折叠/合并 ---
        Component processed = this.messageManager.compactMessage(message);

        // 返回 null 表示高频静默期，用标记文本替代以触发后续取消注入
        return (processed == null) ? Component.literal("TRANSLEX_SILENT_CANCEL") : processed;
    }

    /**
     * 拦截哨兵标记，阻止标记消息实际显示到聊天栏。
     */
    @Inject(
            method = "addMessage(Lnet/minecraft/network/chat/Component;"
                   + "Lnet/minecraft/network/chat/MessageSignature;"
                   + "Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;"
                   + "Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void translex$cancelSilentUpdate(
            Component message,
            MessageSignature signature,
            GuiMessageSource source,
            GuiMessageTag tag,
            CallbackInfo ci) {
        if (message != null) {
            String s = message.getString();
            if (s.equals("TRANSLEX_SILENT_CANCEL") || s.equals("TRANSLEX_SPAM_BLOCKED")) {
                ci.cancel();
            }
        }
    }

    @Override
    public List<GuiMessage> translex$getMessages() {
        return this.allMessages;
    }

    @Override
    public void translex$refreshMessages() {
        this.refreshTrimmedMessages();
    }

    @Override
    public void translex$forceAddMessage(Component message) {
        this.isInternalRedirect = true;
        try {
            this.addClientSystemMessage(message);
        } finally {
            this.isInternalRedirect = false;
        }
    }
}
