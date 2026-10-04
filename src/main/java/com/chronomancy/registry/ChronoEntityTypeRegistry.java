package com.chronomancy.registry;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.entity.BacktrackBoltEntity;
import com.chronomancy.entity.RewindAfterimageEntity;
import com.chronomancy.entity.TimeDilationFieldEntity;
import com.chronomancy.entity.TimeDilationOrbEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ChronoEntityTypeRegistry {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ChronomancyMod.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<TimeDilationFieldEntity>> TIME_DILATION_FIELD =
            ENTITY_TYPES.register("time_dilation_field",
                    () -> EntityType.Builder.<TimeDilationFieldEntity>of(TimeDilationFieldEntity::new, MobCategory.MISC)
                            .sized(1.0F, 1.0F)
                            .clientTrackingRange(64)
                            .updateInterval(10)
                            .build("time_dilation_field"));

    /** Вспышка временного парадокса (зона + поле в одной точке): короткоживущая визуальная сущность. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.chronomancy.entity.TimeParadoxEntity>> TIME_PARADOX =
            ENTITY_TYPES.register("time_paradox",
                    () -> EntityType.Builder.<com.chronomancy.entity.TimeParadoxEntity>of(
                                    com.chronomancy.entity.TimeParadoxEntity::new, MobCategory.MISC)
                            .sized(1.0F, 1.0F)
                            .clientTrackingRange(64)
                            .updateInterval(20)
                            .build("time_paradox"));

    /** Конус песка заклинания Sands of Time (невидимая сущность-струя, как конусы Iron's Spells). */
    public static final DeferredHolder<EntityType<?>, EntityType<com.chronomancy.entity.SandsOfTimeProjectile>> SANDS_OF_TIME_CONE =
            ENTITY_TYPES.register("sands_of_time_cone",
                    () -> EntityType.Builder.<com.chronomancy.entity.SandsOfTimeProjectile>of(
                                    com.chronomancy.entity.SandsOfTimeProjectile::new, MobCategory.MISC)
                            .sized(1.0F, 1.0F)
                            .clientTrackingRange(64)
                            .build("sands_of_time_cone"));

    /** Снаряд-«клубок нитей»: летит по дуге и разворачивает поле при приземлении. */
    public static final DeferredHolder<EntityType<?>, EntityType<TimeDilationOrbEntity>> TIME_DILATION_ORB =
            ENTITY_TYPES.register("time_dilation_orb",
                    () -> EntityType.Builder.<TimeDilationOrbEntity>of(TimeDilationOrbEntity::new, MobCategory.MISC)
                            .sized(0.35F, 0.35F)
                            .clientTrackingRange(4)
                            .updateInterval(10)
                            .build("time_dilation_orb"));

    /**
     * Остаточный образ Rewind: декоративный силуэт на точке каста.
     * Хитбокс — как у игрока, чтобы пропорции силуэта выглядели естественно;
     * сущность не физична и не целируется (см. {@link RewindAfterimageEntity}).
     */
    public static final DeferredHolder<EntityType<?>, EntityType<RewindAfterimageEntity>> REWIND_AFTERIMAGE =
            ENTITY_TYPES.register("rewind_afterimage",
                    () -> EntityType.Builder.<RewindAfterimageEntity>of(RewindAfterimageEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(10)
                            .updateInterval(10)
                            .build("rewind_afterimage"));

    /**
     * Backtrack Bolt — небольшой быстрый темпоральный снаряд. Маленький
     * хитбокс, no-gravity, не пробивает; вся механика — в {@code BacktrackSpell}.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<BacktrackBoltEntity>> BACKTRACK_BOLT =
            ENTITY_TYPES.register("backtrack_bolt",
                    () -> EntityType.Builder.<BacktrackBoltEntity>of(BacktrackBoltEntity::new, MobCategory.MISC)
                            .sized(0.25F, 0.25F)
                            .clientTrackingRange(4)
                            .updateInterval(10)
                            .build("backtrack_bolt"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.chronomancy.entity.TemporalNeedleEntity>> TEMPORAL_NEEDLE =
            ENTITY_TYPES.register("temporal_needle",
                    () -> EntityType.Builder.<com.chronomancy.entity.TemporalNeedleEntity>of(com.chronomancy.entity.TemporalNeedleEntity::new, MobCategory.MISC)
                            .sized(0.1F, 0.1F).clientTrackingRange(8).updateInterval(1).build("temporal_needle"));

    /**
     * ChronoDouble anchor — зеркальный медный двойник игрока. Хитбокс как у игрока, чтобы
     * перерисованная модель выглядела естественно; updateInterval=2 для плавного отслеживания
     * (сама синхронизация позиции — в {@link com.chronomancy.temporal.chronodouble.ChronoDoubleManager}).
     */
    public static final DeferredHolder<EntityType<?>, EntityType<com.chronomancy.entity.ChronoDoubleEntity>> CHRONO_DOUBLE =
            ENTITY_TYPES.register("chrono_double",
                    () -> EntityType.Builder.<com.chronomancy.entity.ChronoDoubleEntity>of(com.chronomancy.entity.ChronoDoubleEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(10)
                            .updateInterval(2)
                            .noSave()
                            .build("chrono_double"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.chronomancy.entity.ChronoDoubleArrowEntity>> CHRONO_DOUBLE_ARROW =
            ENTITY_TYPES.register("chrono_double_arrow",
                    () -> EntityType.Builder.<com.chronomancy.entity.ChronoDoubleArrowEntity>of(com.chronomancy.entity.ChronoDoubleArrowEntity::new, MobCategory.MISC)
                            .sized(0.25F, 0.25F).clientTrackingRange(8).updateInterval(1).build("chrono_double_arrow"));

    /**
     * Chronomaly — парящая временная аномалия из Time Rift. MONSTER-категория (обычный
     * деспавн/пиcфул), но естественного спавна нет: только разлом и яйцо призыва.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<com.chronomancy.entity.ChronomalyEntity>> CHRONOMALY =
            ENTITY_TYPES.register("chronomaly",
                    () -> EntityType.Builder.<com.chronomancy.entity.ChronomalyEntity>of(com.chronomancy.entity.ChronomalyEntity::new, MobCategory.MONSTER)
                            .sized(0.7F, 1.8F)
                            .eyeHeight(1.55F)
                            .clientTrackingRange(10)
                            .build("chronomaly"));

    /** Островок времени в испытании разлома: пятно, где личное время кастера снова идёт. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.chronomancy.entity.TimeIslandEntity>> TIME_ISLAND =
            ENTITY_TYPES.register("time_island",
                    () -> EntityType.Builder.<com.chronomancy.entity.TimeIslandEntity>of(com.chronomancy.entity.TimeIslandEntity::new, MobCategory.MISC)
                            .sized(0.5F, 0.5F)
                            .clientTrackingRange(10)
                            .updateInterval(20)
                            .fireImmune()
                            .noSave()
                            .noSummon()
                            .build("time_island"));

    /** Time Rift — разрыв в остановленном времени; не сохраняется, живёт внутри одного стопа. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.chronomancy.entity.TimeRiftEntity>> TIME_RIFT =
            ENTITY_TYPES.register("time_rift",
                    () -> EntityType.Builder.<com.chronomancy.entity.TimeRiftEntity>of(com.chronomancy.entity.TimeRiftEntity::new, MobCategory.MISC)
                            .sized(1.2F, 2.4F)
                            .clientTrackingRange(10)
                            .updateInterval(10)
                            .fireImmune()
                            .noSave()
                            .noSummon()
                            .build("time_rift"));

    /** Медный двойник игрока (призывы Rift Maker и заклинание Rift по игроку). */
    public static final DeferredHolder<EntityType<?>, EntityType<com.chronomancy.entity.PlayerEchoEntity>> PLAYER_ECHO =
            ENTITY_TYPES.register("player_echo",
                    () -> EntityType.Builder.<com.chronomancy.entity.PlayerEchoEntity>of(com.chronomancy.entity.PlayerEchoEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .eyeHeight(1.62F)
                            .clientTrackingRange(10)
                            .noSave()
                            .noSummon()
                            .build("player_echo"));

    /** Босс испытания разлома — Rift Maker (Творец разломов). */
    public static final DeferredHolder<EntityType<?>, EntityType<com.chronomancy.entity.RiftMakerEntity>> RIFT_MAKER =
            ENTITY_TYPES.register("rift_maker",
                    () -> EntityType.Builder.<com.chronomancy.entity.RiftMakerEntity>of(com.chronomancy.entity.RiftMakerEntity::new, MobCategory.MONSTER)
                            .sized(2.2F, 6.4F) // модель отрисована в 2× (RiftMakerRenderer)
                            .eyeHeight(5.4F)
                            .clientTrackingRange(12)
                            .fireImmune()
                            .build("rift_maker"));

    /** Фазирующие мобы разлома: как обычные, но рывками ходят сквозь время. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.chronomancy.entity.PhasingZombie>> PHASING_ZOMBIE =
            ENTITY_TYPES.register("phasing_zombie",
                    () -> EntityType.Builder.<com.chronomancy.entity.PhasingZombie>of(com.chronomancy.entity.PhasingZombie::new, MobCategory.MONSTER)
                            .sized(0.6F, 1.95F)
                            .eyeHeight(1.74F)
                            .clientTrackingRange(8)
                            .build("phasing_zombie"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.chronomancy.entity.PhasingSkeleton>> PHASING_SKELETON =
            ENTITY_TYPES.register("phasing_skeleton",
                    () -> EntityType.Builder.<com.chronomancy.entity.PhasingSkeleton>of(com.chronomancy.entity.PhasingSkeleton::new, MobCategory.MONSTER)
                            .sized(0.6F, 1.99F)
                            .eyeHeight(1.74F)
                            .clientTrackingRange(8)
                            .build("phasing_skeleton"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.chronomancy.entity.PhasingCreeper>> PHASING_CREEPER =
            ENTITY_TYPES.register("phasing_creeper",
                    () -> EntityType.Builder.<com.chronomancy.entity.PhasingCreeper>of(com.chronomancy.entity.PhasingCreeper::new, MobCategory.MONSTER)
                            .sized(0.6F, 1.7F)
                            .clientTrackingRange(8)
                            .build("phasing_creeper"));

    /** Часовщик — нейтральный маг-торговец из часовой башни (модель и поведение магов Iron's Spells). */
    public static final DeferredHolder<EntityType<?>, EntityType<com.chronomancy.entity.ClocksmithEntity>> CLOCKSMITH =
            ENTITY_TYPES.register("clocksmith",
                    () -> EntityType.Builder.<com.chronomancy.entity.ClocksmithEntity>of(com.chronomancy.entity.ClocksmithEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(64)
                            .build("clocksmith"));

    /** Атрибуты мобов мода (mod bus). */
    public static void registerAttributes(net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) {
        event.put(CHRONOMALY.get(), com.chronomancy.entity.ChronomalyEntity.createAttributes().build());
        event.put(PLAYER_ECHO.get(), com.chronomancy.entity.PlayerEchoEntity.createAttributes().build());
        event.put(RIFT_MAKER.get(), com.chronomancy.entity.RiftMakerEntity.createAttributes().build());
        event.put(CLOCKSMITH.get(), com.chronomancy.entity.ClocksmithEntity.createAttributes().build());
        event.put(PHASING_ZOMBIE.get(), com.chronomancy.entity.PhasingZombie.createAttributes().build());
        event.put(PHASING_SKELETON.get(), com.chronomancy.entity.PhasingSkeleton.createAttributes().build());
        event.put(PHASING_CREEPER.get(), com.chronomancy.entity.PhasingCreeper.createAttributes().build());
    }

    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
        eventBus.addListener(ChronoEntityTypeRegistry::registerAttributes);
    }
}
