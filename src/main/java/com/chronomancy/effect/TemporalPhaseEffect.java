package com.chronomancy.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * «Фазирование»: сущность наполовину вне времени — снаряды проходят сквозь неё, урон от снарядов
 * не проходит. Дают Time Walk игроку и уклонение хрономальным мобам. Сам эффект ничего не делает:
 * проверка — в {@code PhaseDodge}.
 */
public class TemporalPhaseEffect extends MobEffect {
    public TemporalPhaseEffect(MobEffectCategory category, int color) {
        super(category, color);
    }
}
