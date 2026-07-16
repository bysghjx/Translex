package top.iencand.translex.client.util;

/**
 * 采集模式标志（ThreadLocal）。
 *
 * <p>{@link LoreHarvester} 抓取原文 tooltip 时设为 true，
 * {@link top.iencand.translex.client.mixin.ScreenTooltipMixin} 检测到则跳过翻译替换，
 * 保留原始 tooltip（否则会拿到缓存里的译文）。</p>
 *
 * <p>用独立类而非 Mixin 内静态方法：Mixin 类不允许非 private 的 static 方法，
 * 跨类调用标志必须放在普通工具类里。</p>
 */
public final class HarvestFlag {
    private static final ThreadLocal<Boolean> HARVESTING = ThreadLocal.withInitial(() -> false);

    private HarvestFlag() {}

    public static void setHarvesting(boolean harvesting) {
        HARVESTING.set(harvesting);
    }

    public static boolean isHarvesting() {
        return HARVESTING.get();
    }
}
