package top.iencand.translex.client.keybinding;

import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.KeyBinding.Category; // Category 静态内部类
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier; // 用于创建分类标识符
import org.lwjgl.glfw.GLFW;
import top.iencand.translex.client.web.ConsoleBroadcaster;

/**
 * 模组按键绑定注册。
 * 使用 Fabric API 的 KeyBindingHelper 注册所有自定义快捷键。
 */
@Environment(EnvType.CLIENT)
public class ModKeybindings {

    /** 通用按键分类，在设置界面中分组显示 */
    // 使用 Category.create(Identifier) 注册按键分类
    public static final Category GENERAL_CATEGORY = Category.create(
            Identifier.of("translex", "general")
    );

    /** 删除标记物品的按键（默认 DELETE 键） */
    public static final KeyBinding REMOVE_BLOCK_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                    "key.translex.remove_block",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_DELETE,
                    GENERAL_CATEGORY
            )
    );

    /** 翻译鼠标悬停物品说明的按键（默认 P 键） */
    public static final KeyBinding TRANSLATE_LORE_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                    "key.translex.translate_lore",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_P,
                    GENERAL_CATEGORY
            )
    );

    /** 注册所有按键绑定（在客户端初始化时调用） */
    public static void register() {
        ConsoleBroadcaster.broadcast("DEBUG", "按键绑定已注册");
    }

    /** 工具类，禁止实例化 */
    private ModKeybindings() {
    }
}