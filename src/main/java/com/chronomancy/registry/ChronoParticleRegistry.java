package com.chronomancy.registry;

import com.chronomancy.ChronomancyMod;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Типы частиц Chronomancy. Реальный отбор спавна — клиентский
 * ({@code ChronoParticles}): сервер НЕ шлёт пакет на каждую песчинку, он вообще
 * не участвует в визуале стазиса/перемотки. Регистрация нужна для двух вещей:
 * корректного жизненного цикла реестра и провайдера, чтобы частицу можно было
 * поставить и простым {@code level.addParticle(type, ...)} (клиентский путь,
 * например «рассыпание» afterimage).
 */
public final class ChronoParticleRegistry {

    public static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(BuiltInRegistries.PARTICLE_TYPE, ChronomancyMod.MODID);

    /** Золотая песчинка Temporal Stasis (орбиты/подвешенные/крупные motes). */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> TEMPORAL_MOTE =
            PARTICLES.register("temporal_mote", () -> new SimpleParticleType(false));

    /** Темпоральный трейл Rewind: вытянутый streak с яркой головой. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> TEMPORAL_TRAIL =
            PARTICLES.register("temporal_trail", () -> new SimpleParticleType(false));

    /** Яркий spark-акцент (атласный спрайт SPARK): удар луча, ядро сгустка, Rewind. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> TEMPORAL_SPARK =
            PARTICLES.register("temporal_spark", () -> new SimpleParticleType(false));

    // Additional semantic names, all rendered by the existing Chronomancy providers/atlas.
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> TEMPORAL_RUNE =
            PARTICLES.register("temporal_rune", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> TEMPORAL_SAND_GRAIN =
            PARTICLES.register("temporal_sand_grain", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> TEMPORAL_CRACK =
            PARTICLES.register("temporal_crack", () -> new SimpleParticleType(false));

    /** Огонёк струи снаряда (без направления): голубая фаза / золото / крупный сгусток. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> TEMPORAL_WISP =
            PARTICLES.register("temporal_wisp", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> TEMPORAL_WISP_GOLD =
            PARTICLES.register("temporal_wisp_gold", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> TEMPORAL_CLOUD =
            PARTICLES.register("temporal_cloud", () -> new SimpleParticleType(false));

    /** Медная пыль копий Rift (тот же фазовый шейдер, медная палитра). */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> TEMPORAL_COPPER =
            PARTICLES.register("temporal_copper", () -> new SimpleParticleType(false));

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> NEEDLE_WAKE =
            PARTICLES.register("needle_wake", () -> new SimpleParticleType(false));

    private ChronoParticleRegistry() {
    }

    public static void register(IEventBus modEventBus) {
        PARTICLES.register(modEventBus);
    }
}
