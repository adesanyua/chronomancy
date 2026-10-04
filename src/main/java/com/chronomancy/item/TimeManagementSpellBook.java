package com.chronomancy.item;

import com.chronomancy.registry.ChronoAttributes;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.item.SpellBook;
import io.redspace.ironsspellbooks.item.weapons.AttributeContainer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * A normal editable ISS spellbook; bonuses apply only in the spellbook Curios slot.
 *
 * <p>Besides mana and Chronomancy power, the worn book releases its owner's continuous spells five
 * times faster at full damage (see {@link TimelessBookCasting}).
 */
public final class TimeManagementSpellBook extends SpellBook {
    public static final int SPELL_SLOTS = 12;
    public TimeManagementSpellBook(Properties properties) {
        super(SPELL_SLOTS, properties);
        withSpellbookAttributes(
                new AttributeContainer(AttributeRegistry.MAX_MANA, 100, AttributeModifier.Operation.ADD_VALUE),
                new AttributeContainer(ChronoAttributes.CHRONOMANCY_SPELL_POWER, .10, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }
}
