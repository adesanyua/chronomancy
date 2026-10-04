package com.chronomancy.item;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.registry.ChronoAttributes;
import com.chronomancy.registry.ChronoItemRegistry;
import com.chronomancy.registry.ChronoSpellRegistry;
import io.redspace.ironsspellbooks.api.item.weapons.ExtendedSwordItem;
import io.redspace.ironsspellbooks.api.item.weapons.MagicSwordItem;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellDataRegistryHolder;
import io.redspace.ironsspellbooks.item.weapons.AttributeContainer;
import io.redspace.ironsspellbooks.item.weapons.ExtendedWeaponTier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * RIFT CREATOR — легендарный клинок-разлом, трофей босса Rift Maker. Несёт уникальное заклинание
 * {@link com.chronomancy.spell.RiftSpell Rift}; бонус к силе хрономантии и перезарядке.
 */
public final class RiftCreatorItem extends MagicSwordItem {

    public static final ExtendedWeaponTier TIER = new ExtendedWeaponTier(2600, 11, -2.4F, 22,
            BlockTags.INCORRECT_FOR_NETHERITE_TOOL,
            () -> Ingredient.of(ChronoItemRegistry.CHRONOMALY_SHARD.get()),
            new AttributeContainer(ChronoAttributes.CHRONOMANCY_SPELL_POWER, 0.20, AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
            new AttributeContainer(AttributeRegistry.COOLDOWN_REDUCTION, 0.10, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));

    public RiftCreatorItem(Properties properties) {
        super(TIER, properties.rarity(Rarity.EPIC).fireResistant().stacksTo(1)
                        .attributes(ExtendedSwordItem.createAttributes(TIER)),
                SpellDataRegistryHolder.of(new SpellDataRegistryHolder(() -> ChronoSpellRegistry.RIFT_SPELL, 1)));
    }

    @Override
    public Component getName(ItemStack stack) {
        return super.getName(stack).copy().withStyle(ChatFormatting.GOLD);
    }

}
