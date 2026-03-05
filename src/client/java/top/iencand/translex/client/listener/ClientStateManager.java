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
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.client.input.KeyInput;
import org.jetbrains.annotations.NotNull;
import top.iencand.translex.client.keybinding.ModKeybindings;
import top.iencand.translex.client.Translate.TranslationManager;
import top.iencand.translex.client.util.I18nHelper; // 导入工具类

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

@Environment(EnvType.CLIENT)
public class ClientStateManager {

    private final TranslationManager translationManager;
    private ItemStack lastHoveredItem = null;
    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("§[0-9a-fk-or]");

    public ClientStateManager(TranslationManager translationManager) {
        this.translationManager = translationManager;
    }

    public void registerEvents() {
        // 1. 监听悬停物品
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            this.lastHoveredItem = stack;
        });

        // 2. 监听屏幕初始化与按键
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            this.lastHoveredItem = null;

            if (screen instanceof HandledScreen) {
                ScreenKeyboardEvents.afterKeyPress(screen).register(this::onGuiKeyPress);
            }

            ScreenEvents.remove(screen).register((removedScreen) -> {
                this.lastHoveredItem = null;
            });
        });
    }

    private void onGuiKeyPress(Screen screen, KeyInput input) {
        if (ModKeybindings.TRANSLATE_LORE_KEY.matchesKey(input)) {
            MinecraftClient mc = MinecraftClient.getInstance();
            ClientPlayerEntity player = mc.player;

            if (screen instanceof HandledScreen) {
                // 检查是否有悬停物品
                if (this.lastHoveredItem != null && !this.lastHoveredItem.isEmpty()) {

                    String itemDisplayName = this.lastHoveredItem.getName().getString();
                    List<Text> loreLines = getLore(this.lastHoveredItem);
                    String rawLoreText = concatenateLore(loreLines);

                    // 错误处理 1：Lore 为空 (使用 translex.error.content_empty)
                    if (rawLoreText.isEmpty()) {
                        if (player != null) player.sendMessage(Text.literal(I18nHelper.getPrefixed("translex.error.content_empty")), false);
                        return;
                    }

                    String cleanedLoreText = removeColorCodes(rawLoreText).trim();

                    // 错误处理 2：清理后为空 (使用 translex.error.content_empty)
                    if (cleanedLoreText.isEmpty()) {
                        if (player != null) player.sendMessage(Text.literal(I18nHelper.getPrefixed("translex.error.content_empty")), false);
                        return;
                    }

                    // 成功发送请求提示 (使用 translex.info.request_sent)
                    String context = "Lore: " + itemDisplayName;
                    this.translationManager.translateTextAsync(cleanedLoreText, context);

                    if (player != null) {
                        player.sendMessage(Text.literal(I18nHelper.getPrefixed("translex.info.request_sent")), false);
                    }

                } else {
                    // 调试/错误：未悬停物品 (使用 translex.error.no_item_hovered)
                    if (player != null) {
                        player.sendMessage(Text.literal(I18nHelper.getPrefixed("translex.error.no_item_hovered")), false);
                    }
                }
            }
        }
    }

    public static @NotNull List<Text> getLore(ItemStack stack) {
        return stack.getOrDefault(DataComponentTypes.LORE, LoreComponent.DEFAULT).styledLines();
    }

    public static @NotNull String concatenateLore(@NotNull List<Text> lore) {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < lore.size(); i++) {
            stringBuilder.append(lore.get(i).getString());
            if (i < lore.size() - 1) stringBuilder.append("\n");
        }
        return stringBuilder.toString();
    }

    private static String removeColorCodes(String text) {
        if (text == null) return null;
        Matcher matcher = COLOR_CODE_PATTERN.matcher(text);
        return matcher.replaceAll("");
    }
}