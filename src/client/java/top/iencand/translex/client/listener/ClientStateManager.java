package top.iencand.translex.client.listener;

import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.KeyEvent;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import top.iencand.translex.client.mixin.HandledScreenAccessor;
import top.iencand.translex.client.translate.cache.TemporaryTooltipCache;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.keybinding.ModKeybindings;
import top.iencand.translex.client.translate.TranslationManager;
import top.iencand.translex.client.util.I18nHelper;
import top.iencand.translex.client.util.ItemIdExtractor;
import top.iencand.translex.client.util.LoreHarvester;
import top.iencand.translex.client.util.TooltipKeyUtil;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

@Environment(EnvType.CLIENT)
/**
 * 客户端状态管理器，负责追踪玩家当前悬停的物品、注册按键事件和工具提示回调。
 *
 * <p>核心职责：
 * <ul>
 *   <li>记录最近悬停的物品堆栈（供 Mixin 读取）</li>
 *   <li>管理按键快捷键触发物品说明翻译</li>
 *   <li>清理临时缓存中的旧物品条目</li>
 * </ul>
 */
public class ClientStateManager {

    private final TranslationManager translationManager;

    /** 最近悬停的物品堆栈（静态变量以便 Mixin 访问） */
    private static volatile ItemStack lastHoveredItem = null;

    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("§[0-9a-fk-or]");

    public ClientStateManager(TranslationManager translationManager) {
        this.translationManager = translationManager;
    }

    /** 供工具提示替换 Mixin 调用，获取当前悬停的物品 */
    public static ItemStack getLastHoveredItem() {
        return lastHoveredItem;
    }

