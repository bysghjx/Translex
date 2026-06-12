package top.iencand.translex.client.spam;

import com.google.gson.annotations.Expose;

import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/**
 * SpamHider 过滤器的数据模型。
 *
 * <p>源自 SkytilsMod {@code SpamHider.Filter} 的架构，适配 Fabric 1.21.1。
 * 每个 Filter 包含名称、状态、匹配模式、匹配类型等信息。
 *
 * <h3>匹配模式 (FilterType)</h3>
 * <ul>
 *   <li>{@link FilterType#STARTSWITH} — 前缀匹配（输入以 pattern 开头）</li>
 *   <li>{@link FilterType#CONTAINS} — 包含匹配（输入包含 pattern）</li>
 *   <li>{@link FilterType#REGEX} — 正则匹配（pattern 作为正则表达式匹配整个输入）</li>
 * </ul>
 *
 * <h3>过滤状态 (FilterState)</h3>
 * <ul>
 *   <li>{@link FilterState#NORMAL} — 放行（消息正常显示）</li>
 *   <li>{@link FilterState#HIDDEN} — 隐藏（消息完全不显示）</li>
 *   <li>{@link FilterState#SEPARATE} — 分离（消息不显示在聊天栏，改为在浮动 HUD 中展示）</li>
 * </ul>
 */
public final class SpamFilterData {

    private SpamFilterData() { /* 工具类，禁止实例化 */ }

    // ================================================================
    // 匹配类型枚举
    // ================================================================

    /**
     * 过滤器的匹配方式。
     */
    public enum FilterType {
        /** 消息以 pattern 开头时命中 */
        STARTSWITH((input, pattern) -> input.startsWith(pattern.pattern())),

        /** 消息包含 pattern 时命中 */
        CONTAINS(  (input, pattern) -> input.contains(pattern.pattern())),

        /** message 完全匹配正则 pattern 时命中 */
        REGEX(     (input, pattern) -> pattern.matcher(input).matches());

        private final BiPredicate<String, Pattern> method;

        FilterType(BiPredicate<String, Pattern> method) {
            this.method = method;
        }

        /**
         * 用此匹配方式测试输入。
         * @param input  待测字符串（已剥离颜色码的纯文本，或带 § 格式码的文本）
         * @param pattern 编译后的匹配模式
         * @return 是否命中
         */
        public boolean check(String input, Pattern pattern) {
            return method.test(input, pattern);
        }
    }

    // ================================================================
    // 过滤状态枚举
    // ================================================================

    /**
     * 过滤器对消息的处理方式。
     */
    public enum FilterState {
        /** 不处理，消息正常显示 */
        NORMAL,
        /** 完全隐藏，消息不进入聊天栏也不进入浮动层 */
        HIDDEN,
        /** 从聊天栏移除，改在浮动 HUD 中显示（未来功能） */
        SEPARATE
    }

    // ================================================================
    // 过滤器数据类
    // ================================================================

    /**
     * 单条过滤规则。
     *
     * <p>Gson 序列化时通过 {@link Expose} 控制字段可见性，
     * {@code compiledPattern} 为 transient，不参与序列化，
     * 通过 {@link #getPattern()} 惰性编译。
     */
    public static final class Filter {
        /** 过滤器显示名称（用户可自定义） */
        public String name = "";

        /** 过滤状态：NORMAL / HIDDEN / SEPARATE */
        public FilterState state = FilterState.NORMAL;

        /**
         * 匹配模式的原始字符串。
         * <ul>
         *   <li>{@link FilterType#STARTSWITH} / {@link FilterType#CONTAINS} —
         *       编译为 {@link Pattern#LITERAL} 模式，特殊字符原文匹配</li>
         *   <li>{@link FilterType#REGEX} — 编译为标准 Java 正则</li>
         * </ul>
         */
        public String patternString = "";

        /** 匹配方式 */
        public FilterType type = FilterType.CONTAINS;

        /**
         * 是否按格式化文本（含 § 颜色码）匹配。
         * {@code true} 时对带 § 格式码的文本做匹配，
         * {@code false} 时对纯文本（{@link net.minecraft.text.Text#getString()}）做匹配。
         */
        public boolean formatted = false;

        // ---- 运行时字段 ----

        /** 编译后的 Pattern，惰性初始化，不参与 JSON 序列化 */
        private transient Pattern compiledPattern;

        /** 无参构造器（供 Gson 反序列化使用） */
        public Filter() {}

        /**
         * 便捷构造器 — 创建一个新的过滤规则。
         */
        public Filter(String name, FilterState state, String patternString,
                      FilterType type, boolean formatted) {
            this.name = name;
            this.state = state;
            this.patternString = patternString;
            this.type = type;
            this.formatted = formatted;
        }

        /**
         * 获取编译后的匹配模式。首次调用时编译，后续返回缓存值。
         */
        public Pattern getPattern() {
            if (compiledPattern == null) {
                recompile();
            }
            return compiledPattern;
        }

        /**
         * 强制重新编译模式（修改 patternString 或 type 后调用）。
         */
        public void recompile() {
            if (patternString == null || patternString.isEmpty()) {
                compiledPattern = Pattern.compile(""); // 空模式不匹配任何内容
                return;
            }
            int flags = (type == FilterType.REGEX) ? 0 : Pattern.LITERAL;
            compiledPattern = Pattern.compile(patternString, flags);
        }

        /**
         * 测试输入是否命中此过滤规则。
         *
         * @param input 待测字符串（纯文本或带 § 格式码）
         * @return 是否命中
         */
        public boolean matches(String input) {
            if (input == null || input.isEmpty()) return false;
            try {
                return type.check(input, getPattern());
            } catch (Exception e) {
                // 正则异常时静默放行（避免因一条错误规则导致整个过滤链崩掉）
                return false;
            }
        }
    }
}
