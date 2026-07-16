package top.iencand.translex.client.keybinding;

import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyMapping.Category; // Category 静态内部类
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.resources.Identifier; // 用于创建分类标识符
import org.lwjgl.glfw.GLFW;
import top.iencand.translex.client.web.ConsoleBroadcaster;

/**
 * 模组按键绑定注册。
 * 使用 Fabric API 的 KeyBindingHelper 注册所有自定义快捷键。
 */
@Environment(EnvType.CLIENT)
public class ModKeybindings {

    /** 通用按键分类，在设置界面中分组显示 */
    // 使用 Category.register(Identifier) 注册按键分类
    public static final Category GENERAL_CATEGORY = Category.register(
            Identifier.fromNamespaceAndPath("translex", "general")
    );

    /** 删除标记物品的按键（默认 DELETE 键） */
    public static final KeyMapping REMOVE_BLOCK_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.translex.remove_block",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_DELETE,
                    GENERAL_CATEGORY
            )
    );

    /** 翻译鼠标悬停物品说明的按键（默认 P 键） */
    public static final KeyMapping TRANSLATE_LORE_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.translex.translate_lore",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_P,
                    GENERAL_CATEGORY
            )
    );

    /** 采集当前 GUI 所有物品 tooltip 存本地（TSP 测试数据收集）。
     *  默认无绑定（禁用）--数据采集完成后禁用避免误按；需要时在控制设置手动绑键。
     *  必须用键位而非命令：容器 GUI 里按 T 打开聊天会关闭容器，命令拿不到 screen。 */
    public static final KeyMapping HARVEST_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.translex.harvest",
                    InputConstants.Type.KEYSYM,
                    InputConstants.UNKNOWN.getValue(),  // 默认无绑定（禁用）
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