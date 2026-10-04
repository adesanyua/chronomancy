package com.chronomancy.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * «Временные иглы» — индикатор на цели: сколько игл Time-Piercing Needle в ней засело.
 * Уровень эффекта = число стаков (суммарно по всем цепочкам), длительность — время до
 * схлопывания. Сам эффект ничего не делает: стаки и урон ведёт {@code TemporalNeedleManager},
 * он же выставляет и снимает эффект.
 */
public class TemporalNeedlesEffect extends MobEffect {
    public TemporalNeedlesEffect(MobEffectCategory category, int color) {
        super(category, color);
    }
}
