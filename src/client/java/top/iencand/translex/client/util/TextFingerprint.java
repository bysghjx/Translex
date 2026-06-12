package top.iencand.translex.client.util;

/**
 * 文本指纹生成器（严格模式）。
 * 直接返回净化后的全文，只有完全一致的消息才会被折叠。
 */
public class TextFingerprint {
    /** 生成文本指纹：去除首尾空白后原文返回 */
    public static String getFingerprint(String input) {
        return input.trim();
    }
}