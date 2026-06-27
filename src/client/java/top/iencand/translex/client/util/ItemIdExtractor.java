package top.iencand.translex.client.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;

/**
 * 从物品 NBT 中提取 SkyBlock 物品 ID。
 * ID 位于 {@code ExtraAttributes.id} 字段，例如 "HYPERION"、"ASPECT_OF_THE_END"。
 */
public final class ItemIdExtractor {

    private ItemIdExtractor() {}

    /**
     * 从物品的自定义 NBT 数据中读取 SkyBlock 内部物品 ID。
     *
     * @param stack 物品堆栈
     * @return 物品 ID，如果不存在则返回 null
     */
    public static String extractSkyBlockItemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return null;

        CompoundTag nbt = customData.copyTag();

        if (nbt.contains("ExtraAttributes")) {
            CompoundTag extraAttrs = nbt.getCompound("ExtraAttributes").orElse(null);
            if (extraAttrs != null && extraAttrs.contains("id"))
                return extraAttrs.getString("id").orElse(null);
        }

        if (nbt.contains("id"))
            return nbt.getString("id").orElse(null);

        return null;
    }
}
