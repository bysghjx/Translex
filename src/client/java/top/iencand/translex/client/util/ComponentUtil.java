package top.iencand.translex.client.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** 聊天/工具提示组件转换工具。 */
public final class ComponentUtil {

    private ComponentUtil() {
    }

    /** 将 {@link FormattedCharSequence} 转换回 {@link Component}，保留样式结构。 */
    public static Component fromFormattedCharSequence(FormattedCharSequence ordered) {
        net.minecraft.network.chat.MutableComponent result = Component.empty();
        StringBuilder segment = new StringBuilder();
        Style[] currentStyle = {Style.EMPTY};

        ordered.accept((index, style, codePoint) -> {
            if (!style.equals(currentStyle[0]) && segment.length() > 0) {
                result.append(Component.literal(segment.toString()).setStyle(currentStyle[0]));
                segment.setLength(0);
            }
            currentStyle[0] = style;
            segment.appendCodePoint(codePoint);
            return true;
        });

        if (segment.length() > 0) {
            result.append(Component.literal(segment.toString()).setStyle(currentStyle[0]));
        }
        return result;
    }
}
