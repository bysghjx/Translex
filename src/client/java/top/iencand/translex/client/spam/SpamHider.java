package top.iencand.translex.client.spam;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.web.ConsoleBroadcaster;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 聊天 Spam 过滤引擎。
 *
 * <p>源自 SkytilsMod {@code SpamHider.kt} 架构，适配 Fabric 1.21.1。
 * 拦截进入聊天栏的每条消息，依次检查所有用户定义的过滤规则，
 * 根据命中规则的状态决定消息的去向：
 *
 * <ul>
 *   <li>{@link SpamFilterData.FilterState#NORMAL} — 放行</li>
 *   <li>{@link SpamFilterData.FilterState#HIDDEN} — 丢弃</li>
 *   <li>{@link SpamFilterData.FilterState#SEPARATE} — 不进入聊天栏，转发至浮动 HUD（待实现）</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * SpamFilterData.FilterState result = SpamHider.getInstance().checkMessage(message);
 * if (result == SpamFilterData.FilterState.HIDDEN) {
 *     // 取消消息
 * }
 * }</pre>
 *
 * <h3>持久化</h3>
 * 过滤器存储在 {@code config/translex/spam_filters.json}，JSON 格式。
 */
public final class SpamHider {

    private static final Logger LOGGER = LoggerFactory.getLogger("TranslexSpam");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type FILTER_LIST_TYPE = new TypeToken<List<SpamFilterData.Filter>>() {}.getType();

    private final List<SpamFilterData.Filter> filters = new CopyOnWriteArrayList<>();
    private boolean enabled = true;

    // ================================================================
    // 单例
    // ================================================================

    private static final SpamHider INSTANCE = new SpamHider();

    /**
     * 获取全局单例。首次调用时自动从磁盘加载过滤规则。
     */
    public static SpamHider getInstance() {
        return INSTANCE;
    }

    private SpamHider() {
        loadFilters();
    }

    // ================================================================
    // 核心 API
    // ================================================================

    /**
     * 检查一条消息是否应被过滤。
     *
     * @param message 进入聊天栏的原始消息（含样式信息）
     * @return 处理结果 — NORMAL 放行，HIDDEN 丢弃，SEPARATE 转入浮动层
     */
    public SpamFilterData.FilterState checkMessage(Component message) {
        if (!enabled || message == null) {
            return SpamFilterData.FilterState.NORMAL;
        }

        // 无过滤器时快速返回（sentinel 不会被 check，因为 isInternalRedirect）
        if (filters.isEmpty()) {
            return SpamFilterData.FilterState.NORMAL;
        }

        final String plainText = message.getString().trim();

        // 跳过空消息
        if (plainText.isEmpty()) {
            return SpamFilterData.FilterState.NORMAL;
        }

        // 惰性计算：仅当存在 formatted=true 的 filter 时才做 § 码转换
        String formattedText = null;

        for (SpamFilterData.Filter filter : filters) {
            if (filter.state == SpamFilterData.FilterState.NORMAL) {
                continue;
            }

            final String input;
            if (filter.formatted) {
                if (formattedText == null) {
                    formattedText = toLegacyString(message);
                }
                input = formattedText;
            } else {
                input = plainText;
            }

            if (filter.matches(input)) {
                if (ModConfig.get().debug) {
                    String preview = plainText.length() > 60
                            ? plainText.substring(0, 57) + "..." : plainText;
                    ConsoleBroadcaster.broadcast("DEBUG",
                            "[Spam] \"" + preview + "\" → "
                            + filter.state + " (rule: " + filter.name + ")");
                }
                return filter.state;
            }
        }

        return SpamFilterData.FilterState.NORMAL;
    }

    // ================================================================
    // 过滤器 CRUD（供 Web API 使用）
    // ================================================================

    /** 返回不可变列表，防止外部直接修改 */
    public List<SpamFilterData.Filter> getFilters() {
        return Collections.unmodifiableList(filters);
    }

    /** 添加过滤器并持久化 */
    public void addFilter(SpamFilterData.Filter filter) {
        filters.add(filter);
        saveFilters();
    }

    /** 按索引删除并持久化 */
    public void removeFilter(int index) {
        if (index >= 0 && index < filters.size()) {
            filters.remove(index);
            saveFilters();
        }
    }

    /** 替换全部过滤器（批量保存时用）并持久化 */
    public void replaceAll(List<SpamFilterData.Filter> newFilters) {
        filters.clear();
        filters.addAll(newFilters);
        saveFilters();
    }

    // ---- 开关 ----

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // ================================================================
    // 持久化
    // ================================================================

    /**
     * 从磁盘加载过滤规则。
     */
    public void loadFilters() {
        Path file = getFilterFile();
        if (!file.toFile().exists()) {
            // 首次使用：添加一条演示规则，方便用户立刻验证功能
            addBuiltInDemoFilters();
            LOGGER.info("[Spam] 过滤器文件不存在，已创建演示规则（{} 条），"
                    + "在聊天中输入 spam-test 即可测试", filters.size());
            saveFilters(); // 立即持久化到磁盘
            return;
        }

        try (Reader reader = new InputStreamReader(
                new FileInputStream(file.toFile()), StandardCharsets.UTF_8)) {

            List<SpamFilterData.Filter> loaded = GSON.fromJson(reader, FILTER_LIST_TYPE);
            filters.clear();
            if (loaded != null) {
                for (SpamFilterData.Filter f : loaded) {
                    f.recompile(); // 重建 transient compiledPattern
                    filters.add(f);
                }
            }
            LOGGER.info("[Spam] 已加载 {} 条过滤规则", filters.size());
        } catch (Exception e) {
            LOGGER.error("[Spam] 加载过滤器失败", e);
        }
    }

    /**
     * 将当前过滤规则写回磁盘。
     */
    public void saveFilters() {
        Path file = getFilterFile();
        file.getParent().toFile().mkdirs();

        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file.toFile()), StandardCharsets.UTF_8)) {
            GSON.toJson(filters, writer);
            LOGGER.debug("[Spam] 已保存 {} 条过滤规则", filters.size());
        } catch (Exception e) {
            LOGGER.error("[Spam] 保存过滤器失败", e);
        }
    }

    /**
     * 添加内置演示过滤规则，帮助首次使用者快速验证功能。
     * 这些规则会在用户首次启动且无过滤器文件时自动创建。
     */
    private void addBuiltInDemoFilters() {
        filters.add(new SpamFilterData.Filter(
                "Demo: 浮动显示 spam-test 消息",
                SpamFilterData.FilterState.SEPARATE,
                "spam-test",
                SpamFilterData.FilterType.CONTAINS,
                false
        ));
        filters.add(new SpamFilterData.Filter(
                "Demo: 隐藏含 help-me 的消息",
                SpamFilterData.FilterState.HIDDEN,
                "help-me",
                SpamFilterData.FilterType.CONTAINS,
                false
        ));
    }

    /** 过滤器存储路径：{@code config/translex/spam_filters.json} */
    private Path getFilterFile() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("translex").resolve("spam_filters.json");
    }

    // ================================================================
    // Component → § 格式码字符串转换
    // ================================================================

    /**
     * 将 Minecraft {@link Component} 组件树转换为含 § 颜色码的字符串。
     *
     * <p>遍历 Component 树的每个带样式的片段，在每个片段前插入对应的 § 格式码。
     * 结果可直接用于 {@link SpamFilterData.FilterType#STARTSWITH startsWith}、
     * {@link SpamFilterData.FilterType#CONTAINS contains} 等匹配方式。
     *
     * <p>示例输出：{@code "§r§cThere are no enemies nearby!§r"}
     *
     * @param text Minecraft Component 组件
     * @return 带 § 格式码的传统样式字符串
     */
    public static String toLegacyString(Component text) {
        if (text == null) return "";

        StringBuilder sb = new StringBuilder();
        text.visit((style, string) -> {
            appendStyleCodes(sb, style);
            sb.append(string);
            return Optional.empty();
        }, Style.EMPTY);

        return sb.toString();
    }

    /**
     * 将 Style 中的颜色和格式标志写入 § 码。
     */
    private static void appendStyleCodes(StringBuilder sb, Style style) {
        TextColor color = style.getColor();
        if (color != null) {
            // 判断是否为 16 种标准颜色之一（ChatFormatting.BLACK ~ ChatFormatting.WHITE）
            ChatFormatting fmt = findFormattingByColor(color);
            if (fmt != null) {
                sb.append('§').append(fmt.getChar());
            } else {
                // RGB 颜色 → §x§R§R§G§G§B§B
                int rgb = color.getValue();
                sb.append("§x");
                String hex = String.format("%06X", rgb);
                for (char c : hex.toCharArray()) {
                    sb.append('§').append(c);
                }
            }
        }
        if (style.isBold())          sb.append("§l");
        if (style.isItalic())        sb.append("§o");
        if (style.isUnderlined())    sb.append("§n");
        if (style.isStrikethrough()) sb.append("§m");
        if (style.isObfuscated())    sb.append("§k");
    }

    /**
     * 在 16 种标准 Minecraft 颜色中查找与给定 TextColor 匹配的 Formatting。
     * 匹配失败（如 RGB 自定义色）返回 null，上游需回退到十六进制 §x 格式。
     */
    private static ChatFormatting findFormattingByColor(TextColor color) {
        for (ChatFormatting f : ChatFormatting.values()) {
            if (f.isColor()) {
                Integer colorValue = f.getColor();
                if (colorValue != null && colorValue == color.getValue()) {
                    return f;
                }
            }
        }
        return null;
    }
}
