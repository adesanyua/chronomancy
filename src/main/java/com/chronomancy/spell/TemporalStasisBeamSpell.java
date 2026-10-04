package com.chronomancy.spell;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.effect.TemporalStasisEvents;
import com.chronomancy.registry.ChronoMobEffectRegistry;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.registry.ChronoSchools;
import com.chronomancy.sound.ChronoStasisSounds;

import io.redspace.ironsspellbooks.api.config.DefaultConfig;
import io.redspace.ironsspellbooks.api.config.SpellConfigManager;
import io.redspace.ironsspellbooks.api.config.SpellConfigParameter;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellAnimations;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import io.redspace.ironsspellbooks.api.util.AnimationHolder;
import io.redspace.ironsspellbooks.api.util.RaycastBuilder;
import io.redspace.ironsspellbooks.api.util.Utils;

import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;


/**
 * Temporal Stasis Beam
 *
 * Накладывает Temporal Stasis на цель.
 *
 * Прочность стазиса зависит от:
 *
 * 1. уровня заклинания;
 * 2. Spell Power владельца.
 *
 * Весь входящий урон во время стазиса
 * накапливается.
 *
 * При достижении Damage Cap стазис
 * разрушается досрочно.
 */
public class TemporalStasisBeamSpell
        extends AbstractSpell {

    // =========================================================
    // SETTINGS
    // =========================================================

    /**
     * 3 секунды.
     */
    public static final int STASIS_DURATION_TICKS =
            Integer.MAX_VALUE;


    // =========================================================
    // DURATION SCALING (реальная длительность = дедлайн по мировому времени)
    // =========================================================

    /**
     * Базовая длительность стазиса (уровень 1) — 3 секунды.
     */
    public static final double STASIS_BASE_SECONDS = 3.0;

    /**
     * +1 секунда за каждый уровень свитка выше первого.
     */
    public static final double STASIS_SECONDS_PER_LEVEL = 1.0;

    /**
     * +0.5 секунды за каждые 10% spell power сверх базовых 100%.
     */
    public static final double STASIS_SECONDS_PER_TEN_PCT_SPELL_POWER = 0.5;


    /**
     * Максимальный уровень заклинания.
     */
    public static final int MAX_SPELL_LEVEL =
            5;


    /**
     * Прогрессивная стоимость маны по уровням (Common -> Legendary).
     * Индекс 0 = уровень 1. Прирост ускоряется: +40, +50, +60, +70.
     *
     *  L1 Common    = 100
     *  L2 Uncommon  = 140
     *  L3 Rare      = 190
     *  L4 Epic      = 250
     *  L5 Legendary = 320
     */
    public static final int[] MANA_COST_BY_LEVEL =
            {
                    100,
                    140,
                    190,
                    250,
                    320
            };


    /**
     * Радиус луча.
     */
    private static final float RANGE =
            25.0F;


    // =========================================================
    // ID
    // =========================================================

    /**
     * Полный строковый id (== {@code AbstractSpell#getSpellId()}).
     * Используется The World Stop, чтобы запретить луч во время остановки времени
     * (см. {@code WorldStopEvents#onSpellPreCast}).
     */
    public static final String SPELL_ID = "chronomancy:temporal_stasis_beam";

    private final ResourceLocation spellId =
            ResourceLocation.fromNamespaceAndPath(
                    ChronomancyMod.MODID,
                    "temporal_stasis_beam"
            );


    // =========================================================
    // CONFIG
    // =========================================================

    private final DefaultConfig defaultConfig =
            new DefaultConfig()
                    .setMinRarity(
                            SpellRarity.COMMON
                    )
                    .setSchoolResource(
                            ChronoSchools.CHRONOMANCY_RESOURCE
                    )
                    .setMaxLevel(
                            MAX_SPELL_LEVEL
                    )
                    .setCooldownSeconds(
                            20
                    )
                    .build();


    // =========================================================
    // CONSTRUCTOR
    // =========================================================

    public TemporalStasisBeamSpell() {

        /*
         * Mana:
         *
         * Прогрессивная стоимость по уровням (Common -> Legendary)
         * задаётся переопределением getManaCost(...).
         *
         * baseManaCost оставлен равным стартовому значению уровня 1
         * только как фолбэк/для наглядности.
         */
        this.baseManaCost = MANA_COST_BY_LEVEL[0];
        this.manaCostPerLevel = 0;


        /*
         * Сам луч прямого урона не наносит.
         *
         * Spell Power используется для
         * прочности Temporal Stasis.
         */
        this.baseSpellPower = 0;
        this.spellPowerPerLevel = 0;


        this.castTime = 15;
    }


    // =========================================================
    // SPELL CONFIG
    // =========================================================

    @Override
    public CastType getCastType() {
        return CastType.INSTANT;
    }


    @Override
    public DefaultConfig getDefaultConfig() {
        return defaultConfig;
    }


    @Override
    public ResourceLocation getSpellResource() {
        return spellId;
    }


    /**
     * Прогрессивная стоимость маны вместо линейной формулы
     * {@code baseManaCost + manaCostPerLevel * (level - 1)}.
     *
     * <p>Уровни 1..5 (Common..Legendary) берутся из {@link #MANA_COST_BY_LEVEL};
     * значения за его пределами клампятся к краям таблицы. Глобальный
     * конфигурационный множитель маны школы (MANA_MULTIPLIER) применяется так же,
     * как это делает дефолтная реализация {@code AbstractSpell#getManaCost}.
     */
    @Override
    public int getManaCost(
            int spellLevel
    ) {
        int index =
                Math.min(
                        Math.max(spellLevel, 1),
                        MANA_COST_BY_LEVEL.length
                ) - 1;

        double multiplier =
                SpellConfigManager.getSpellConfigValue(
                        this,
                        SpellConfigParameter.MANA_MULTIPLIER
                );

        return (int) (MANA_COST_BY_LEVEL[index] * multiplier);
    }


    // =========================================================
    // TOOLTIP
    // =========================================================

    @Override
    public List<MutableComponent> getUniqueInfo(
            int spellLevel,
            LivingEntity caster
    ) {

        float cap =
                calculateStasisDamageCap(
                        caster,
                        spellLevel
                );


        return List.of(
                Component.literal(
                        "Stasis Capacity: "
                                + Utils.stringTruncation(
                                cap,
                                1
                        )
                                + " Damage"
                ),

                Component.literal(
                        "Stasis Duration: "
                                + Utils.stringTruncation(
                                (float) calculateStasisDurationSeconds(
                                        caster,
                                        spellLevel
                                ),
                                1
                        )
                                + "s"
                ),

                Component.translatable(
                        "ui.chronomancy.distance",
                        Utils.stringTruncation(
                                getRange(),
                                1
                        )
                )
        );
    }


    // =========================================================
    // SOUNDS
    // =========================================================

    @Override
    public Optional<net.minecraft.sounds.SoundEvent>
    getCastStartSound() {
        return Optional.empty();
    }


    @Override
    public Optional<net.minecraft.sounds.SoundEvent>
    getCastFinishSound() {
        return Optional.of(com.chronomancy.registry.ChronoSounds.CHRONOMANCY_CAST.get());
    }


    // =========================================================
    // CAST
    // =========================================================

    @Override
    public void onCast(
            Level level,
            int spellLevel,
            LivingEntity caster,
            CastSource castSource,
            MagicData playerMagicData
    ) {

        /*
         * Вся логика попадания выполняется сервером.
         */
        if (level.isClientSide) {
            return;
        }


        // =====================================================
        // RAYCAST
        // =====================================================

        HitResult hitResult =
                RaycastBuilder
                        .begin(
                                level,
                                caster
                        )
                        .range(
                                getRange()
                        )
                        .checkForBlocks(
                                true
                        )
                        .bbInflation(
                                0.15F
                        )
                        .build();


        Vec3 start =
                caster.getEyePosition();

        Vec3 impact =
                hitResult.getLocation();


        // =====================================================
        // BEAM PARTICLES
        // =====================================================

        spawnBeamParticles(
                level,
                start,
                impact
        );


        // =====================================================
        // ENTITY HIT
        // =====================================================

        if (hitResult.getType()
                == HitResult.Type.ENTITY) {

            Entity target =
                    ((EntityHitResult) hitResult)
                            .getEntity();


            if (target instanceof LivingEntity livingTarget
                    && target.canBeHitByProjectile()
                    && target != caster) {


                /*
                 * Пока Temporal Stasis работает
                 * только на Mob.
                 *
                 * Это исключает игроков.
                 */
                if (livingTarget instanceof Mob || livingTarget instanceof net.minecraft.world.entity.player.Player) {

                    applyTemporalStasis(
                            livingTarget,
                            caster,
                            spellLevel
                    );

                    /*
                     * Звук попадания: металлический clunk +
                     * кристаллический звон ("время защёлкнулось").
                     */
                    ChronoStasisSounds.playHit(
                            level,
                            livingTarget
                    );
                }


                // =================================================
                // HIT PARTICLES
                // =================================================

                spawnHitParticles(
                        level,
                        livingTarget,
                        impact
                );
            }
        }


        super.onCast(
                level,
                spellLevel,
                caster,
                castSource,
                playerMagicData
        );
    }


    // =========================================================
    // APPLY STASIS
    // =========================================================

    /**
     * Накладывает Temporal Stasis и записывает
     * его итоговую прочность.
     */
    private void applyTemporalStasis(
            LivingEntity target,
            LivingEntity caster,
            int spellLevel
    ) {

        // =====================================================
        // CALCULATE CAP
        // =====================================================

        float damageCap =
                calculateStasisDamageCap(
                        caster,
                        spellLevel
                );


        // =====================================================
        // EFFECT
        // =====================================================

        target.addEffect(
                new MobEffectInstance(
                        ChronoMobEffectRegistry
                                .TEMPORAL_STASIS,
                        STASIS_DURATION_TICKS,
                        0,

                        // ambient
                        false,

                        // particles
                        false,

                        // icon: у игрока в стазисе иконка видна в HUD
                        true
                )
        );


        // =====================================================
        // STASIS DATA
        // =====================================================

        long applied = TemporalStasisEvents.initializeStasis(
                target,
                spellLevel,
                damageCap,
                calculateStasisDurationTicks(caster, spellLevel)
        );
        if (applied > 0L) {
            com.chronomancy.advancement.ChronoAdvancements.grant(caster,
                    com.chronomancy.advancement.ChronoAdvancements.STASIS);
        }
    }


    // =========================================================
    // STASIS CAP
    // =========================================================

    /**
     * Базовая прочность стазиса.
     *
     * Level 1 / Common    = 12
     * Level 2             = 18
     * Level 3             = 26
     * Level 4             = 36
     * Level 5 / Legendary = 50
     */
    private float getBaseStasisDamageCap(
            int spellLevel
    ) {

        return switch (spellLevel) {

            case 1 ->
                    12.0F;

            case 2 ->
                    18.0F;

            case 3 ->
                    26.0F;

            case 4 ->
                    36.0F;

            default ->
                    50.0F;
        };
    }


    /**
     * Итоговая прочность Temporal Stasis.
     *
     * Формула:
     *
     * Base Cap × Chronomancy Spell Power
     *
     * Например:
     *
     * Level 5:
     *
     * 100% power:
     * 50 × 1.0 = 50
     *
     * 120% power:
     * 50 × 1.2 = 60
     *
     * 150% power:
     * 50 × 1.5 = 75
     */
    private float calculateStasisDamageCap(
            LivingEntity caster,
            int spellLevel
    ) {

        float baseCap =
                getBaseStasisDamageCap(
                        spellLevel
                );


        double spellPower = ChronoSchools.totalSpellPower(caster);


        /*
         * Защита от отрицательных значений.
         */
        spellPower =
                Math.max(
                        0.0D,
                        spellPower
                );


        return (float) (
                baseCap
                        * spellPower
        );
    }


    // =========================================================
    // STASIS DURATION
    // =========================================================

    /**
     * Итоговая длительность Temporal Stasis в секундах.
     *
     * Формула:
     *   Base (3s) + 1s за каждый уровень свитка выше первого
     *            + 0.5s за каждые 10% spell power сверх 100%.
     *
     * Примеры (100% spell power):
     *   L1 = 3s, L2 = 4s, L3 = 5s, L4 = 6s, L5 = 7s
     * L5 при 150% spell power: 7s + (0.5/0.1)*0.5 = 7s + 2.5s = 9.5s
     */
    public static double calculateStasisDurationSeconds(
            LivingEntity caster,
            int spellLevel
    ) {
        double seconds =
                STASIS_BASE_SECONDS
                        + STASIS_SECONDS_PER_LEVEL * Math.max(0, spellLevel - 1);

        if (caster != null) {
            double spellPower = ChronoSchools.totalSpellPower(caster);

            double bonus = Math.max(0.0, spellPower - 1.0);

            seconds +=
                    (bonus / 0.1) * STASIS_SECONDS_PER_TEN_PCT_SPELL_POWER;
        }

        return seconds;
    }


    /**
     * То же в тиках мирового времени (для дедлайна стазиса).
     */
    public static long calculateStasisDurationTicks(
            LivingEntity caster,
            int spellLevel
    ) {
        return Math.max(
                1L,
                Math.round(
                        calculateStasisDurationSeconds(caster, spellLevel) * 20.0
                )
        );
    }


    // =========================================================
    // BEAM PARTICLES
    // =========================================================

    /**
     * ВАЖНО: {@code Level.addParticle} — клиентский API; на сервере базовая
     * реализация молча возвращает false (поэтому луч «не рендерился»).
     * Серверная доставка — ServerLevel.sendParticles(...): при count == 0
     * клиент вычисляет скорость частицы как {@code maxSpeed * xDist/yDist/zDist}
     * (см. ClientPacketListener#handleParticleEvent), поэтому вектор направления
     * кладём в offset-поля, а speed = 1.0.
     */
    private static void spawnChrono(
            Level level,
            SimpleParticleType type,
            double x,
            double y,
            double z,
            double vx,
            double vy,
            double vz
    ) {
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(
                    type,
                    x,
                    y,
                    z,
                    0,
                    vx,
                    vy,
                    vz,
                    1.0D
            );
        }
    }


    /**
     * Луч — больше НЕ vanilla dust: это поток направленных TRAIL-streak'ов,
     * летящих вдоль оси к цели, мягкое MOTE-glow гало вокруг них и SPARK
     * акценты наконечника. Все частицы идут через тот же
     * chronomancy_particle шейдер и ту же палитру, что стазис и Rewind.
     */
    private void spawnBeamParticles(
            Level level,
            Vec3 start,
            Vec3 end
    ) {

        Vec3 difference =
                end.subtract(start);

        double distance =
                difference.length();


        /*
         * Защита от normalize нулевого вектора.
         */
        if (distance <= 0.001D) {
            return;
        }


        Vec3 direction =
                difference.normalize();


        int steps =
                (int) Math.max(
                        1,
                        distance * 2.0D
                );


        RandomSource random =
                level.getRandom();


        /*
         * Ядро: волнообразная золотая струя вдоль луча — огоньки без направления,
         * рисунок волны задаётся их положением.
         */
        if (level instanceof ServerLevel serverLevel) {
            com.chronomancy.util.ProjectileStreams.wave(
                    (type, x, y, z, vx, vy, vz) -> serverLevel.sendParticles(type, x, y, z, 0, vx, vy, vz, 1.0D),
                    ChronoParticleRegistry.TEMPORAL_WISP_GOLD.get(),
                    start, end, 0.0D, 0.2D, 1.6D, 6.0D, 160);
        }


        for (int i = 1;
             i <= steps;
             i++) {

            double progress =
                    (double) i
                            / (double) steps;


            /*
             * Луч слегка «дышит»: к цели дрожение амплитуды растёт,
             * иначе линия читается как математическая.
             */
            double spread =
                    0.03D + progress * 0.10D;


            Vec3 point =
                    start.add(
                            direction.scale(
                                    distance
                                            * progress
                            )
                    ).add(
                            (random.nextDouble() - 0.5D) * spread,
                            (random.nextDouble() - 0.5D) * spread,
                            (random.nextDouble() - 0.5D) * spread
                    );



            /*
             * Гало: мягкая glow-песчинка через каждую точку.
             */
            if (i % 2 == 0) {
                spawnChrono(
                        level,
                        ChronoParticleRegistry.TEMPORAL_MOTE.get(),

                        point.x,
                        point.y,
                        point.z,

                        0.0D,
                        0.01D,
                        0.0D
                );
            }
        }


        /*
         * Точка попадания: glow-вспышка + редкие искры — «время защёлкнулось».
         */
        spawnChrono(
                level,
                ChronoParticleRegistry.TEMPORAL_MOTE.get(),

                end.x,
                end.y,
                end.z,

                0.0D,
                0.02D,
                0.0D
        );


        for (int i = 0;
             i < 4;
             i++) {

            spawnChrono(
                    level,
                    ChronoParticleRegistry.TEMPORAL_SPARK.get(),

                    end.x,
                    end.y,
                    end.z,

                    (random.nextDouble() - 0.5D) * 0.08D,
                    0.02D + random.nextDouble() * 0.06D,
                    (random.nextDouble() - 0.5D) * 0.08D
            );
        }
    }


    // =========================================================
    // HIT PARTICLES
    // =========================================================

    private void spawnHitParticles(
            Level level,
            LivingEntity target,
            Vec3 hitPosition
    ) {

        RandomSource random =
                target.getRandom();


        // =====================================================
        // BURST
        // =====================================================

        int burstCount =
                20;


        for (int i = 0;
             i < burstCount;
             i++) {

            double offsetX =
                    (random.nextDouble() - 0.5D)
                            * target.getBbWidth();


            double offsetY =
                    (random.nextDouble() - 0.5D)
                            * target.getBbHeight();


            double offsetZ =
                    (random.nextDouble() - 0.5D)
                            * target.getBbWidth();


            double px =
                    hitPosition.x
                            + offsetX;

            double py =
                    hitPosition.y
                            + offsetY;

            double pz =
                    hitPosition.z
                            + offsetZ;


            double vx =
                    (random.nextDouble() - 0.5D)
                            * 0.1D;

            double vy =
                    (random.nextDouble() - 0.5D)
                            * 0.1D;

            double vz =
                    (random.nextDouble() - 0.5D)
                            * 0.1D;


            /*
             * Glow-песчинки, разлетающиеся от точки попадания — то же
             * мягкое радиальное свечение, что и у стазиса.
             */
            spawnChrono(
                    level,
                    ChronoParticleRegistry.TEMPORAL_MOTE.get(),

                    px,
                    py,
                    pz,

                    vx,
                    vy,
                    vz
            );
        }


        // =====================================================
        // OUTLINE
        // =====================================================

        int outlineCount =
                15;


        for (int i = 0;
             i < outlineCount;
             i++) {

            double angle =
                    random.nextDouble()
                            * Math.PI
                            * 2.0D;


            double radius =
                    target.getBbWidth()
                            * 0.6D;


            double height =
                    target.getBbHeight()
                            * (
                            random.nextDouble()
                                    * 0.8D
                                    + 0.1D
                    );


            double px =
                    target.getX()
                            + Math.cos(angle)
                            * radius;


            double py =
                    target.getY()
                            + height;


            double pz =
                    target.getZ()
                            + Math.sin(angle)
                            * radius;


            double vx =
                    -Math.cos(angle)
                            * 0.05D;


            double vy =
                    (random.nextDouble() - 0.5D)
                            * 0.05D;


            double vz =
                    -Math.sin(angle)
                            * 0.05D;


            /*
             * Контур: золотые огоньки, сходящиеся к центру цели — время «закручивается»
             * внутрь стазис-скорлупы.
             */
            spawnChrono(
                    level,
                    ChronoParticleRegistry.TEMPORAL_WISP_GOLD.get(),

                    px,
                    py,
                    pz,

                    vx,
                    vy,
                    vz
            );
        }


        /*
         * Несколько искр в точку попадания — атласные акценты той же палитры.
         */
        for (int i = 0;
             i < 3;
             i++) {

            spawnChrono(
                    level,
                    ChronoParticleRegistry.TEMPORAL_SPARK.get(),

                    hitPosition.x,
                    hitPosition.y,
                    hitPosition.z,

                    (random.nextDouble() - 0.5D) * 0.05D,
                    0.03D + random.nextDouble() * 0.05D,
                    (random.nextDouble() - 0.5D) * 0.05D
            );
        }
    }


    // =========================================================
    // RANGE
    // =========================================================

    private static float getRange() {
        return RANGE;
    }


    // =========================================================
    // ANIMATION
    // =========================================================

    @Override
    public AnimationHolder
    getCastStartAnimation() {

        return SpellAnimations
                .ONE_HANDED_RAY_SHOOT;
    }
}
