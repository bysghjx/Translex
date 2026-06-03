package top.iencand.translex.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.translate.TranslexTooltipContext;

import java.util.List;

/**
 * Primary tooltip replacement. Injects at RETURN of
 * {@link Screen#getTooltipFromItem}, receives {@link ItemStack}
 * directly, and replaces lore lines with cached translations
 * while preserving each line's original {@link net.minecraft.text.Style}.
 */
@Mixin(Screen.class)
public abstract class ScreenTooltipMixin {

    @Unique
    private static final ThreadLocal<Boolean> BUILDING = ThreadLocal.withInitial(() -> false);

    @Inject(
            method = "getTooltipFromItem(Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/item/ItemStack;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void translex$replaceItemTooltip(
            MinecraftClient client,
            ItemStack stack,
            CallbackInfoReturnable<List<Text>> cir
    ) {
        if (BUILDING.get()) return;
        if (stack == null || stack.isEmpty()) return;

        String mode = ModConfig.get().outputMode;
        if ("chat".equals(mode)) return;

        List<String> replacement = TranslexTooltipContext.lookupReplacement(stack, mode);
        if (replacement == null || replacement.isEmpty()) return;

        List<Text> original = cir.getReturnValue();

        BUILDING.set(true);
        try {
            for (int i = 1; i < original.size() && i < replacement.size(); i++) {
                original.set(i, Text.literal(replacement.get(i))
                        .setStyle(original.get(i).getStyle()));
            }
            cir.setReturnValue(original);
            TranslexTooltipContext.markScreenHandled();
        } finally {
            BUILDING.set(false);
        }
    }
}
