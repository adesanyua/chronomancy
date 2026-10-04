package com.chronomancy.temporal;

import com.chronomancy.registry.ChronoMobEffectRegistry;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * ПАРАДОКС — след временного взрыва: личное время выжившего то несётся вперёд, то вязнет.
 * Темп переключается каждые {@value #SWING_TICKS} тиков между {@value #FAST_RATE} и
 * {@value #SLOW_RATE}.
 *
 * <p>Состояние — сам эффект {@code chronomancy:paradox} на существе: пока он висит, время качается;
 * сняли (молоко, истёк) — качели остановились. Отдельного учёта нет, поэтому эффект, выданный
 * командой, работает так же. Фаза качелей идёт по серверным часам ({@link ChronoClock}), а не по
 * тикам самой жертвы — иначе замедленная фаза растягивала бы саму себя.
 */
public final class TimeParadox {
    public static final int SWING_TICKS = 10;
    public static final double FAST_RATE = 1.75D;
    public static final double SLOW_RATE = 0.35D;

    private TimeParadox() {
    }

    /** Накладывает парадокс на {@code ticks} тиков (более долгий уже висящий не укорачивает). */
    public static void afflict(Entity entity, int ticks) {
        if (entity instanceof LivingEntity living && !living.level().isClientSide) {
            living.addEffect(new MobEffectInstance(ChronoMobEffectRegistry.PARADOX, ticks, 0, false, true, true));
        }
    }

    public static boolean afflicted(Entity entity) {
        return entity instanceof LivingEntity living && living.hasEffect(ChronoMobEffectRegistry.PARADOX);
    }

    /** Текущий темп под парадоксом: у каждой жертвы свой сдвиг фазы, чтобы толпа не дёргалась в такт. */
    public static double rate(Entity entity) {
        if (!afflicted(entity)) {
            return TemporalRate.NORMAL;
        }
        long phase = (ChronoClock.now() + Math.floorMod(entity.getId() * 7, SWING_TICKS * 2)) / SWING_TICKS;
        return (phase & 1L) == 0L ? FAST_RATE : SLOW_RATE;
    }
}
