package top.iencand.translex.client.spam;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.web.ConsoleBroadcaster;

import java.util.ArrayList;
import java.util.List;

/**
 * Spam 消息浮动 HUD 渲染器。
 *
 * <p>从 SkytilsMod {@code SpamHider.SpamGuiElement} 移植，适配 Fabric 1.21.1。
 * 当 SpamHider 过滤规则设为 {@link SpamFilterData.FilterState#SEPARATE} 时，
 * 消息不进入聊天栏，而是被转发到此渲染器，在屏幕上以动画形式浮动显示后自动消失。
 *
 * <h3>动画</h3>
 * <ul>
 *   <li>滑入阶段（0–500ms）：消息从屏幕右侧滑入，sin 曲线缓动</li>
 *   <li>稳定阶段（500–3500ms）：消息完全可见，停留在锚点位置</li>
 *   <li>滑出阶段（3500–4000ms）：消息向右侧滑出消失</li>
 * </ul>
 *
 * <h3>位置</h3>
 * 默认在屏幕右侧（{@code anchorX = 0.70}），上方（{@code anchorY = 0.18}）。
 * 多条消息从上到下堆叠，最新消息在最上方。
 */
public final class SpamOverlayRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("TranslexOverlay");
    private static final SpamOverlayRenderer INSTANCE = new SpamOverlayRenderer();

    // ---------- 动画参数 ----------
    /** 滑入动画时长 (ms) */
    private static final long SLIDE_IN_MS = 500;
    /** 稳定显示时长 (ms) */
    private static final long DISPLAY_MS = 3500;
    /** 滑出动画时长 (ms) */
    private static final long SLIDE_OUT_MS = 500;
    /** 总生命周期 = 滑入 + 显示 + 滑出 */
    private static final long LIFETIME_MS = SLIDE_IN_MS + DISPLAY_MS + SLIDE_OUT_MS;
    /** 滑出开始时间点 = 滑入 + 显示 */
    private static final long SLIDE_OUT_START_MS = SLIDE_IN_MS + DISPLAY_MS;

    // ---------- 显示参数 ----------
    /** 最大同时显示的消息数量 */
    private static final int MAX_MESSAGES = 20;
    /** 消息行间距 (px) */
    private static final int LINE_SPACING = 14;

    // ---------- 实例字段 ----------

    /** 消息队列（按添加时间排序） */
    private final List<SpamMessage> messages = new ArrayList<>();
    /** 是否已向 HudRenderCallback 注册 */
    private boolean registered = false;
    /** 渲染计数器，用于 debug 时限制日志频率 */
    private int debugFrameCounter = 0;

    /**
     * 水平锚点：屏幕宽度的比例（0.0 = 左侧，1.0 = 右侧）。
     * 消息右对齐于此 X 坐标。靠近 1 = 右下角。
     */
    private float anchorX = 0.97f;

    /**
     * 垂直锚点：屏幕高度的比例（0.0 = 顶部，1.0 = 底部）。
     * 第一条（最新）消息的 Y 坐标。靠近 1 = 屏幕底部。
     */
    private float anchorY = 0.70f;

    // ================================================================
    // 单例
    // ================================================================

    public static SpamOverlayRenderer getInstance() {
        return INSTANCE;
    }

    private SpamOverlayRenderer() {}

    // ================================================================
    // 公共 API
    // ================================================================

    /**
     * 初始化渲染器，向 HUD 渲染回调注册。
     * 幂等 — 重复调用不会重复注册。
     * 应在客户端初始化阶段调用（如 {@code top.iencand.translex.client.TranslexClient#onInitializeClient()}）。
     */
    public void init() {
        if (!registered) {
            // 26.x：HudRenderCallback 已移除，改用 HudElementRegistry 在 CHAT 元素之后附加自定义 HUD 元素
            HudElementRegistry.attachElementAfter(
                    VanillaHudElements.CHAT,
                    Identifier.fromNamespaceAndPath("translex", "spam_overlay"),
                    this::onHudRender
            );
            registered = true;
            LOGGER.info("[Overlay] HUD 元素已注册");
            if (ModConfig.get().debug) {
                ConsoleBroadcaster.broadcast("INFO",
                        "[Overlay] HUD element registered — anchor=("
                        + anchorX + "," + anchorY + ")");
            }
        }
    }

    /**
     * 添加一条消息到浮动显示队列。
     * 由 SpamHider 管道在 SEPARATE 状态命中时调用。
     *
     * @param text 原始聊天消息（含样式信息）
     */
    public void addMessage(Component text) {
        if (text == null) return;
        messages.add(new SpamMessage(text));
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }

        LOGGER.info("[Overlay] addMessage: \"{}\" (queue size={})",
                truncate(text.getString()), messages.size());
        if (ModConfig.get().debug) {
            ConsoleBroadcaster.broadcast("DEBUG",
                    "[Overlay] addMessage queue=" + messages.size()
                    + " text=\"" + truncate(text.getString()) + "\"");
        }
    }

    // ================================================================
    // 渲染回调
    // ================================================================

    /**
     * 每帧 HUD 渲染回调。
     * 遍历消息队列，清理过期消息，渲染剩余消息的动画。
     */
    private void onHudRender(GuiGraphicsExtractor context, DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        if (client.options.hideGui) return; // F1 模式下不渲染

        if (messages.isEmpty()) return;

        final long now = System.currentTimeMillis();
        final int screenW = client.getWindow().getGuiScaledWidth();
        final int screenH = client.getWindow().getGuiScaledHeight();
        final int baseX = (int) (screenW * anchorX);
        final int baseY = (int) (screenH * anchorY);

        // 每隔 20 帧输出一次 debug 日志（避免刷屏 SSE）
        if (ModConfig.get().debug && (debugFrameCounter++ % 20 == 0)) {
            ConsoleBroadcaster.broadcast("DEBUG",
                    "[Overlay] render screen=" + screenW + "x" + screenH
                    + " base=(" + baseX + "," + baseY + ") msgs=" + messages.size());
        }

        // 倒序遍历：最新消息（队列末尾）渲染在最上方
        int line = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            SpamMessage msg = messages.get(i);
            long elapsed = now - msg.createTime;

            // 生命周期结束 → 移除
            if (elapsed > LIFETIME_MS) {
                messages.remove(i);
                continue;
            }

            // 计算 sin 缓动动画偏移量（0 = 原位，1 = 屏幕右外侧）
            double anim = computeAnimationOffset(elapsed);

            // 剥离 Translex 翻译按钮，只显示原始消息
            Component displayText = stripButton(msg.text);
            net.minecraft.util.FormattedCharSequence ordered = displayText.getVisualOrderText();
            int textWidth = client.font.width(ordered);
            String plainText = displayText.getString();

            // 右对齐：文本结束于 baseX。动画期间向右偏移产生滑入/滑出
            int animRange = (screenW - baseX) + textWidth + 30;
            int drawX = baseX - textWidth + (int) (anim * animRange);
            int drawY = baseY - line * LINE_SPACING;

            // FormattedCharSequence 版本 — 每个字符保留其原始颜色
            context.text(
                    client.font,
                    ordered,
                    drawX,
                    drawY,
                    0xFFFFFFFF,
                    true
            );
            line++;
        }
    }

    // ================================================================
    // 动画计算
    // ================================================================

    /**
     * 计算当前时间点的动画偏移量。
     *
     * <p>使用 sin 曲线实现平滑缓动：
     * <pre>
     *   0ms             500ms            4000ms
     *    |-- 滑入 (1→0) --|-- 稳定 (0) --|-- 滑出 (0→1) --|
     * </pre>
     *
     * @param elapsed 自消息创建以来经过的毫秒数
     * @return 0.0（原位）到 1.0（完全偏移到屏幕右侧之外）
     */
    private static double computeAnimationOffset(long elapsed) {
        double raw;
        if (elapsed < SLIDE_IN_MS) {
            raw = 1.0 - (double) elapsed / SLIDE_IN_MS;
        } else if (elapsed > SLIDE_OUT_START_MS) {
            raw = (double) (elapsed - SLIDE_OUT_START_MS) / SLIDE_OUT_MS;
        } else {
            return 0.0;
        }

        double degrees = raw * 90.0 + 90.0;
        return 1.0 - Math.sin(Math.toRadians(degrees));
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /**
     * 剥离 Translex 附加的翻译按钮（[翻译] / [T] / [Translate] + 空格），
     * 返回原始聊天消息 Text，在浮动 HUD 中不显示按钮。
     */
    private static Component stripButton(Component text) {
        var siblings = text.getSiblings();
        if (siblings.size() < 3) return text;

        // NORMAL 模式: [翻译] <space> <message>
        String first = siblings.get(0).getString();
        if (first.equals("[翻译]") || first.equals("[T]") || first.equals("[Translate]")) {
            return siblings.get(2); // 跳过按钮 + 空格，返回原始消息
        }

        // COMPACT 模式: <message> <space> [T]
        String last = siblings.get(siblings.size() - 1).getString();
        if (last.equals("[T]") || last.equals("[翻译]") || last.equals("[Translate]")) {
            return siblings.get(0); // 原始消息在最前
        }

        return text;
    }

    /** 截断字符串用于日志显示 */
    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() > 50 ? s.substring(0, 47) + "..." : s;
    }

    // ================================================================
    // 内部消息类
    // ================================================================

    private static final class SpamMessage {
        final Component text;
        final long createTime;

        SpamMessage(Component text) {
            this.text = text;
            this.createTime = System.currentTimeMillis();
        }
    }
}
