package com.chronomancy.registry;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.effect.TemporalStasisEffect;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ChronoMobEffectRegistry {
    public static final DeferredRegister<MobEffect> MOB_EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, ChronomancyMod.MODID);

    public static final DeferredHolder<MobEffect, MobEffect> TEMPORAL_STASIS = MOB_EFFECTS.register(
            "temporal_stasis",
            () -> new TemporalStasisEffect(MobEffectCategory.HARMFUL, 0xE4C660) // песочный цвет школы хрономантии
    );

    /** Индикатор стаков Time-Piercing Needle на цели (уровень = число игл). */
    public static final DeferredHolder<MobEffect, MobEffect> TEMPORAL_NEEDLES = MOB_EFFECTS.register(
            "temporal_needles",
            () -> new com.chronomancy.effect.TemporalNeedlesEffect(MobEffectCategory.HARMFUL, 0xF2C868)
    );

    /** Фазирование: снаряды проходят насквозь (Time Walk, уклонение хрономальных мобов). */
    public static final DeferredHolder<MobEffect, MobEffect> TEMPORAL_PHASE = MOB_EFFECTS.register(
            "temporal_phase",
            () -> new com.chronomancy.effect.TemporalPhaseEffect(MobEffectCategory.BENEFICIAL, 0x6EC8FF)
    );

    /** Слои песка заклинания Sands of Time (уровень = число слоёв; сам темп считает {@code SandsOfTime}). */
    public static final DeferredHolder<MobEffect, MobEffect> SANDS_OF_TIME = MOB_EFFECTS.register(
            "sands_of_time",
            () -> new com.chronomancy.effect.TemporalNeedlesEffect(MobEffectCategory.HARMFUL, 0xD9B45A)
    );

    /** Парадокс: время выжившего после временного взрыва то несётся, то вязнет (см. {@code TimeParadox}). */
    public static final DeferredHolder<MobEffect, MobEffect> PARADOX = MOB_EFFECTS.register(
            "paradox",
            () -> new com.chronomancy.effect.TemporalNeedlesEffect(MobEffectCategory.HARMFUL, 0x9A6BFF)
    );

    public static void register(IEventBus eventBus) {
        MOB_EFFECTS.register(eventBus);
    }
}