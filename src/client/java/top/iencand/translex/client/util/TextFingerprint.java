package top.iencand.translex.client.util;

public class TextFingerprint {
    public static String getFingerprint(String input) {
        // 直接返回净化后的全文，实现“完全一样才折叠”
        return input.trim();
    }
}