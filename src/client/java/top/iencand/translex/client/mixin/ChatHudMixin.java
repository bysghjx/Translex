package top.iencand.translex.client.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
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

import java.util.List;

@Mixin(value = ChatHud.class, priority = Integer.MAX_VALUE)
public abstract class ChatHudMixin implements IChatHudExt {
    @Shadow @Final private List<ChatHudLine> messages;
    @Shadow protected abstract void refresh();

    // 注入目标改为这个简单的重载
    @Shadow public abstract void addMessage(Text message);

    @Unique
    private final MessageManager messageManager = new MessageManager(this);

    @Unique
    private boolean isInternalRedirect = false;

    /**
     * 修改变量逻辑
     */
    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/text/Text;)V", // 改为匹配单参数版本
            at = @At("HEAD"),
            argsOnly = true
    )
    public Text translex$interceptMessage(Text message) {
        if (isInternalRedirect) return message;

        Text processed = this.messageManager.compactMessage(message);

        // 如果返回 null，说明是高频静默期，我们返回一个标记文本
        return (processed == null) ? Text.literal("TRANSLEX_SILENT_CANCEL") : processed;
    }

    /**
     * 拦截并取消静默更新的显示
     */
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), cancellable = true)
    private void translex$cancelSilentUpdate(Text message, CallbackInfo ci) {
        // 关键：现在的参数只留 Text 和 CallbackInfo
        if (message != null && message.getString().equals("TRANSLEX_SILENT_CANCEL")) {
            ci.cancel();
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