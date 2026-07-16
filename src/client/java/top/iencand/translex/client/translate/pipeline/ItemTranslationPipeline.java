package top.iencand.translex.client.translate.pipeline;

import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.translate.TranslationPrompts;
import top.iencand.translex.client.translate.TranslationRequester;
import top.iencand.translex.client.translate.TranslationSplitter;
import top.iencand.translex.client.translate.cache.TemporaryTooltipCache;
import top.iencand.translex.client.translate.cache.TranslationCacheManager;
import top.iencand.translex.client.translate.model.ItemPresetLibrary;
import top.iencand.translex.client.translate.model.LineTemplate;
import top.iencand.translex.client.translate.model.StyleCodec;
import top.iencand.translex.client.translate.model.TranslationCacheEntry;
import top.iencand.translex.client.translate.model.TranslationFormat;
import top.iencand.translex.client.translate.render.ChatRenderer;
import top.iencand.translex.client.util.TooltipKeyUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 物品说明（lore）翻译管线 facade。
 *
 * <p>独立的 {@link BatchDispatcher}（item 配置，使用 itemTranslationPrompt）+ 行级 shard 缓存
 * （{@link TranslationCacheManager}，保留）+ {@link ItemPresetLibrary}（permanent）
 * + {@link TemporaryTooltipCache}（temporary）。段落合并翻译（跨行描述行整段送 AI），
 * 全部完成后统一渲染并按输出模式存储。</p>
 *
 * <p>存储缓存键统一经 {@link TooltipKeyUtil}（{@code itemId#loreHash}），与替换查找一致，避免串台。</p>
 */
public class ItemTranslationPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger("Translex/ItemPipeline");
    /** 样式匹配正则（与 StyleCodec 一致），用于解析模板中的 &lt;sN&gt; 段。 */
    private static final Pattern STYLE_TAG_RE = Pattern.compile("<s(\\d+)>(.*?)</s\\1>", Pattern.DOTALL);

    private final TranslationSplitter splitter = new TranslationSplitter();
    private final ChatRenderer renderer = new ChatRenderer();
    private final TranslationCacheManager cacheManager = new TranslationCacheManager();
    private final ItemPresetLibrary presetLibrary = new ItemPresetLibrary();
    private final BatchDispatcher dispatcher;

    public ItemTranslationPipeline(TranslationRequester sharedRequester) {
        PipelineConfig config = new PipelineConfig(
                "TL_ITEM",
                1500,
                () -> "TSP".equalsIgnoreCase(ModConfig.get().styleProtocol)
                        ? TranslationPrompts.itemSystemPromptTsp(ModConfig.get().targetLanguage, ModConfig.get().properNounMode)
                        : TranslationPrompts.itemSystemPrompt(ModConfig.get().targetLanguage, ModConfig.get().properNounMode),
                () -> ModConfig.get().userItemPrompt,
                "Translex-Dispatcher-Item"
        );
        this.dispatcher = new BatchDispatcher(config, sharedRequester);
        presetLibrary.load();
    }

    /** 当前 styleProtocol 对应的 TranslationFormat（无状态，每次按 config 选）。 */
    private static TranslationFormat currentFormat() {
        return TranslationFormat.forId(ModConfig.get().styleProtocol);
    }

    public void initializePersistence(File file) {
        if (file != null) cacheManager.init(file);
    }

    public ItemPresetLibrary getPresetLibrary() { return presetLibrary; }
    public TranslationCacheManager getCacheManager() { return cacheManager; }

    /**
     * 翻译物品说明。先按段落分组（连续无冒号描述行合并），段落整段翻译给 AI 跨行上下文；
     * 独立行（stat/标题/Bazaar）走逐行逻辑。各行/段落并行，全部完成后统一渲染并按输出模式存储。
     */
    public void translateItemLoreTemplates(List<Component> originalLines, String itemId,
                                            String itemDisplayName, ItemStack stack, boolean force) {
        if (originalLines == null || originalLines.isEmpty()) return;
        int n = originalLines.size();

        Component[] result = new Component[n];
        String[] storedTemplates = new String[n];
        String displayId = "IL_" + System.currentTimeMillis();

        record LinePending(int index, CompletableFuture<String> future, String template, Component original,
                           String cacheKey, String glossed, String formatId, String registryHash) {}
        record ParaPending(int startLine, int lineCount, CompletableFuture<String> future,
                           String template, Component original, String cacheKey, List<String> glossedLines,
                           String formatId, String registryHash) {}
        List<LinePending> linePending = new ArrayList<>();
        List<ParaPending> paraPending = new ArrayList<>();
        java.util.Set<Integer> aiIndices = new java.util.LinkedHashSet<>();

        boolean debug = ModConfig.get().debug;
        String[] originalTemplates = debug ? new String[n] : null;
        if (debug) {
            LOGGER.info("══════ StyleDump START - item={} ══════", itemId);
        }

        // 段落分组：连续无冒号描述行合并成段落整段翻译，给 AI 跨行上下文，避免逐行脑补
        List<ParagraphGrouper.Group> groups = ParagraphGrouper.group(originalLines);
        for (ParagraphGrouper.Group g : groups) {
            if (g.isParagraph()) {
                // ── 段落路径：合并多行整段翻译 ──
                int start = g.startIndex();
                int cnt = g.lineCount();
                List<Component> paraLines = originalLines.subList(start, g.endIndexExclusive());
                // 合并成一个 Text（\n 分隔）
                net.minecraft.network.chat.MutableComponent combined = net.minecraft.network.chat.Component.literal("");
                for (int j = 0; j < paraLines.size(); j++) {
                    if (j > 0) combined.append(net.minecraft.network.chat.Component.literal("\n"));
                    combined.append(paraLines.get(j));
                }
                TranslationFormat format = currentFormat();
                TranslationFormat.Encoded encoded = format.encode(combined);
                String paraCacheKey = cacheManager.buildCacheKey(format.stripFormatTags(encoded.template()));
                List<String> glossedLines = new ArrayList<>();
                for (Component pl : paraLines) glossedLines.add(cacheManager.applyGlossary(pl.getString()));
                if (debug) {
                    for (int j = 0; j < cnt; j++) originalTemplates[start + j] = encoded.template();
                }
                String paraCached = force ? null : cacheManager.getByCacheKey(paraCacheKey);
                boolean cacheHit = false;
                if (paraCached != null) {
                    TranslationCacheEntry entry = TranslationCacheEntry.parse(paraCached);
                    if (entry != null) {
                        TranslationFormat cachedFormat = TranslationFormat.forId(entry.format());
                        // decode：TSP 校验 registryHash（颜色结构变返回 null -> miss），sN 忽略
                        Component paraComponent = cachedFormat.decode(entry.template(), combined, true, entry.registryHash());
                        if (paraComponent != null) {
                            result[start] = paraComponent;
                            // \n -> 空格：避免 handleOutputMode 的 join/split 把整段 \n 当行分隔拆开
                            storedTemplates[start] = new TranslationCacheEntry(entry.format(),
                                    entry.template().replace("\n", " "), entry.registryHash()).toJson();
                            for (int j = 1; j < cnt; j++) {
                                result[start + j] = null;  // 空标记，渲染时由段落首行 wrap 填充
                                storedTemplates[start + j] = "";
                            }
                            cacheHit = true;
                        }
                    }
                }
                if (!cacheHit) {
                    for (int j = 0; j < cnt; j++) {
                        result[start + j] = originalLines.get(start + j);  // 临时原文，future 完成后替换
                        aiIndices.add(start + j);
                    }
                    paraPending.add(new ParaPending(start, cnt, dispatcher.submit(encoded.template()),
                            encoded.template(), combined, paraCacheKey, glossedLines,
                            format.id(), encoded.registryHash()));
                }
            } else {
                // ── 单行路径 ──
                int i = g.startIndex();
                Component lineComp = originalLines.get(i);
                String lineText = lineComp.getString();

                // Bazaar 价格行不翻译（保留原文）：价格行每次都因数值变化 miss 缓存
                if (isSkippedPriceLine(lineText)) {
                    result[i] = lineComp;
                    TranslationFormat priceFmt = currentFormat();
                    TranslationFormat.Encoded priceEnc = priceFmt.encode(lineComp);
                    storedTemplates[i] = new TranslationCacheEntry(priceFmt.id(), priceEnc.template(), priceEnc.registryHash()).toJson();
                    if (debug) originalTemplates[i] = priceEnc.template();
                    continue;
                }

                String glossed = cacheManager.applyGlossary(lineText);  // 纯文本版（回退用）
                TranslationFormat format = currentFormat();
                TranslationFormat.Encoded encoded = format.encode(lineComp);

                // 调试：sN 输出样式信息（TSP 无 LineTemplate，跳过）
                if (debug && "SN".equals(format.id())) {
                    LineTemplate tmpl = LineTemplate.fromText(lineComp);
                    String type = (i == 0) ? "NAME" : "LORE";
                    LOGGER.info(StyleCodec.dumpExtraction(
                            String.format("%s#%d", type, i),
                            tmpl.extractionResult().styleMap(),
                            tmpl.getTemplate()));
                }

                // 词库预翻译：sN 专用（TSP 跳过，总发 AI）
                if ("SN".equals(format.id())) {
                    String glossedTmpl = TranslationCacheManager.applyGlossaryToTemplate(encoded.template());
                    if (!TranslationCacheManager.templateStillHasEnglish(glossedTmpl)) {
                        // 词库完整处理（模板内不再含英文）-> 直接渲染，颜色完整保留
                        result[i] = format.decode(glossedTmpl, lineComp, false, null);
                        storedTemplates[i] = new TranslationCacheEntry("SN", glossedTmpl, null).toJson();
                        if (debug) originalTemplates[i] = encoded.template();
                        continue;
                    }
                }

                String ck = cacheManager.buildCacheKey(format.stripFormatTags(encoded.template()));
                // force=true（Ctrl+P 强制重译）时跳过行级缓存，直接请求 AI
                String cachedJson = force ? null : cacheManager.getByCacheKey(ck);
                if (cachedJson != null) {
                    TranslationCacheEntry entry = TranslationCacheEntry.parse(cachedJson);
                    if (entry != null) {
                        TranslationFormat cachedFormat = TranslationFormat.forId(entry.format());
                        // sN validate 缓存完整性（TSP 信任 + registryHash 校验在 decode 内做）
                        boolean valid = "TSP".equals(entry.format())
                                || validateTranslation(encoded.template(), entry.template(), i) != null;
                        if (valid) {
                            Component decoded = cachedFormat.decode(entry.template(), lineComp, false, entry.registryHash());
                            if (decoded != null) {
                                result[i] = decoded;
                                storedTemplates[i] = new TranslationCacheEntry(entry.format(), entry.template(), entry.registryHash()).toJson();
                                if (debug) originalTemplates[i] = encoded.template();
                                continue;
                            }
                        }
                    }
                    // 缓存损坏 / TSP registryHash 不匹配 -> 丢弃重译
                    LOGGER.warn("⚠ Line {} cache rejected, re-requesting AI", i);
                    cacheManager.removeByCacheKey(ck);
                }
                // 发 AI
                result[i] = lineComp;  // 临时原文，future 完成后替换
                storedTemplates[i] = glossed;
                aiIndices.add(i);
                linePending.add(new LinePending(i, dispatcher.submit(encoded.template()),
                        encoded.template(), lineComp, ck, glossed, format.id(), encoded.registryHash()));
                if (debug) originalTemplates[i] = encoded.template();
            }
        }

        // 所有行已 submit 到 dispatcher，立即触发批 flush（自适应窗口：行到齐即发，不等 2.5s 固定窗口）
        dispatcher.flushNow();

        if (aiIndices.isEmpty()) {
            if (debug) LOGGER.info("══════ StyleDump END - all lines pre-translated, item={} ══════", itemId);
            renderer.renderResult(joinTexts(originalLines), joinTextsSafe(result), displayId);
            handleOutputMode(String.join("\n", storedTemplates), itemId, stack, originalLines);
            return;
        }

        final java.util.Set<Integer> completed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        // 整批失败时只向玩家红字提示一次（多行 future 并发完成，避免刷屏）
        final java.util.concurrent.atomic.AtomicBoolean errorReported = new java.util.concurrent.atomic.AtomicBoolean(false);
        final String[] finalOriginalTemplates = originalTemplates;
        final Runnable maybeRender = () -> {
            if (completed.size() == aiIndices.size()) {
                if (debug) LOGGER.info("══════ StyleDump END - item={} ══════", itemId);
                renderer.renderResult(joinTexts(originalLines), joinTextsSafe(result), displayId);
                handleOutputMode(String.join("\n", storedTemplates), itemId, stack, originalLines);
            }
        };

        // 单行 future
        for (LinePending p : linePending) {
            final int idx = p.index();
            final Component original = p.original();
            final String template = p.template();
            final String glossed = p.glossed();
            final String formatId = p.formatId();
            final String registryHash = p.registryHash();
            final TranslationFormat fmt = TranslationFormat.forId(formatId);
            p.future().thenAccept(translated -> {
                if (translated != null && translated.startsWith("§c")) {
                    // AI 整体请求失败（§c 错误串）：回退原文，红字提示一次（不刷屏）
                    if (errorReported.compareAndSet(false, true)) {
                        renderer.renderError(translated, displayId);
                    }
                    result[idx] = original;
                    storedTemplates[idx] = new TranslationCacheEntry(formatId, template, registryHash).toJson();
                } else {
                    // sN validate 占位符/标签完整性（TSP 跳过，信任强 prompt + decode 内 registryHash 校验）
                    String validated = "TSP".equals(formatId) ? translated
                            : validateTranslation(template, translated, idx);
                    if (validated != null) {
                        // AI 输出有效 -> 缓存 + 使用
                        cacheManager.putByCacheKey(p.cacheKey(),
                                new TranslationCacheEntry(formatId, validated, registryHash).toJson());
                        Component decoded = fmt.decode(validated, original, false, registryHash);
                        result[idx] = decoded != null ? decoded : original;
                        storedTemplates[idx] = new TranslationCacheEntry(formatId, validated, registryHash).toJson();
                    } else {
                        // AI 输出损坏（标签塌缩/占位符丢失）-> 回退原文
                        result[idx] = original;
                        storedTemplates[idx] = new TranslationCacheEntry(formatId, template, registryHash).toJson();
                    }
                }
                completed.add(idx);
                maybeRender.run();
            });
        }

        // 段落 future
        for (ParaPending p : paraPending) {
            final int start = p.startLine();
            final int cnt = p.lineCount();
            final Component original = p.original();
            final String template = p.template();
            final List<String> glossedLines = p.glossedLines();
            final String formatId = p.formatId();
            final String registryHash = p.registryHash();
            final TranslationFormat fmt = TranslationFormat.forId(formatId);
            p.future().thenAccept(translated -> {
                if (translated != null && translated.startsWith("§c")) {
                    // 段落请求失败：每行回退原文
                    if (errorReported.compareAndSet(false, true)) {
                        renderer.renderError(translated, displayId);
                    }
                    for (int j = 0; j < cnt; j++) {
                        result[start + j] = originalLines.get(start + j);
                        storedTemplates[start + j] = new TranslationCacheEntry(formatId, template, registryHash).toJson();
                        completed.add(start + j);
                    }
                } else {
                    // 段落整段渲染成一个 Component（不拆行，\n->空格），存首行 + 空标记
                    // 渲染层（Mixin）用 Font.split 按宽度重新换行，动态调 wrapWidth 对齐原行数
                    try {
                        Component paraComponent = fmt.decode(translated, original, true, registryHash);
                        if (paraComponent == null) paraComponent = original;  // registryHash 不匹配回退
                        cacheManager.putByCacheKey(p.cacheKey(),
                                new TranslationCacheEntry(formatId, translated, registryHash).toJson());
                        result[start] = paraComponent;
                        // \n -> 空格：避免 handleOutputMode 的 join/split 把整段 \n 当行分隔拆开
                        storedTemplates[start] = new TranslationCacheEntry(formatId,
                                translated.replace("\n", " "), registryHash).toJson();
                        for (int j = 1; j < cnt; j++) {
                            result[start + j] = null;  // 空标记
                            storedTemplates[start + j] = "";
                        }
                        for (int j = 0; j < cnt; j++) completed.add(start + j);
                    } catch (Exception e) {
                        LOGGER.warn("⚠ Paragraph render failed at lines {}-{}, fallback to original: {}",
                                start, start + cnt - 1, e.getMessage());
                        for (int j = 0; j < cnt; j++) {
                            result[start + j] = originalLines.get(start + j);
                            storedTemplates[start + j] = new TranslationCacheEntry(formatId, template, registryHash).toJson();
                            completed.add(start + j);
                        }
                    }
                }
                maybeRender.run();
            });
        }
    }

    /** 调试：输出翻译前后完整对照，并检测跨行 {i} 占位符移动。 */
    private void dumpTranslationResult(String itemId, int nLines,
                                        String[] originalTemplates, String[] translatedTemplates,
                                        LineTemplate anyTmpl) {
        // 收集每行原始/AI 的 {i} 占位符出现位置
        record Ph(String text, int line) {}
        java.util.Map<String, Ph> origPlacements = new java.util.LinkedHashMap<>();
        java.util.Map<String, Ph> aiPlacements = new java.util.LinkedHashMap<>();

        for (int i = 0; i < nLines; i++) {
            String orig = originalTemplates[i];
            String ai = translatedTemplates[i];

            // 跳过预翻译行（无 AI 参与）
            boolean isPre = (ai == null);

            // 提取原始模板中的 {i} 占位符
            if (orig != null) {
                Matcher m = Pattern.compile("\\{(\\d+)\\}").matcher(orig);
                while (m.find()) origPlacements.put(m.group(), new Ph(orig, i));
            }
            // 提取 AI 结果中的 {i} 占位符
            if (ai != null && !isPre) {
                Matcher m = Pattern.compile("\\{(\\d+)\\}").matcher(ai);
                while (m.find()) aiPlacements.put(m.group(), new Ph(ai, i));
            }

            // 输出每行对照
            LOGGER.info("── Line {} {} ──", i, isPre ? "(PRETRANSLATED)" : "");
            if (orig != null) LOGGER.info("  ORIG: {}", orig);
            if (ai != null && !isPre) {
                LOGGER.info("  AI  : {}", ai);
                // 检查 AI 是否创建了新标签 ID
                java.util.Set<Integer> origIds = extractTagIds(orig);
                java.util.Set<Integer> aiIds = extractTagIds(ai);
                java.util.Set<Integer> newIds = new java.util.LinkedHashSet<>(aiIds);
                newIds.removeAll(origIds);
                if (!newIds.isEmpty()) {
                    LOGGER.warn("  ⚠ NEW TAG IDs created by AI: {} (original had: {})", newIds, origIds);
                }
            }
        }

        // 跨行占位符移动检测
        for (var entry : origPlacements.entrySet()) {
            String placeholder = entry.getKey();
            Ph origPh = entry.getValue();
            Ph aiPh = aiPlacements.get(placeholder);
            if (aiPh != null && aiPh.line() != origPh.line()) {
                LOGGER.warn("  ⚠ CROSS-LINE MOVEMENT: {} moved from line {} to line {} - color may be wrong!",
                        placeholder, origPh.line(), aiPh.line());
            }
        }

        LOGGER.info("══════ StyleDump END - item={} ══════", itemId);
    }

    /** 从带 &lt;sN&gt; 标签的模板中提取所有标签 ID。 */
    private static java.util.Set<Integer> extractTagIds(String template) {
        java.util.Set<Integer> ids = new java.util.LinkedHashSet<>();
        if (template == null) return ids;
        Matcher m = STYLE_TAG_RE.matcher(template);
        while (m.find()) ids.add(Integer.parseInt(m.group(1)));
        return ids;
    }

    /** 匹配 {0} {1} 等占位符。 */
    private static final Pattern PLACEHOLDER_RE = Pattern.compile("\\{(\\d+)\\}");

    /**
     * 验证 AI 翻译结果是否保留了所有 {i} 占位符和 &lt;sN&gt; 标签。
     * 若 AI 塌缩了标签或丢失了占位符，返回 {@code null} 表示应回退到英文原文；
     * 否则返回原始 AI 结果（验证通过）。
     *
     * @param original 原始模板（如 {@code <s0>Defense: </s0><s1>{0}</s1><s2>{1}</s2>}）
     * @param aiResult AI 返回的翻译（可能缺少标签/占位符）
     * @param lineIdx  行号（用于日志）
     * @return 验证通过的 AI 结果，或 null 表示损坏需回退
     */
    private static String validateTranslation(String original, String aiResult, int lineIdx) {
        if (original == null || aiResult == null) return aiResult;

        // 1. 统计原始/AI 的 {i} 占位符
        java.util.Set<String> origPH = new java.util.LinkedHashSet<>();
        Matcher mo = PLACEHOLDER_RE.matcher(original);
        while (mo.find()) origPH.add(mo.group());

        java.util.Set<String> aiPH = new java.util.LinkedHashSet<>();
        Matcher ma = PLACEHOLDER_RE.matcher(aiResult);
        while (ma.find()) aiPH.add(ma.group());

        java.util.Set<String> lostPH = new java.util.LinkedHashSet<>(origPH);
        lostPH.removeAll(aiPH);
        boolean lostPlaceholders = !lostPH.isEmpty();

        // 2. 统计标签数量
        int origTags = 0, aiTags = 0;
        Matcher mt = STYLE_TAG_RE.matcher(original);
        while (mt.find()) origTags++;
        mt = STYLE_TAG_RE.matcher(aiResult);
        while (mt.find()) aiTags++;

        boolean collapsed = aiTags < origTags;

        // 始终输出验证结果（INFO 级别，方便确认逻辑执行了）
        LOGGER.info("Validator Line {}: origTags={} aiTags={} collapsed={} origPH={} aiPH={} lostPH={}",
                lineIdx, origTags, aiTags, collapsed, origPH, aiPH, lostPH);

        // 3. 检测到损坏 -> 回退到英文原文（保留单色），不缓存损坏结果
        if (collapsed || lostPlaceholders) {
            LOGGER.warn("⚠ Line {} REJECTED - tags {}->{}  placeholders lost={}  |  orig={}  |  ai={}",
                    lineIdx, origTags, aiTags, lostPH, original, aiResult);
            return null;  // null = 回退到英文原文
        }

        // 4. AI 多加占位符 -> 清洗多余 {i}（不回退，保留翻译）
        java.util.Set<String> extraPH = new java.util.LinkedHashSet<>(aiPH);
        extraPH.removeAll(origPH);
        if (!extraPH.isEmpty()) {
            LOGGER.warn("⚠ Line {} EXTRA placeholders {} - cleaned, kept translation", lineIdx, extraPH);
            String cleaned = aiResult;
            for (String ph : extraPH) cleaned = cleaned.replace(ph, "");
            return cleaned;
        }

        return aiResult;
    }

    /**
     * 根据输出模式存储翻译结果，键统一为 {@code itemId#loreHash}（与查找一致）。
     */
    private void handleOutputMode(String translated, String itemId, ItemStack stack, List<Component> originalLines) {
        String mode = ModConfig.get().outputMode;
        List<String> lines = Arrays.asList(translated.split("\n", -1));
        switch (mode) {
            case "permanent" -> {
                if (stack != null && !stack.isEmpty()) {
                    String key = TooltipKeyUtil.buildKey(stack, originalLines);
                    if (key != null) presetLibrary.putTooltip(key, lines);
                }
            }
            case "temporary" -> {
                if (stack != null && !stack.isEmpty()) {
                    TemporaryTooltipCache.put(stack, originalLines, lines);
                }
            }
        }
    }

    // -------- 辅助 --------

    private static net.minecraft.network.chat.Style findColor(Component text) {
        for (Component child : text.getSiblings()) {
            net.minecraft.network.chat.Style cs = findColor(child);
            if (cs.getColor() != null) return cs;
        }
        return text.getStyle();
    }

    private static String joinTexts(List<Component> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i).getString());
            if (i < lines.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }

    /** joinTexts 的 null 安全版：段落空标记行（null）跳过，不调 getString()。 */
    private static String joinTextsSafe(Component[] lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i] != null) {
                sb.append(lines[i].getString());
            }
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString();
    }

    private static boolean containsEnglish(String text) {
        return text != null && text.matches("(?s).*[a-zA-Z].*");
    }

    /**
     * 从缓存 JSON 或纯文本中提取"翻译后模板"（含 {@code <sN>} 标签），
     * 用于输出存储（temp/permanent），确保 tooltip Mixin 读回时能正确重建样式。
     */
    static String fromCacheOrRaw(String cacheJson) {
        try {
            // 缓存 JSON：{"markedText":"<s0>译</s0>","snapshots":{...}}
            StyleCodec.ExtractionResult er = StyleCodec.ExtractionResult.fromCacheJson(cacheJson);
            if (er.markedText() != null && !er.markedText().isBlank()) {
                return er.markedText();
            }
        } catch (Exception ignored) {}
        // 旧版缓存（纯译文字符串，无标签）：原样返回（Mixin 用 apply() 回退上色）
        return cacheJson;
    }

    /** 价格行跳过翻译（保留原文）：Bazaar 买/卖均价、合成价格。数值常变且翻译价值低。 */
    private static boolean isSkippedPriceLine(String text) {
        if (text == null) return false;
        return text.contains("Bazaar Buy-Avg") || text.contains("Bazaar Sell-Avg")
                || text.contains("Crafting Price")
                || text.contains("Auction Lowest BIN")
                || text.contains("Est. Item Value");
    }

    /** 段落拆回行数容错：译文行数 > 原行数时，把多余尾行合并到第 cnt 行
     * （前 cnt-1 行各自，第 cnt 行 = 剩余全部拼接）。应对 AI 把句号等标点单独拆成一行的常见情况。
     * 行数不足（< cnt）则原样返回，由调用方回退原文。 */
    private static List<String> alignParagraphParts(List<String> parts, int cnt) {
        if (parts == null || parts.size() <= cnt) return parts;
        List<String> aligned = new ArrayList<>(cnt);
        for (int i = 0; i < cnt - 1; i++) aligned.add(parts.get(i));
        StringBuilder last = new StringBuilder();
        for (int i = cnt - 1; i < parts.size(); i++) last.append(parts.get(i));
        aligned.add(last.toString());
        return aligned;
    }

    public void shutdown() {
        dispatcher.shutdown();
        cacheManager.shutdown();
        presetLibrary.shutdown();
    }

    /** 使当前物品翻译会话失效（关 GUI 时调用），丢弃进行中的异步回调。 */
    public void invalidateSession() {
        dispatcher.invalidateSession();
    }
}
