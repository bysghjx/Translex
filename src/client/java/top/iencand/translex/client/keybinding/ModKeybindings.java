package top.iencand.translex.client.keybinding;

import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper; // 导入 Fabric KeyBinding 注册帮助类
import net.minecraft.client.option.KeyBinding; // 导入 Minecraft 的 KeyBinding 类
import net.minecraft.client.util.InputUtil; // 导入 InputUtil 用于按键类型和代码
import org.lwjgl.glfw.GLFW; // 导入 GLFW 用于按键代码常量

@Environment(EnvType.CLIENT)
public class ModKeybindings {
    public static final KeyBinding REMOVE_BLOCK_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                    "key.translex.remove_block", // 翻译键
                    InputUtil.Type.KEYSYM, // 按键类型：键盘按键
                    GLFW.GLFW_KEY_DELETE, // 默认按键代码 (GLFW.GLFW_KEY_DELETE 对应 Delete 键)
                    "category.translex.general" // 按键分类翻译键
            )
    );

    // 2. 翻译物品 Lore 按键 (原 Forge Keyboard.KEY_P 对应 P 键)
    // 注意：这里直接在类中定义并注册了，Main 类中不再需要单独定义字段和 regKeys 方法
    public static final KeyBinding TRANSLATE_LORE_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                    "key.translex.translate_lore", // 翻译键
                    InputUtil.Type.KEYSYM, // 按键类型：键盘按键
                    GLFW.GLFW_KEY_P, // 默认按键代码 (GLFW.GLFW_KEY_P 对应 P 键)
                    "category.translex.general" // 按键分类翻译键
            )
    );

    public static void register() {
        // 当这个方法被调用时，上面的静态字段初始化就会执行 KeyBindingHelper.registerKeyBinding()
        // 打印一条日志确认注册被触发
        System.out.println("[Translex] Registering keybindings...");
        // 可以选择在这里再次打印每个按键的信息，用于调试
        // System.out.println("  - " + REMOVE_BLOCK_KEY.getTranslationKey() + " bound to " + REMOVE_BLOCK_KEY.getBoundKey().getTranslationKey());
        // System.out.println("  - " + TRANSLATE_LORE_KEY.getTranslationKey() + " bound to " + TRANSLATE_LORE_KEY.getBoundKey().getTranslationKey());
        System.out.println("[Translex] Keybindings registration complete.");
    }

    private ModKeybindings() {
    }
}
