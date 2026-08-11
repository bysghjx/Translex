package top.iencand.translex.client.translate.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 可变术语词典（附魔名 + 专有词汇）：预生成 + 运行时补缺 + 持久化。
 *
 * <p>解决附魔段高重复翻译问题：191 唯一附魔，89.9% 重复率。
 * 预生成常见附魔翻译，运行时遇到新附魔翻译后 learn() 存入，下次命中。</p>
 *
 * <h3>查找流程</h3>
 * <ol>
 *   <li>预生成 Map（Minecraft 原版附魔官方翻译 + Hypixel 社区翻译）</li>
 *   <li>运行时 Map（learn 存入，持久化到 term_dict.json）</li>
 *   <li>合并查找，按词边界正则替换</li>
 * </ol>
 *
 * <h3>模板应用</h3>
 * {@link #applyToTemplate(String, String)} 支持 SN（{@code <sN>TEXT</sN>}）和
 * TSP（{@code [[ID||TEXT]]} / {@code [[ID:HASH||TEXT]]}）两种格式，只替换标签内
 * 的可见文本，保留 ID/标签结构。{@link #hasEnglishRemaining(String, String)}
 * 检查替换后是否还有英文（决定是否短路不发 AI）。</p>
 *
 * <p>单例：{@link #get()}。静态方法委托单例，方便 TranslationCacheManager 复用。</p>
 */
public final class TermDictionary {
    private static final Logger LOGGER = LoggerFactory.getLogger("Translex/TermDict");
    private static volatile TermDictionary instance;

    public static TermDictionary get() {
        if (instance == null) {
            synchronized (TermDictionary.class) {
                if (instance == null) instance = new TermDictionary();
            }
        }
        return instance;
    }

    private final Map<String, String> terms = new ConcurrentHashMap<>();
    private volatile List<Pattern> patterns = new ArrayList<>();
    private volatile boolean dirty = false;
    private File file;

    private TermDictionary() {
        loadPreset();
    }

    // ================================================================
    // 预生成词典
    // ================================================================

    private static final Map<String, String> PRESET = Map.ofEntries(
            // Minecraft 原版附魔（官方翻译）
            Map.entry("Aqua Affinity", "水下速掘"),
            Map.entry("Bane of Arthropods", "节肢杀手"),
            Map.entry("Depth Strider", "深海探索者"),
            Map.entry("Efficiency", "效率"),
            Map.entry("Feather Falling", "摔落保护"),
            Map.entry("Fire Aspect", "火焰附加"),
            Map.entry("Fortune", "时运"),
            Map.entry("Knockback", "击退"),
            Map.entry("Looting", "抢夺"),
            Map.entry("Protection", "保护"),
            Map.entry("Respiration", "水下呼吸"),
            Map.entry("Sharpness", "锋利"),
            Map.entry("Smite", "亡灵杀手"),
            Map.entry("Thorns", "荆棘"),

            // Hypixel SkyBlock 特有附魔（社区翻译，运行时学习会纠正）
            Map.entry("Champion", "冠军"),
            Map.entry("Chimera", "奇美拉"),
            Map.entry("Cleave", "横扫"),
            Map.entry("Compact", "紧凑"),
            Map.entry("Critical", "暴击"),
            Map.entry("Cubism", "立方"),
            Map.entry("Divine Gift", "神圣恩赐"),
            Map.entry("Drain", "抽取"),
            Map.entry("Ender Slayer", "末影杀手"),
            Map.entry("Execute", "处决"),
            Map.entry("Experience", "经验"),
            Map.entry("First Strike", "先攻"),
            Map.entry("Flowstate", "心流"),
            Map.entry("Forest Pledge", "森林誓约"),
            Map.entry("Giant Killer", "巨人杀手"),
            Map.entry("Gravity", "重力"),
            Map.entry("Growth", "生长"),
            Map.entry("Hecatomb", "百牛祭"),
            Map.entry("Ice Cold", "冰冷"),
            Map.entry("Impaling", "穿刺"),
            Map.entry("Lapidary", "宝石匠"),
            Map.entry("Legion", "军团"),
            Map.entry("Lethality", "致命"),
            Map.entry("Luck", "幸运"),
            Map.entry("Mana Steal", "法力窃取"),
            Map.entry("Paleontologist", "古生物学家"),
            Map.entry("Prismatic", "棱彩"),
            Map.entry("Prosecute", "惩戒"),
            Map.entry("Pyroclasm", "火山碎屑"),
            Map.entry("Rejuvenate", "复苏"),
            Map.entry("Scavenger", "拾荒"),
            Map.entry("Scuba", "水肺"),
            Map.entry("Smarty Pants", "聪明裤"),
            Map.entry("Smoldering", "阴燃"),
            Map.entry("Strong Mana", "强效法力"),
            Map.entry("Tabasco", "塔巴斯科"),
            Map.entry("Thunderbolt", "雷电"),
            Map.entry("Thunderlord", "雷霆"),
            Map.entry("Titan Killer", "泰坦杀手"),
            Map.entry("Ultimate Wise", "终极智慧"),
            Map.entry("Vampirism", "吸血"),
            Map.entry("Venomous", "剧毒"),
            Map.entry("Vicious", "凶残")
    );

    private void loadPreset() {
        // 先加载 SkyBlockTerm（属性名 + stat 名：Strength->力量, Breaking Power->破坏力 等）
        for (SkyBlockTerm term : SkyBlockTerm.VALUES) {
            terms.put(term.getEn(), term.getZh());
        }
        // 再加载附魔 + 专有词汇预设
        for (Map.Entry<String, String> e : PRESET.entrySet()) {
            terms.put(e.getKey(), e.getValue());
        }
        rebuildPatterns();
    }

    // ================================================================
    // 查询 / 学习
    // ================================================================

    public String translate(String en) {
        if (en == null) return null;
        return terms.get(en);
    }

    /** 运行时学习：存入词条 + 重建 pattern（不覆盖已有翻译除非显式调用）。 */
    public void learn(String en, String zh) {
        if (en == null || zh == null || en.isBlank() || zh.isBlank()) return;
        if (en.equals(zh)) return;
        // 不覆盖已有翻译（避免学到错误翻译覆盖预生成）
        if (terms.containsKey(en)) return;
        terms.put(en, zh);
        dirty = true;
        rebuildPatterns();
        LOGGER.debug("TermDict learned: {} -> {}", en, zh);
    }

    /** 对纯文本应用词典（词边界替换，多词优先）。 */
    public String apply(String text) {
        if (text == null || text.isEmpty()) return text;
        String r = text;
        List<Pattern> ps = patterns;
        for (Pattern p : ps) {
            Matcher m = p.matcher(r);
            if (m.find()) {
                StringBuffer sb = new StringBuffer();
                m.reset();
                while (m.find()) {
                    String zh = terms.get(m.group());
                    if (zh != null) {
                        m.appendReplacement(sb, Matcher.quoteReplacement(zh));
                    }
                }
                m.appendTail(sb);
                r = sb.toString();
            }
        }
        return r;
    }

    // ================================================================
    // 模板应用（SN + TSP）
    // ================================================================

    private static final Pattern SN_TAG = Pattern.compile("<s(\\d+)>(.*?)</s\\1>", Pattern.DOTALL);
    // [[ID||TEXT]] 或 [[ID:HASH||TEXT]]，content 处理转义（\] \\）
    private static final Pattern TSP_TAG = Pattern.compile(
            "\\[\\[(\\d+(?::[0-9a-fA-F]+)?)\\|\\|((?:\\\\.|[^\\\\\\]])*?)\\]\\]");

    /**
     * 对带标签的模板应用词典，只替换标签内可见文本，保留 ID/标签结构。
     *
     * @param template 模板（SN {@code <sN>..</sN>} 或 TSP {@code [[ID||..]]}）
     * @param formatId "SN" / "TSP" / "HYBRID"
     */
    public String applyToTemplate(String template, String formatId) {
        if (template == null) return null;
        if (isTsp(formatId)) {
            return applyToTspTemplate(template);
        }
        return applyToSnTemplate(template);
    }

    private String applyToSnTemplate(String template) {
        Matcher m = SN_TAG.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String content = m.group(2);
            String glossed = Matcher.quoteReplacement(apply(content));
            m.appendReplacement(sb, "<s" + m.group(1) + ">" + glossed + "</s" + m.group(1) + ">");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String applyToTspTemplate(String template) {
        Matcher m = TSP_TAG.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String id = m.group(1);
            String content = m.group(2);
            String glossed = Matcher.quoteReplacement(apply(content));
            m.appendReplacement(sb, "[[" + id + "||" + glossed + "]]");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean isTsp(String formatId) {
        return TranslationFormatRegistry.usesTspSyntax(formatId);
    }

    /**
     * 检查模板经词典替换后是否仍含英文（决定是否需要 AI）。
     * 去掉所有标签/结构后检查剩余文本是否含英文字母。
     */
    public boolean hasEnglishRemaining(String template, String formatId) {
        if (template == null || template.isEmpty()) return false;
        String stripped = template;
        if (isTsp(formatId)) {
            // 去掉 [[ID:HASH|| 和 ]]，保留 content
            stripped = stripped.replaceAll("\\[\\[\\d+(?::[0-9a-fA-F]+)?\\|\\|", "").replace("]]", "");
        } else {
            stripped = stripped.replaceAll("</?s\\d+>", "");
        }
        // 排除占位符 {0} {1}（不算英文）
        stripped = stripped.replaceAll("\\{\\d+\\}", "");
        return Pattern.compile("[a-zA-Z]").matcher(stripped).find();
    }

    // ================================================================
    // 持久化
    // ================================================================

    public void load(File f) {
        this.file = f;
        if (f == null || !f.exists()) return;
        try {
            String json = Files.readString(f.toPath());
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            int count = 0;
            for (Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    terms.put(e.getKey(), e.getValue().getAsJsonPrimitive().getAsString());
                    count++;
                }
            }
            rebuildPatterns();
            LOGGER.info("TermDict loaded {} runtime terms from {}", count, f.getName());
        } catch (Exception e) {
            LOGGER.warn("TermDict load failed: {}", e.getMessage());
        }
    }

    /** 持久化运行时学习的词条（不存预生成，避免冗余）。只在 dirty 时写。 */
    public void save() {
        if (file == null || !dirty) return;
        try {
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, String> e : terms.entrySet()) {
                if (!PRESET.containsKey(e.getKey())) {
                    obj.addProperty(e.getKey(), e.getValue());
                }
            }
            Files.createDirectories(file.toPath().getParent());
            Files.writeString(file.toPath(), obj.toString());
            dirty = false;
        } catch (Exception e) {
            LOGGER.warn("TermDict save failed: {}", e.getMessage());
        }
    }

    // ================================================================
    // pattern 重建（多词优先，避免短词先匹配破坏长词）
    // ================================================================

    private void rebuildPatterns() {
        List<Pattern> p = terms.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .map(e -> Pattern.compile("\\b" + Pattern.quote(e.getKey()) + "\\b"))
                .collect(Collectors.toList());
        patterns = p;
    }

    public int size() { return terms.size(); }
    public int presetSize() { return PRESET.size(); }
}
