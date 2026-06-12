package top.iencand.translex.client.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
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
 * <p>拦截 {@link ChatHud#addMessage(Text, MessageSignatureData, MessageIndicator)}（三参数版本），
 * 因为单参数版本 {@code addMessage(Text)} 内部委托给三参数版本，
 * 所以拦截此方法可以覆盖所有入口。
 *
 * <p>处理流水线（按执行顺序）：
 * <ol>
 *   <li>{@link SpamHider} — 检查是否命中过滤规则（HIDDEN/SEPARATE）</li>
 *   <li>{@link MessageManager} — 同内容消息折叠合并计数</li>
 * </ol>
 */
@Mixin(value = ChatHud.class, priority = Integer.MAX_VALUE)
public abstract class ChatHudMixin implements IChatHudExt {
    @Shadow @Final private List<ChatHudLine> messages;
    @Shadow protected abstract void refresh();

    /** 单参数版本的 addMessage（供 forceAddMessage 内部调用） */
    @Shadow public abstract void addMessage(Text message);

    @Unique
    private final MessageManager messageManager = new MessageManager(this);

    @Unique
    private boolean isInternalRedirect = false;

    /**
     * 拦截三参数版本的 addMessage，修改 Text 参数。
     * 这是所有消息进入聊天栏的必经之路。
     *
     * <p>处理顺序：
     * <ol>
     *   <li>SpamHider 检查 — 命中则返回哨兵标记取消消息</li>
     *   <li>MessageManager 折叠 — 高频重复消息原地更新计数</li>
     * </ol>
     */
    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/text/Text;"
                   + "Lnet/minecraft/network/message/MessageSignatureData;"
                   + "Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    public Text translex$interceptMessage(Text message) {
        if (isInternalRedirect) return message;

        // --- 第 1 层：SpamHider 过滤 ---
        SpamFilterData.FilterState spamResult = SpamHider.getInstance().checkMessage(message);
        if (spamResult == SpamFilterData.FilterState.HIDDEN) {
            return Text.literal("TRANSLEX_SPAM_BLOCKED");
        }
        if (spamResult == SpamFilterData.FilterState.SEPARATE) {
            SpamOverlayRenderer.getInstance().addMessage(message);
            return Text.literal("TRANSLEX_SPAM_BLOCKED");
        }

        // --- 第 2 层：消息折叠/合并 ---
        Text processed = this.messageManager.compactMessage(message);

        // 返回 null 表示高频静默期，用标记文本替代以触发后续取消注入
        return (processed == null) ? Text.literal("TRANSLEX_SILENT_CANCEL") : processed;
    }

    /**
     * 拦截哨兵标记，阻止标记消息实际显示到聊天栏。
     */
    @Inject(
            method = "addMessage(Lnet/minecraft/text/Text;"
                   + "Lnet/minecraft/network/message/MessageSignatureData;"
                   + "Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void translex$cancelSilentUpdate(
            Text message,
            MessageSignatureData signature,
            MessageIndicator indicator,
            CallbackInfo ci) {
        if (message != null) {
            String s = message.getString();
            if (s.equals("TRANSLEX_SILENT_CANCEL") || s.equals("TRANSLEX_SPAM_BLOCKED")) {
                ci.cancel();
            }
        }
    }

    @Override
    public List<ChatHudLine> translex$getMessages() {
        return this.messages;
    }

    @Override
    public void translex$refreshMessages() {
        this.refresh();
    }

    @Override
    public void translex$forceAddMessage(Text message) {
        this.isInternalRedirect = true;
        try {
            this.addMessage(message);
        } finally {
            this.isInternalRedirect = false;
        }
    }
}
