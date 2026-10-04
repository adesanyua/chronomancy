package com.chronomancy.item;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.registry.ChronoAttributes;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.spells.IPresetSpellContainer;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import io.redspace.ironsspellbooks.item.armor.ImbuableChestplateArmorItem;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import software.bernie.geckolib.renderer.GeoArmorRenderer;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/** Each equipped piece: +125 mana, +10% Chronomancy power, +5% general spell power. */
public final class ClocksmithArmorItem extends ImbuableChestplateArmorItem implements IPresetSpellContainer {
    private ItemAttributeModifiers boosted;
    public ClocksmithArmorItem(Holder<ArmorMaterial> material, Type type, Properties properties) {
        super(material, type, properties);
    }
    @Override public ItemAttributeModifiers getDefaultAttributeModifiers() {
        if (boosted == null) {
            EquipmentSlotGroup slot = EquipmentSlotGroup.bySlot(getType().getSlot());
            ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
            for (ItemAttributeModifiers.Entry entry : super.getDefaultAttributeModifiers().modifiers()) {
                builder.add(entry.attribute(), entry.modifier(), entry.slot());
            }
            bonus(builder, AttributeRegistry.MAX_MANA, 125, AttributeModifier.Operation.ADD_VALUE, slot, "mana");
            bonus(builder, ChronoAttributes.CHRONOMANCY_SPELL_POWER, .10,
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE, slot, "chronomancy_power");
            bonus(builder, AttributeRegistry.SPELL_POWER, .05,
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE, slot, "spell_power");
            boosted = builder.build();
        }
        return boosted;
    }
    private void bonus(ItemAttributeModifiers.Builder builder, Holder<Attribute> attribute,
                       double amount, AttributeModifier.Operation operation, EquipmentSlotGroup slot, String name) {
        builder.add(attribute, new AttributeModifier(ResourceLocation.fromNamespaceAndPath(
                ChronomancyMod.MODID, "clocksmith_" + getType().getName() + "_" + name), amount, operation), slot);
    }
    @Override
    @OnlyIn(Dist.CLIENT)
    public GeoArmorRenderer<?> supplyRenderer() {
        return new io.redspace.ironsspellbooks.entity.armor.GenericCustomArmorRenderer<>(
                new com.chronomancy.client.equipment.ClockworkGeoModel<ClocksmithArmorItem>("clocksmith"));
    }

    @Override public void initializeSpellContainer(ItemStack stack) {
        if (getType() == Type.CHESTPLATE && !ISpellContainer.isSpellContainer(stack)) {
            ISpellContainer.set(stack, ISpellContainer.create(1, true, true));
        }
    }
}
