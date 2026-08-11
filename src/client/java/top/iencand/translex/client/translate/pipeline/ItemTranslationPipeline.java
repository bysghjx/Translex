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
import top.iencand.translex.client.translate.model.StyledText;
import top.iencand.translex.client.translate.model.StyleCodec;
import top.iencand.translex.client.translate.model.TranslationCacheEntry;
import top.iencand.translex.client.translate.model.TranslationFormat;
import top.iencand.translex.client.translate.model.TranslationFormatRegistry;
import top.iencand.translex.client.translate.render.ChatRenderer;
import top.iencand.translex.client.util.TooltipKeyUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
    private final TranslationSplitter splitter = new TranslationSplitter();
    private final ChatRenderer renderer = new ChatRenderer();
    private final TranslationCacheManager cacheManager = new TranslationCacheManager();
    private final ItemPresetLibrary presetLibrary = new ItemPresetLibrary();
    private final BatchDispatcher dispatcher;

    private final tsp.RecoveryStats recoveryStats;

    public ItemTranslationPipeline(TranslationRequester sharedRequester, tsp.RecoveryStats recoveryStats) {
        PipelineConfig config = new PipelineConfig(
                "TL_ITEM",
                1500,
                () -> {
                    // TSP / HYBRID 都用 [[ID||]] 格式，用 TSP 强 prompt；sN 用 <sN> prompt
                    return currentFormat().usesTspSyntax()
                            ? TranslationPrompts.itemSystemPromptTsp(ModConfig.get().targetLanguage, ModConfig.get().properNounMode)
                            : TranslationPrompts.itemSystemPrompt(ModConfig.get().targetLanguage, ModConfig.get().properNounMode);
                },
                () -> ModConfig.get().userItemPrompt,
                "Translex-Dispatcher-Item"
        );
        this.dispatcher = new BatchDispatcher(config, sharedRequester);
        this.recoveryStats = recoveryStats;
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

        List<ItemTranslationTask> tasks = new ArrayList<>();
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
                StyledText styledParagraph = StyledText.of(combined);
                TranslationFormat format = currentFormat();
                TranslationFormat.Encoded encoded = format.encode(styledParagraph);
                String paraCacheKey = cacheManager.buildCacheKey(format.stripFormatTags(encoded.template()));
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
                        Component paraComponent = cachedFormat.decode(entry.template(), styledParagraph, true,
                                entry.registryHash(), recoveryStats);
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
                    // 词库预翻译 short-circuit（全命中不发 AI）
                    String glossedTmpl = TranslationCacheManager.applyGlossaryToTemplate(
                            encoded.template(), format.id());
                    if (!TranslationCacheManager.templateStillHasEnglish(glossedTmpl, format.id())) {
                        Component paraComponent = format.decode(glossedTmpl, styledParagraph, true,
                                encoded.registryHash(), recoveryStats);
                        if (paraComponent != null) {
                            result[start] = paraComponent;
                            storedTemplates[start] = new TranslationCacheEntry(format.id(),
                                    glossedTmpl.replace("\n", " "), encoded.registryHash()).toJson();
                            for (int j = 1; j < cnt; j++) {
                                result[start + j] = null;
                                storedTemplates[start + j] = "";
                            }
                            continue;
                        }
                    }
                    for (int j = 0; j < cnt; j++) {
                        result[start + j] = originalLines.get(start + j);  // 临时原文，future 完成后替换
                        aiIndices.add(start + j);
                    }
                    tasks.add(ItemTranslationTask.ofParagraph(
                            start, cnt,
                            dispatcher.submit(encoded.template(),
                                    debug ? combined.getString() : null, null),
                            styledParagraph, encoded.template(), paraCacheKey,
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
                StyledText styledLine = StyledText.of(lineComp);
                TranslationFormat format = currentFormat();
                TranslationFormat.Encoded encoded = format.encode(styledLine);

                // 调试：sN 输出样式信息（直接复用 StyledText 的一次提取结果）
                if (debug && "SN".equals(format.id())) {
                    String type = (i == 0) ? "NAME" : "LORE";
                    LOGGER.info(StyleCodec.dumpExtraction(
                            String.format("%s#%d", type, i),
                            styledLine.extractionResult().styleMap(),
                            styledLine.snTemplate()));
                }

                // 词库预翻译：SN + TSP/HYBRID 都支持。全命中（无英文剩余）-> 短路不发 AI。
                // TSP 的 hash 是原文 text 的 sha4，词典只改 text 不改 hash 字段，checksum 校验仍通过。
                String glossedTmpl = TranslationCacheManager.applyGlossaryToTemplate(
                        encoded.template(), format.id());
                if (!TranslationCacheManager.templateStillHasEnglish(glossedTmpl, format.id())) {
                    Component decoded = format.decode(glossedTmpl, styledLine, false,
                            encoded.registryHash(), recoveryStats);
                    if (decoded != null) {
                        result[i] = decoded;
                        storedTemplates[i] = new TranslationCacheEntry(
                                format.id(), glossedTmpl, encoded.registryHash()).toJson();
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
                        boolean valid = TranslationFormatRegistry.usesTspSyntax(entry.format())
                                || ItemTranslationValidator.validateTranslation(
                                        encoded.template(), entry.template(), i) != null;
                        if (valid) {
                            Component decoded = cachedFormat.decode(entry.template(), styledLine, false,
                                    entry.registryHash(), recoveryStats);
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
                tasks.add(ItemTranslationTask.ofLine(
                        i,
                        dispatcher.submit(encoded.template(),
                                debug ? lineText : null, debug ? glossed : null),
                        styledLine, encoded.template(), ck,
                        format.id(), encoded.registryHash()));
                if (debug) originalTemplates[i] = encoded.template();
            }
        }

        // 所有行已 submit 到 dispatcher，立即触发批 flush（自适应窗口：行到齐即发，不等 2.5s 固定窗口）
        dispatcher.flushNow();

        ItemTranslationResultCollector collector = new ItemTranslationResultCollector(
                originalLines, itemId, stack, result, storedTemplates, displayId,
                aiIndices, debug, renderer, cacheManager, presetLibrary, recoveryStats);
        if (aiIndices.isEmpty()) {
            collector.finishImmediately();
            return;
        }
        collector.attach(tasks);
    }

    /** 价格行跳过翻译（保留原文）：Bazaar 买/卖均价、合成价格。数值常变且翻译价值低。 */
    private static boolean isSkippedPriceLine(String text) {
        if (text == null) return false;
        return text.contains("Bazaar Buy-Avg") || text.contains("Bazaar Sell-Avg")
                || text.contains("Crafting Price")
                || text.contains("Auction Lowest BIN")
                || text.contains("Est. Item Value");
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
