package com.chronomancy.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * Эффект «Временной стазис»: замораживает цель на месте.
 *
 * Визуальные частицы («песок времени») генерируются ИСКЛЮЧИТЕЛЬНО на клиенте
 * системой {@code ChronoParticles} — сервер не рассылает particle-пакеты
 * (клиент знает замороженные сущности из TemporalStasisClientState).
 *
 * Логика замораживания (движение, AI, урон) обрабатывается в TemporalStasisEvents.
 */
public class TemporalStasisEffect extends MobEffect {

    public TemporalStasisEffect(MobEffectCategory pCategory, int pColor) {
        super(pCategory, pColor);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int pDuration, int pAmplifier) {
        return true;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int pAmplifier) {
        // Геймплейный эффект (заморозка) обрабатывается в TemporalStasisEvents;
        // здесь ничего не рассылаем — частицы целиком клиентские.
        return true;
    }
}
