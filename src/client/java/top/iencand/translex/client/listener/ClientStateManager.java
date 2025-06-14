package top.iencand.translex.client.listener;

import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents; // 确保导入 ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents; // 确保导入 ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
// 导入 TooltipContext, List<Text>, 和 TooltipType
import net.minecraft.item.Item.TooltipContext;
import java.util.List;
import net.minecraft.item.tooltip.TooltipType; // 确保导入 TooltipType
// 新增导入：用于新的 Lore Component API
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
// 新增导入：用于新的 NBT API (虽然 getLore 不直接用，但其他地方可能需要)
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import org.jetbrains.annotations.NotNull;
import top.iencand.translex.client.keybinding.ModKeybindings;
import top.iencand.translex.client.Translate.TranslationManager;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

@Environment(EnvType.CLIENT)
public class ClientStateManager {

    private final TranslationManager translationManager;
    private ItemStack lastHoveredItem = null;
    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("§[0-9a-fk-or]");

    public ClientStateManager(TranslationManager translationManager) {
        this.translationManager = translationManager;
        System.out.println("[Translex] ClientStateManager created.");
    }

    public void registerEvents() {
        // --- 1. 监听 ItemTooltipCallback 来捕获悬停物品 ---
        // 1.20.5+ 的 ItemTooltipCallback 签名是 (stack, context, type, lines)
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            this.lastHoveredItem = stack;
            // System.out.println("[Translex] ItemTooltipCallback: lastHoveredItem set to: " + (stack != null ? stack.getName().getString() : "null")); // 调试日志
            // 您可以在这里使用 type 参数，例如：
            // if (type == TooltipType.ADVANCED) { /* 只在高级 Tooltip 时做某事 */ }
        });
        System.out.println("[Translex] ItemTooltipCallback registered.");

        // --- 2. 监听 ScreenEvents (GUI 打开/关闭) ---

        // 根据您提供的 ScreenEvents 源代码，AFTER_INIT 需要四个参数
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            System.out.println("[Translex] ScreenEvents.AFTER_INIT: GUI opened: " + screen.getClass().getName() + ". Clearing lastHoveredItem.");
            // 在任何新屏幕打开时清除 lastHoveredItem
            this.lastHoveredItem = null;

            // 如果打开的是 HandledScreen，则注册该屏幕的键盘事件监听
            if (screen instanceof HandledScreen) {
                System.out.println("[Translex] Registering keyboard event for HandledScreen: " + screen.getClass().getName());
                // 根据您提供的 ScreenKeyboardEvents 源代码，方法名是 afterKeyPress 且需要四个参数
                ScreenKeyboardEvents.afterKeyPress(screen).register((currentScreen, key, scancode, modifiers) -> {
                    onGuiKeyPress(currentScreen, key, scancode, modifiers);
                });
            }

            // *** 根据您提供的 ScreenEvents 源代码，监听特定屏幕关闭的方式是使用 ScreenEvents.remove(screen) 方法 ***
            // 为当前打开的这个屏幕实例注册移除事件监听器
            ScreenEvents.remove(screen).register((removedScreen) -> {
                System.out.println("[Translex] ScreenEvents.remove: GUI closed: " + removedScreen.getClass().getName() + ". Clearing lastHoveredItem.");
                // 在屏幕关闭时清除 lastHoveredItem
                this.lastHoveredItem = null;
            });
        });
        System.out.println("[Translex] ScreenEvents.AFTER_INIT registered.");

        // *** 根据您提供的 ScreenEvents 源代码，ScreenEvents.REMOVE 静态事件不存在 ***
        // *** 因此移除或注释掉以下代码，并依赖上面在 AFTER_INIT 中为每个屏幕注册的 remove(screen) 监听器 ***
        /*
        ScreenEvents.REMOVE.register((screen) -> { // <-- 这行会报错，因为 REMOVE 不是静态字段
            System.out.println("[Translex] ScreenEvents.REMOVE: GUI closed: " + screen.getClass().getName() + ". Clearing lastHoveredItem.");
            this.lastHoveredItem = null;
        });
        System.out.println("[Translex] ScreenEvents.REMOVE registered.");
        */

        // 如果您确认您的 Fabric API 版本确实没有 AFTER_CLOSE 全局事件，
        // 那么依赖在 AFTER_INIT 中注册的 ScreenEvents.remove(screen) 是监听屏幕关闭的替代方案。
        // 请注意，这种方式只监听通过 AFTER_INIT 打开的屏幕的关闭事件。
    }

    private void onGuiKeyPress(Screen screen, int key, int scancode, int modifiers) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;

        // 检查按下的键是否是我们的翻译 Lore 键绑定
        if (ModKeybindings.TRANSLATE_LORE_KEY.matchesKey(key, scancode)) {
            System.out.println("[Translex GUI_KEY] 'Translate Lore' key matched.");

            // 确保当前屏幕是 HandledScreen (背包、箱子等)
            if (screen instanceof HandledScreen) {
                System.out.println("[Translex GUI_KEY] Current screen IS a HandledScreen.");

                // 检查是否有悬停的物品
                if (this.lastHoveredItem != null && !this.lastHoveredItem.isEmpty()) {
                    System.out.println("[Translex GUI_KEY] Last hovered item IS NOT NULL and not empty: " + this.lastHoveredItem.getName().getString());

                    String itemDisplayName = this.lastHoveredItem.getName().getString();
                    List<Text> loreLines = getLore(this.lastHoveredItem);
                    String rawLoreText = concatenateLore(loreLines);

                    if (rawLoreText.isEmpty()) {
                        System.out.println("[Translex GUI_KEY] Raw Lore text is empty.");
                        // TODO: 迁移到语言文件
                        if (player != null) player.sendMessage(Text.literal("§c[翻译 Mod]§r 当前悬停物品没有 Lore 需要翻译！"), false);
                        return;
                    }

                    String cleanedLoreText = removeColorCodes(rawLoreText).trim();

                    if (cleanedLoreText.isEmpty()) {
                        System.out.println("[Translex GUI_KEY] Cleaned Lore text is empty.");
                        // TODO: 迁移到语言文件
                        if (player != null) player.sendMessage(Text.literal("§c[翻译 Mod]§r 当前悬停物品的 Lore 清理后为空，无需翻译。"), false);
                        return;
                    }

                    String context = "Lore for " + itemDisplayName;
                    System.out.println("[Translex GUI_KEY] Attempting to call translationManager for item: " + itemDisplayName + ", Lore: " + cleanedLoreText);

                    // 调用翻译管理器进行异步翻译
                    this.translationManager.translateTextAsync(cleanedLoreText, context);

                    // TODO: 迁移到语言文件
                    if (player != null) player.sendMessage(Text.literal("§a[翻译 Mod]§r 已发送当前悬停物品 Lore 的翻译请求..."), false);

                } else {
                    System.out.println("[Translex GUI_KEY] Last hovered item IS NULL or empty.");
                    // TODO: 迁移到语言文件
                    if (player != null) player.sendMessage(Text.literal("§c[翻译 Mod]§r (调试) lastHoveredItem 为空或无物品。请先将鼠标悬停在物品上。"), false);
                }
            } else {
                System.out.println("[Translex GUI_KEY] Current screen is not a HandledScreen. It is: " + screen.getClass().getName());
            }
        }
    }

    // 使用新的 Component API 获取物品 Lore
    public static @NotNull List<Text> getLore(ItemStack stack) {
        // DataComponentTypes.LORE 是 1.20.5+ 获取 Lore 的标准方式
        return stack.getOrDefault(DataComponentTypes.LORE, LoreComponent.DEFAULT).styledLines();
    }

    // 将多行 Lore 合并为单个字符串
    public static @NotNull String concatenateLore(@NotNull List<Text> lore) {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < lore.size(); i++) {
            stringBuilder.append(lore.get(i).getString());
            if (i < lore.size() - 1) stringBuilder.append("\n"); // 使用换行符分隔
        }
        return stringBuilder.toString();
    }

    // 移除 Minecraft 颜色和格式化代码
    private static String removeColorCodes(String text) {
        if (text == null) return null;
        Matcher matcher = COLOR_CODE_PATTERN.matcher(text);
        return matcher.replaceAll("");
    }

    // TODO: 如果您将聊天消息迁移到语言文件，请修改相关消息的硬编码字符串。
    // TODO: 确认 TranslationManager 的回调在主线程执行与客户端UI/消息交互的操作。
}