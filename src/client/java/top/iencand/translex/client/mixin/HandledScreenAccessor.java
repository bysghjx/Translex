package top.iencand.translex.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问器 Mixin，暴露 {@link AbstractContainerScreen#hoveredSlot}（protected 字段）的读取，
 * 供 {@link top.iencand.translex.client.listener.HoverSlotTracker} 每帧获知当前悬停槽位。
 *
 * <p>注：26.1.2 / Mojang 映射下该字段名为 {@code hoveredSlot}（Yarn 1.21.11 为 {@code focusedSlot}）。</p>
 */
@Mixin(AbstractContainerScreen.class)
public interface HandledScreenAccessor {

    /** 当前鼠标悬停的槽位；未悬停于任何槽位时为 {@code null}。 */
    @Accessor("hoveredSlot")
    Slot translex$getHoveredSlot();
}
