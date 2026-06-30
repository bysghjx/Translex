package top.iencand.translex.client.listener;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import top.iencand.translex.client.util.TooltipKeyUtil;

import java.util.List;

/**
 * 悬停槽位追踪 + temporary 模式译文显示的"激活态"状态机。
 *
 * <p>背景：两个 tooltip 替换 Mixin（ScreenTooltipMixin / DrawContextTooltipMixin）
 * 都拿不到当前悬停的物理槽位（Slot），因此由 {@link ClientStateManager} 通过
 * {@code ScreenEvents.afterRender} 每帧读取 {@code HandledScreen.focusedSlot}，
 * 调用 {@link #updateHover} 把"当前悬停槽位 + 其 loreHash"维护成静态状态，供 Mixin 间接读取。</p>
 *
 * <p>#3 行为：temporary 模式按 P 翻译后，译文只在"翻译时悬停的那个 Slot"上显示。
 * 一旦鼠标离开该槽位即失活；移回同一槽位需重按 P 才重新激活。
 * 通过"槽位号 + loreHash 双重校验"门控，与异步翻译完全解耦（激活态在按 P 当帧同步设置）。</p>
 *
 * <p>permanent 模式不走本门控（永久预设，命中即显示）。</p>
 */
public final class HoverSlotTracker {

    private HoverSlotTracker() {}

    // -------- 当前悬停（每帧更新） --------
    private static volatile int hoverSlotId = -1;
    private static volatile String hoverLoreHash = null;

    // -------- temporary 激活态（按 P 时设置） --------
    private static volatile int activeSlotId = -1;
    private static volatile String activeLoreHash = null;

    // -------- loreHash 复用优化（避免每帧重算 SHA-256） --------
    private static int cachedSlotId = -2;
    private static ItemStack cachedStack = null;
    private static String cachedLoreHash = null;

    /**
     * 每帧更新当前悬停槽位状态。由 afterRender 回调驱动。
     * 仅当槽位号或物品引用变化时才重算 loreHash（稳态停留零额外开销）。
     *
     * @param slotId 当前 focusedSlot.id；无悬停传 -1
     * @param stack  当前 focusedSlot 的物品；无悬停/空槽传 null 或空
     */
    public static void updateHover(int slotId, ItemStack stack) {
        String loreHash;
        if (slotId < 0 || stack == null || stack.isEmpty()) {
            loreHash = null;
            cachedSlotId = -2;
            cachedStack = null;
            cachedLoreHash = null;
        } else if (slotId == cachedSlotId && stack == cachedStack && cachedLoreHash != null) {
            // 槽位号 + 物品引用都没变 → 复用上次哈希
            loreHash = cachedLoreHash;
        } else {
            loreHash = computeLoreHash(stack);
            cachedSlotId = slotId;
            cachedStack = stack;
            cachedLoreHash = loreHash;
        }

        hoverSlotId = slotId;
        hoverLoreHash = loreHash;

        // 失活判定：离开激活槽位（变别的槽位或 -1）→ 立即清激活态 → "移回需重按 P"
        if (activeSlotId != -1 && slotId != activeSlotId) {
            deactivate();
        }
    }

    /**
     * 激活 temporary 译文显示（按 P 翻译/命中时调用，仅 temporary 模式）。
     * 记录当前悬停槽位号与其 loreHash 作为显示门控依据。
     */
    public static void activate(int slotId, String loreHash) {
        activeSlotId = slotId;
        activeLoreHash = loreHash;
    }

    public static void deactivate() {
        activeSlotId = -1;
        activeLoreHash = null;
    }

    /**
     * temporary 门控：当前悬停槽位是否处于激活态且与给定 loreHash 一致。
     * 双重校验：槽位号匹配 + loreHash 匹配（防换格串台 / 防同格物品变了串台）。
     */
    public static boolean isActiveForCurrentHover(String currentLoreHash) {
        return activeSlotId != -1
                && hoverSlotId == activeSlotId
                && activeLoreHash != null
                && activeLoreHash.equals(currentLoreHash);
    }

    /** 当前悬停槽位的 loreHash（供按 P 时取值传给 activate）。 */
    public static String getHoverLoreHash() {
        return hoverLoreHash;
    }

    /** 当前悬停槽位号。 */
    public static int getHoverSlotId() {
        return hoverSlotId;
    }

    /** 进屏 / 关屏时全部清空，防激活态跨屏泄漏。 */
    public static void clearAll() {
        hoverSlotId = -1;
        hoverLoreHash = null;
        activeSlotId = -1;
        activeLoreHash = null;
        cachedSlotId = -2;
        cachedStack = null;
        cachedLoreHash = null;
    }

    /** 用 getTooltipFromItem 取完整 tooltip 行，经 TooltipKeyUtil 规范化算 loreHash（与替换查找同源）。 */
    private static String computeLoreHash(ItemStack stack) {
        try {
            Minecraft mc = Minecraft.getInstance();
            List<Component> lines = Screen.getTooltipFromItem(mc, stack);
            return TooltipKeyUtil.loreHash(lines);
        } catch (Exception e) {
            return null;
        }
    }
}
