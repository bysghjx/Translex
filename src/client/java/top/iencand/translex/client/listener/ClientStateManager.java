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
import top.iencand.translex.client.cache.TemporaryTooltipCache;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.keybinding.ModKeybindings;
import top.iencand.translex.client.translate.ItemPresetLibrary;
import top.iencand.translex.client.translate.TranslationManager;
import top.iencand.translex.client.util.I18nHelper;
import top.iencand.translex.client.util.ItemIdExtractor;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

@Environment(EnvType.CLIENT)
public class ClientStateManager {

    private final TranslationManager translationManager;

    /** The most recently hovered item stack. Static so the Mixin can read it. */
    private static volatile ItemStack lastHoveredItem = null;

    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("§[0-9a-fk-or]");

    public ClientStateManager(TranslationManager translationManager) {
        this.translationManager = translationManager;
    }

    /** Called by the tooltip replacement Mixin. */
    public static ItemStack getLastHoveredItem() {
        return lastHoveredItem;
    }

    public void registerEvents() {
        // Tooltip callback: track hovered item + temp cache cleanup only.
        // Actual replacement is done by ScreenTooltipMixin + DrawContextTooltipMixin.
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            if (lastHoveredItem != null && lastHoveredItem != stack) {
                TemporaryTooltipCache.remove(lastHoveredItem);
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

                    // 1. Get full tooltip via getTooltipFromItem (same method the Mixin hooks).
                    //    This ensures line count matches between translation and replacement.
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

                    // 3. Submit translation of the FULL tooltip
                    this.translationManager.translateItemLoreAsync(
                            fullText, itemId, itemDisplayName, lastHoveredItem);

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

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static String concatenateTooltip(List<Text> tooltip) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tooltip.size(); i++) {
            String line = tooltip.get(i).getString();
            // Strip color codes for AI
            sb.append(COLOR_CODE_PATTERN.matcher(line).replaceAll(""));
            if (i < tooltip.size() - 1) sb.append("\n");
        }
        return sb.toString().trim();
    }
}
