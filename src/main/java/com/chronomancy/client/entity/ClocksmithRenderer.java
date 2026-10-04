package com.chronomancy.client.entity;

import io.redspace.ironsspellbooks.entity.mobs.abstract_spell_casting_mob.AbstractSpellCastingMobRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * Рендер Часовщика — штатный рендер магов Iron's Spells: он сам рисует надетую броню (в том числе
 * GeckoLib-броню Clocksmith), предмет в руке и анимации каста.
 */
public class ClocksmithRenderer extends AbstractSpellCastingMobRenderer {
    public ClocksmithRenderer(EntityRendererProvider.Context context) {
        super(context, new ClocksmithModel());
    }
}
