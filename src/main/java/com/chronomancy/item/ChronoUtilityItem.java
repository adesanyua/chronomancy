package com.chronomancy.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;

/** Curio bound to one slot; its description is drawn by {@code ChronoItemTooltips}. */
public final class ChronoUtilityItem extends CurioBaseItem<ChronoUtilityItem> {
    private final String equipSlot;
    public ChronoUtilityItem(Item.Properties properties, String equipSlot) {
        super(properties, equipSlot);
        this.equipSlot = equipSlot;
    }
    @Override public boolean canEquip(SlotContext context, ItemStack stack) {
        return equipSlot.equals(context.identifier());
    }
    @Override public boolean canEquipFromUse(SlotContext context, ItemStack stack) {
        return canEquip(context, stack);
    }
}
