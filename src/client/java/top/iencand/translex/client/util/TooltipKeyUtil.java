package top.iencand.translex.client.util;

import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 物品工具提示缓存键的唯一生成来源。
 *
 * <p>组合键格式：{@code itemId + "#" + loreHash}
 * <ul>
 *   <li>{@code itemId} —— SkyBlock 内部 ID（{@code ExtraAttributes.id}），无则回退原版注册 ID</li>
 *   <li>{@code loreHash} —— 对 lore 文本规范化后取 SHA-256 前 16 个十六进制字符</li>
 * </ul>
 *
 * <p>设计要点：物品翻译的「存入」（按 P 键时）与「查找」（Mixin 替换前）
 * 必须经由本类的同一套规范化逻辑，否则哈希不一致会导致缓存永远 miss。
 * temporary / permanent 两种模式、两个 tooltip Mixin、ClientStateManager
 * 全部调用本类，保证键算法一致。
 *
 * <p>键中包含 lore 哈希解决了「同 ID（或同原版物品）但 lore 不同的两个物品
 * 互相串台」的问题：lore 相同仍共享缓存（优点），lore 不同则各存各的。
 */
public final class TooltipKeyUtil {

    private TooltipKeyUtil() {}

    /** 颜色码正则，与 TranslationCache / TranslationCacheManager 保持一致。 */
    private static final String COLOR_CODE = "§[0-9a-fk-or]";

    /**
     * 返回物品的稳定标识：SkyBlock ID 优先，否则回退原版注册 ID。
     * 统一 temporary 与 permanent 两种模式的 itemId 兜底逻辑。
     *
     * @return 物品 ID，stack 为空时返回 null
     */
    public static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String sbId = ItemIdExtractor.extractSkyBlockItemId(stack);
        if (sbId != null && !sbId.isEmpty()) return sbId;
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * 生成完整的工具提示缓存键 {@code itemId#loreHash}。
     *
     * @param stack         物品
     * @param tooltipLines  当前完整 tooltip 行（含第 0 行物品名）
     * @return 组合键，stack 为空时返回 null
     */
    public static String buildKey(ItemStack stack, List<Component> tooltipLines) {
        String id = itemId(stack);
        if (id == null) return null;
        return id + "#" + loreHash(tooltipLines);
    }

    /**
     * 对 lore 文本规范化后计算哈希。
     *
     * <p>规范化：去色码 → 每行 trim + 压缩空白 → <b>跳过第 0 行（物品名）</b>
     * → 用 {@code \n} 连接。跳过物品名是因为替换逻辑本就从第 1 行起替换 lore，
     * 且物品名常含数量/染色等易变内容。
     *
     * @param tooltipLines 完整 tooltip 行（含第 0 行物品名）；null/单行时哈希空串
     * @return SHA-256 的前 16 个十六进制字符
     */
    public static String loreHash(List<Component> tooltipLines) {
        StringBuilder sb = new StringBuilder();
        if (tooltipLines != null) {
            for (int i = 1; i < tooltipLines.size(); i++) { // 跳过第 0 行（物品名）
                Component line = tooltipLines.get(i);
                if (line == null) continue;
                String norm = line.getString()
                        .replaceAll(COLOR_CODE, "")
                        .trim()
                        .replaceAll("\\s+", " ");
                if (i > 1) sb.append('\n');
                sb.append(norm);
            }
        }
        return sha256Hex16(sb.toString());
    }

    /** 计算 SHA-256 并返回前 16 个十六进制字符（64 bit，碰撞概率可忽略）。 */
    private static String sha256Hex16(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) { // 8 字节 = 16 hex 字符
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            // SHA-256 在标准 JDK 必定存在；万一不可用则退回 hashCode 兜底
            return Integer.toHexString(input.hashCode());
        }
    }
}
