package top.iencand.translex.client.util;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

/**
 * Extracts the SkyBlock item ID from NBT at {@code ExtraAttributes.id}.
 */
public final class ItemIdExtractor {

    private ItemIdExtractor() {}

    /**
     * Read the SkyBlock internal item ID (e.g. "HYPERION", "ASPECT_OF_THE_END")
     * from the item's custom NBT data.
     *
     * @return the item ID, or null if not present
     */
    public static String extractSkyBlockItemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData == null) {
            System.out.println("[Translex-ItemId] CUSTOM_DATA is null for " + stack.getName().getString());
            return null;
        }

        NbtCompound nbt = customData.copyNbt();

        // Path 1: ExtraAttributes.id (older Hypixel format)
        if (nbt.contains("ExtraAttributes")) {
            NbtCompound extraAttrs = nbt.getCompound("ExtraAttributes").orElse(null);
            if (extraAttrs != null && extraAttrs.contains("id")) {
                String id = extraAttrs.getString("id").orElse(null);
                System.out.println("[Translex-ItemId] Extracted from ExtraAttributes.id: " + id);
                return id;
            }
        }

        // Path 2: id directly at root (newer/flat format)
        if (nbt.contains("id")) {
            String id = nbt.getString("id").orElse(null);
            System.out.println("[Translex-ItemId] Extracted from root id: " + id);
            return id;
        }

        System.out.println("[Translex-ItemId] No id found. NBT keys: " + nbt.getKeys());
        return null;
    }
}
