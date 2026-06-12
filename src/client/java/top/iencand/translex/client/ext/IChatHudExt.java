package top.iencand.translex.client.ext;

import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.text.Text;
import java.util.List;

/**
 * 为 {@link net.minecraft.client.gui.hud.ChatHud} 提供的扩展接口。
 * 通过 Mixin 注入实现，允许 Translex 直接访问聊天 HUD 的内部状态。
 */
public interface IChatHudExt {
    /** 获取聊天消息列表（用于消息折叠/替换） */
    List<ChatHudLine> translex$getMessages();
    /** 强制刷新聊天 HUD 的渲染 */
    void translex$refreshMessages();
    /** 强制发送消息，绕过内部的拦截逻辑（如静默更新计数） */
    void translex$forceAddMessage(Text message);
}