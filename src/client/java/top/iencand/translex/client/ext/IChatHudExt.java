package top.iencand.translex.client.ext;

import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.text.Text;
import java.util.List;

public interface IChatHudExt {
    List<ChatHudLine> translex$getMessages();
    void translex$refreshMessages();
    // 新增：强制发送消息（绕过拦截逻辑）
    void translex$forceAddMessage(Text message);
}