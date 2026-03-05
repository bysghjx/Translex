package top.iencand.translex.client.keybinding;

import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.KeyBinding.Category; // 导入 Category 静态内部类
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier; // 导入 Identifier
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class ModKeybindings {

    // *** 关键修改：使用 Category.create(Identifier id) 来注册 ***
    // 推荐使用 Identifier.of(modId, name) 来确保唯一性
    // 假设您的 Mod ID 是 "translex"
    public static final Category GENERAL_CATEGORY = Category.create(
            Identifier.of("translex", "general")
    );

    // 或者，如果您只需要使用翻译键，也可以使用 create(Identifier)
    // 但推荐使用上面的方式，因为它与您提供的源代码结构更匹配
    // public static final Category GENERAL_CATEGORY = Category.create(Identifier.of("key.category", "translex_general"));

    public static final KeyBinding REMOVE_BLOCK_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                    "key.translex.remove_block",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_DELETE,
                    GENERAL_CATEGORY // 使用 Category 对象
            )
    );

    public static final KeyBinding TRANSLATE_LORE_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                    "key.translex.translate_lore",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_P,
                    GENERAL_CATEGORY // 使用 Category 对象
            )
    );

    public static void register() {
        System.out.println("[Translex] Registering keybindings...");
        System.out.println("[Translex] Keybindings registration complete.");
    }

    private ModKeybindings() {
    }
}