    public void registerEvents() {
        // 工具提示回调：追踪悬停物品。
        // 实际的文本替换由 ScreenTooltipMixin 和 DrawContextTooltipMixin 完成。
        // 注：temporary 缓存的清理改为在关闭 GUI 时统一 clear（见 ScreenEvents.remove），
        // 不再按"悬停切换"逐条清理 —— 这样切到空格子/别的物品都不会残留旧翻译。
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            lastHoveredItem = stack;
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            lastHoveredItem = null;
            HoverSlotTracker.clearAll();

            if (screen instanceof AbstractContainerScreen) {
                ScreenKeyboardEvents.afterKeyPress(screen).register(this::onGuiKeyPress);

                // 每帧读 hoveredSlot，维护"当前悬停槽位 + loreHash"供 tooltip 替换门控
                // 注：26.1.2 / fabric-screen-api v1 5.0 移除了 afterRender，改用 afterExtract
                ScreenEvents.afterExtract(screen).register((scr, gge, mouseX, mouseY, delta) -> {
                    Slot focused = ((HandledScreenAccessor) scr).translex$getHoveredSlot();
                    if (focused != null && focused.hasItem()) {
                        HoverSlotTracker.updateHover(focused.index, focused.getItem());
                    } else {
                        HoverSlotTracker.updateHover(-1, null);
                    }
                });
            }

            // 关闭 GUI 时清空临时翻译缓存：temporary 模式本就是会话内临时，
            // 关 GUI 全清最符合语义，也顺手压住无界增长。
            // remove 是关闭事件（非 refresh），不会误清同一 GUI 内刚存的翻译。
            ScreenEvents.remove(screen).register((removedScreen) -> {
                lastHoveredItem = null;
                HoverSlotTracker.clearAll();
                TemporaryTooltipCache.clear();
                translationManager.invalidateItemSession();
            });
        });
    }

    // ---------------------------------------------------------------
    // Key press handling (translate lore on hotkey)
    // ---------------------------------------------------------------

    private void onGuiKeyPress(Screen screen, KeyEvent input) {
        // H 键：采集当前 GUI 所有物品 tooltip 存本地（TSP 测试数据收集）。
        // 用键位而非命令：容器 GUI 里按 T 打开聊天会关闭容器，命令执行时拿不到 screen。
        if (ModKeybindings.HARVEST_KEY.matches(input)) {
            LocalPlayer player = Minecraft.getInstance().player;
            LoreHarvester.HarvestResult result = LoreHarvester.harvestAll();
            if (player != null) {
                if (!result.success()) {
                    player.sendSystemMessage(Component.literal(
                            I18nHelper.getPrefixed("translex.harvest.error_no_screen")));
                } else {
                    player.sendSystemMessage(Component.literal(I18nHelper.getPrefixed(
                            "translex.harvest.success", result.total(), result.added(), result.skipped())));
                    player.sendSystemMessage(Component.literal(I18nHelper.getPrefixed(
                            "translex.harvest.location", LoreHarvester.getHarvestFile().getAbsolutePath())));
                }
            }
            return;
        }

        if (ModKeybindings.TRANSLATE_LORE_KEY.matches(input)) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            // Ctrl+P = 强制重新翻译（忽略缓存）；单独 P = 普通翻译
            boolean force = isCtrlDown();

            if (screen instanceof AbstractContainerScreen) {
                if (lastHoveredItem != null && !lastHoveredItem.isEmpty()) {

                    // Check API key before doing any work (skip in debug mode)
                    if (!ModConfig.get().debug) {
                        String key = ModConfig.get().apiKey;
                        if (key == null || key.isBlank() || key.equals("YOUR_API_KEY_HERE")) {
                            if (player != null) {
                                player.sendSystemMessage(Component.literal(
                                        I18nHelper.getPrefixed("translex.error.api_key_unset")));
                                player.sendSystemMessage(Component.literal(
                                        "  §e/translex config §7— " + I18nHelper.translate("translex.error.api_key_hint")));
                            }
                            return;
                        }
                    }

                    String itemDisplayName = lastHoveredItem.getHoverName().getString();
                    String itemId = ItemIdExtractor.extractSkyBlockItemId(lastHoveredItem);

                    // 1. 通过 getTooltipFromItem 获取完整工具提示（与 Mixin 使用相同方法）
                    //    确保翻译行数与替换时的行数一致，也用于组合键哈希
                    List<Component> fullTooltip = Screen.getTooltipFromItem(mc, lastHoveredItem);

                    if (fullTooltip.isEmpty()) {
                        if (player != null) player.sendSystemMessage(Component.literal(
                                I18nHelper.getPrefixed("translex.error.content_empty")));
                        return;
                    }

                    // 0. 先用组合键检查永久预设库（itemId#loreHash），命中则直接回显
                    //    force（Ctrl+P）时跳过，强制重新翻译
                    if (!force) {
                        String presetKey = TooltipKeyUtil.buildKey(lastHoveredItem, fullTooltip);
                        if (presetKey != null) {
                            List<String> presetLines =
                                    translationManager.getPresetLibrary().getTooltip(presetKey);
                            if (presetLines != null && !presetLines.isEmpty()) {
                                if (player != null) {
                                    player.sendSystemMessage(Component.literal(
                                            I18nHelper.getPrefixed("translex.info.preset_hit")));
                                    for (String line : presetLines) {
                                        player.sendSystemMessage(Component.literal("§7" + line));
                                    }
                                }
                                return;
                            }
                        }
                    }

                    // 2. Concatenate all lines — includes item name + lore + rarity etc.
                    String fullText = concatenateTooltip(fullTooltip);

                    if (fullText.isBlank()) {
                        if (player != null) player.sendSystemMessage(Component.literal(
                                I18nHelper.getPrefixed("translex.error.content_empty")));
                        return;
                    }

                    // 3. 提交翻译，传入原始 Component 对象用于模板提取（force=true 时忽略行级缓存）
                    this.translationManager.translateItemLoreTemplates(
                            fullTooltip, itemId, itemDisplayName, lastHoveredItem, force);

                    // temporary 模式：激活当前悬停槽位的译文显示门控（#3）。
                    // 用按 P 当帧的悬停槽位号 + 本次 tooltip 的 loreHash，与异步翻译解耦。
                    if ("temporary".equals(ModConfig.get().outputMode)) {
                        HoverSlotTracker.activate(
                                HoverSlotTracker.getHoverSlotId(),
                                TooltipKeyUtil.loreHash(fullTooltip));
                    }

                    if (player != null) {
                        player.sendSystemMessage(Component.literal(
                                I18nHelper.getPrefixed(force
                                        ? "translex.info.force_retranslate"
                                        : "translex.info.request_sent")));

                        String mode = ModConfig.get().outputMode;
                        if ("temporary".equals(mode)) {
                            player.sendSystemMessage(Component.literal(
                                    I18nHelper.getPrefixed("translex.info.temp_mode_hint")));
                        } else if ("permanent".equals(mode)) {
                            player.sendSystemMessage(Component.literal(
                                    I18nHelper.getPrefixed("translex.info.perm_mode_hint")));
                        }
                    }

                } else {
                    if (player != null) {
                        player.sendSystemMessage(Component.literal(
                                I18nHelper.getPrefixed("translex.error.no_item_hovered")));
                    }
                }
            }
        }
    }

    // ===============================================================
    // 辅助方法
    // ===============================================================

    /** 检测当前是否按下了 Ctrl 键（左或右），用于 Ctrl+P 强制重译。 */
    private static boolean isCtrlDown() {
        long handle = GLFW.glfwGetCurrentContext();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    private static String concatenateTooltip(List<Component> tooltip) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tooltip.size(); i++) {
            String line = tooltip.get(i).getString();
            sb.append(COLOR_CODE_PATTERN.matcher(line).replaceAll(""));
            if (i < tooltip.size() - 1) sb.append("\n");
        }
        return sb.toString().trim();
    }
}
