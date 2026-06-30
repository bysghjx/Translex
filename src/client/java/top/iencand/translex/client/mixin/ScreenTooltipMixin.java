package top.iencand.translex.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.iencand.translex.client.config.ModConfig;
import top.iencand.translex.client.translate.model.LineTemplate;
import top.iencand.translex.client.translate.model.TranslexTooltipContext;

import java.util.List;

/**
 * 主工具提示替换 Mixin。注入到 {@link Screen#getTooltipFromItem} 的 RETURN 点，
 * 直接接收 {@link ItemStack}，并用缓存的翻译结果替换物品说明行，
 * 同时保留每行的原始 {@link net.minecraft.network.chat.Style}。
 *
 * <p>工作流程：
 * <ol>
 *   <li>检查输出模式（chat 模式跳过）</li>
 *   <li>从 TranslexTooltipContext 查找缓存翻译</li>
 *   <li>跳过第 0 行（物品名称），从第 1 行开始替换说明行</li>
 *   <li>标记屏幕已处理，避免 DrawContextTooltipMixin 重复替换</li>
 * </ol>
 */
@Mixin(Screen.class)
public abstract class ScreenTooltipMixin {

    /**
     * 防止递归构建的保护标志（避免 getTooltipFromItem 被重复调用时的死循环）
     */
    @Unique
    private static final ThreadLocal<Boolean> BUILDING = ThreadLocal.withInitial(() -> false);

    @Inject(
            method = "getTooltipFromItem(Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void translex$replaceItemTooltip(
            Minecraft client,
            ItemStack stack,
            CallbackInfoReturnable<List<Component>> cir
    ) {
        if (BUILDING.get()) return;
        if (stack == null || stack.isEmpty()) return;

        // chat 模式不替换工具提示
        String mode = ModConfig.get().outputMode;
        if ("chat".equals(mode)) return;

        List<Component> original = cir.getReturnValue();
        List<String> replacement = TranslexTooltipContext.lookupReplacement(stack, mode, original);
        if (replacement == null || replacement.isEmpty()) return;

        BUILDING.set(true);
        try {
            // 跳过第 0 行（物品名称），只替换后续的说明行（lore）
            for (int i = 1; i < original.size() && i < replacement.size(); i++) {
                original.set(i, LineTemplate.fromText(original.get(i)).buildText(replacement.get(i)));
            }
            cir.setReturnValue(original);
            TranslexTooltipContext.markScreenHandled();
        } finally {
            BUILDING.set(false);
        }
    }
}
