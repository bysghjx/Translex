package top.iencand.translex.client.keybinding;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyMapping.Category;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import top.iencand.translex.client.web.ConsoleBroadcaster;

/**
 * Translex client key bindings.
 */
@Environment(EnvType.CLIENT)
public final class ModKeybindings {

    public static final Category GENERAL_CATEGORY = Category.register(
            Identifier.fromNamespaceAndPath("translex", "general")
    );

    public static final KeyMapping TRANSLATE_LORE_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.translex.translate_lore",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_P,
                    GENERAL_CATEGORY
            )
    );

    /**
     * Registered only when debug mode is enabled at client startup.
     * In normal use this key does not appear in Minecraft's controls.
     */
    public static KeyMapping HARVEST_KEY;

    public static void register(boolean debug) {
        if (debug) {
            HARVEST_KEY = KeyMappingHelper.registerKeyMapping(
                    new KeyMapping(
                            "key.translex.harvest",
                            InputConstants.Type.KEYSYM,
                            InputConstants.UNKNOWN.getValue(),
                            GENERAL_CATEGORY
                    )
            );
        }
        ConsoleBroadcaster.broadcast("DEBUG", "Translex key bindings registered");
    }

    private ModKeybindings() {
    }
}
