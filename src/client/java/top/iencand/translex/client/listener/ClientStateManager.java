package top.iencand.translex.client.listener;

import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.client.input.KeyInput;
import org.jetbrains.annotations.NotNull;
import top.iencand.translex.client.translate.cache.TemporaryTooltipCache;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.keybinding.ModKeybindings;
import top.iencand.translex.client.translate.model.ItemPresetLibrary;
import top.iencand.translex.client.translate.TranslationManager;
import top.iencand.translex.client.util.I18nHelper;
import top.iencand.translex.client.util.ItemIdExtractor;

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
        // 工具提示回调：追踪悬停物品 + 清理临时缓存
        // 实际的文本替换由 ScreenTooltipMixin 和 DrawContextTooltipMixin 完成
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            // Clean up temp cache when hover changes (compare by stable key, not identity)
            if (lastHoveredItem != null && lastHoveredItem != stack) {
                String lastKey = TemporaryTooltipCache.keyOf(lastHoveredItem);
                String curKey = TemporaryTooltipCache.keyOf(stack);
                if (lastKey != null && !lastKey.equals(curKey)) {
                    TemporaryTooltipCache.remove(lastHoveredItem);
                }
            }
            lastHoveredItem = stack;
        });

        // Screen events for key bindings (DO NOT clean temp cache here —
        // screen refresh would wipe the just-stored translation).
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            lastHoveredItem = null;

            if (screen instanceof HandledScreen) {
                ScreenKeyboardEvents.afterKeyPress(screen).register(this::onGuiKeyPress);
            }

            ScreenEvents.remove(screen).register((removedScreen) -> {
                lastHoveredItem = null;
            });
        });
    }

    // ---------------------------------------------------------------
    // Key press handling (translate lore on hotkey)
    // ---------------------------------------------------------------

    private void onGuiKeyPress(Screen screen, KeyInput input) {
        if (ModKeybindings.TRANSLATE_LORE_KEY.matchesKey(input)) {
            MinecraftClient mc = MinecraftClient.getInstance();
            ClientPlayerEntity player = mc.player;

            if (screen instanceof HandledScreen) {
                if (lastHoveredItem != null && !lastHoveredItem.isEmpty()) {

                    // Check API key before doing any work (skip in debug mode)
                    if (!ModConfig.get().debug) {
                        String key = ModConfig.get().apiKey;
                        if (key == null || key.isBlank() || key.equals("YOUR_API_KEY_HERE")) {
                            if (player != null) {
                                player.sendMessage(Text.literal(
                                        I18nHelper.getPrefixed("translex.error.api_key_unset")), false);
                                player.sendMessage(Text.literal(
                                        "  §e/translex config §7— " + I18nHelper.translate("translex.error.api_key_hint")), false);
                            }
                            return;
                        }
                    }

                    String itemDisplayName = lastHoveredItem.getName().getString();

                    // 0. Check ItemPresetLibrary first
                    String itemId = ItemIdExtractor.extractSkyBlockItemId(lastHoveredItem);
                    if (itemId != null) {
                        ItemPresetLibrary.ItemPreset preset = translationManager.getPresetLibrary().get(itemId);
                        if (preset != null) {
                            if (player != null) {
                                player.sendMessage(Text.literal(
                                        I18nHelper.getPrefixed("translex.info.preset_hit")), false);
                                player.sendMessage(Text.literal("§a" + preset.name), false);
                                for (String line : preset.loreLines) {
                                    player.sendMessage(Text.literal("§7" + line), false);
                                }
                            }
                            return;
                        }
                    }

                    // 1. 通过 getTooltipFromItem 获取完整工具提示（与 Mixin 使用相同方法）
                    //    确保翻译行数与替换时的行数一致
                    List<Text> fullTooltip = Screen.getTooltipFromItem(mc, lastHoveredItem);

                    if (fullTooltip.isEmpty()) {
                        if (player != null) player.sendMessage(Text.literal(
                                I18nHelper.getPrefixed("translex.error.content_empty")), false);
                        return;
                    }

                    // 2. Concatenate all lines — includes item name + lore + rarity etc.
                    String fullText = concatenateTooltip(fullTooltip);

                    if (fullText.isBlank()) {
                        if (player != null) player.sendMessage(Text.literal(
                                I18nHelper.getPrefixed("translex.error.content_empty")), false);
                        return;
                    }

                    // 3. 提交翻译，传入原始 Text 对象用于模板提取
                    this.translationManager.translateItemLoreTemplates(
                            fullTooltip, itemId, itemDisplayName, lastHoveredItem);

                    if (player != null) {
                        player.sendMessage(Text.literal(
                                I18nHelper.getPrefixed("translex.info.request_sent")), false);

                        String mode = ModConfig.get().outputMode;
                        if ("temporary".equals(mode)) {
                            player.sendMessage(Text.literal(
                                    I18nHelper.getPrefixed("translex.info.temp_mode_hint")), false);
                        } else if ("permanent".equals(mode)) {
                            player.sendMessage(Text.literal(
                                    I18nHelper.getPrefixed("translex.info.perm_mode_hint")), false);
                        }
                    }

                } else {
                    if (player != null) {
                        player.sendMessage(Text.literal(
                                I18nHelper.getPrefixed("translex.error.no_item_hovered")), false);
                    }
                }
            }
        }
    }

    // ===============================================================
    // 辅助方法
    // ===============================================================

    private static String concatenateTooltip(List<Text> tooltip) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tooltip.size(); i++) {
            String line = tooltip.get(i).getString();
            sb.append(COLOR_CODE_PATTERN.matcher(line).replaceAll(""));
            if (i < tooltip.size() - 1) sb.append("\n");
        }
        return sb.toString().trim();
    }
}
