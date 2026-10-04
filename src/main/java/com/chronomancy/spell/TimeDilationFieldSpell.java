package com.chronomancy.spell;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.entity.TimeDilationFieldEntity;
import com.chronomancy.entity.TimeDilationOrbEntity;
import com.chronomancy.registry.ChronoEntityTypeRegistry;
import com.chronomancy.registry.ChronoSchools;
import com.chronomancy.temporal.TemporalRate;
import io.redspace.ironsspellbooks.api.config.DefaultConfig;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellAnimations;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import io.redspace.ironsspellbooks.api.util.AnimationHolder;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import com.chronomancy.registry.ChronoParticleRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Time Dilation Field.
 *
 * <p>Создаёт автономную временную область в точке попадания/каста. Это НЕ остановка
 * времени: внутри поля всё идёт медленнее (temporal rate &lt; 1.0), мобы продолжают
 * двигаться и действовать, снаряды продолжают лететь — просто реже тикают.
 *
 * <p>Принципиальное отличие от Temporal Stasis (полная остановка) и будущей
 * Chronosphere. Spell Power школы Chronomancy усиливает радиус И силу замедления,
 * но temporal rate жёстко ограничен снизу {@link TemporalRate#MIN_DILATION_RATE}
 * (максимум 85% замедления) — дилейшен никогда не становится стазисом.
 */
public class TimeDilationFieldSpell extends AbstractSpell {

    // =========================================================
    // ID / CONFIG
    // =========================================================

    private final ResourceLocation spellId =
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "time_dilation_field");

    private final DefaultConfig defaultConfig =
            new DefaultConfig()
                    .setMinRarity(SpellRarity.RARE)
                    .setSchoolResource(ChronoSchools.CHRONOMANCY_RESOURCE)
                    .setMaxLevel(5)
                    .setCooldownSeconds(28)
                    .build();

    // =========================================================
    // PARAMETER BASES (L1 @ 100% spell power)
    // =========================================================

    /**
     * Стартовый радиус — 2.5 блока, +0.4 за уровень: 2.5 / 2.9 / 3.3 / 3.7 / 4.1. Раньше было
     * 1.0 +0.6 (1.0 … 3.4): на первом уровне поле выросло в 2.5 раза, на пятом — в 1.2. Те же числа
     * дают полусторону куба Accelerated Zone.
     */
    public static final double BASE_RADIUS = 2.5;
    public static final double RADIUS_PER_LEVEL = 0.4;
    public static final double RADIUS_PER_FULL_SPELL_POWER = 1.5;
    public static final double MIN_FIELD_RADIUS = 1.0;
    public static final double MAX_FIELD_RADIUS = 8.0;

    /** Стартовое замедление — 40% (temporal rate 0.60). */
    public static final double BASE_SLOW = 0.40;
    public static final double SLOW_PER_LEVEL = 0.05;
    public static final double SLOW_PER_FULL_SPELL_POWER = 0.30;

    /** Базовая длительность — 8 секунд. */
    public static final double BASE_DURATION_SECONDS = 8.0;
    public static final double DURATION_SECONDS_PER_LEVEL = 1.0;

    /**
     * Множитель радиуса для «личной сферы» (каст с зажатым Shift): радиус
     * уменьшается на 30%, зато поле следует за кастером.
     */
    public static final double SELF_FIELD_RADIUS_MULTIPLIER = 0.7;

    /**
     * Скорость снаряда-сгустка. Как у снежка/зелья: умеренная дуга, чтобы
     * игрок чувствовал «бросок», а не мгновенный луч.
     */
    private static final double ORB_THROW_SPEED = 1.45;

    /** Прогрессивная стоимость маны по уровням (Rare..Legendary). */
    public static final int[] MANA_COST_BY_LEVEL = {150, 185, 225, 270, 320};

    /** Золотой dust: всплеск при рождении поля и шлейф снаряда. */


    public TimeDilationFieldSpell() {
        this.baseManaCost = MANA_COST_BY_LEVEL[0];
        this.manaCostPerLevel = 0;
        this.baseSpellPower = 0;
        this.spellPowerPerLevel = 0;
        this.castTime = 12; // 0.6 с: сгусток «набирается» в руках
    }

    // =========================================================
    // SPELL CONFIG
    // =========================================================

    /**
     * Короткий длинный каст (0.6 с): анимация ANIMATION_CHARGED_CAST — это поза набора заряда, и у
     * мгновенного заклинания она не сменялась завершающей — рука оставалась поднятой. У длинного
     * каста после неё играет штатный финал ISS, и поза сбрасывается.
     */
    @Override
    public CastType getCastType() {
        return CastType.LONG;
    }

    @Override
    public DefaultConfig getDefaultConfig() {
        return defaultConfig;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return spellId;
    }

    @Override
    public int getManaCost(int spellLevel) {
        int index = Math.min(Math.max(spellLevel, 1), MANA_COST_BY_LEVEL.length) - 1;
        return MANA_COST_BY_LEVEL[index];
    }

    @Override
    public AnimationHolder getCastStartAnimation() {
        return SpellAnimations.ANIMATION_CHARGED_CAST; // сгусток толкают двумя руками
    }

    // =========================================================
    // SPELL POWER (общий × школьный, как в Iron's Spells)
    // =========================================================

    /**
     * Итоговый Spell Power: общий атрибут ISS × сила школы Chronomancy.
     */
    private double schoolSpellPower(LivingEntity caster) {
        return ChronoSchools.totalSpellPower(caster);
    }

    // =========================================================
    // FORMULAS (плавное масштабированием уровнем и Spell Power)
    // =========================================================

    /**
     * Радиус поля.
     * radius = BASE_RADIUS + RADIUS_PER_LEVEL*(level-1) + RADIUS_PER_FULL_SPELL_POWER*(power-1)
     * клампится в [1, 8].
     */
    public double calculateFieldRadius(LivingEntity caster, int spellLevel) {
        double power = Math.max(1.0, schoolSpellPower(caster));
        double radius = BASE_RADIUS
                + RADIUS_PER_LEVEL * Math.max(0, spellLevel - 1)
                + RADIUS_PER_FULL_SPELL_POWER * (power - 1.0);
        return Math.min(MAX_FIELD_RADIUS, Math.max(MIN_FIELD_RADIUS, radius));
    }

    /**
     * Сила замедления (0..0.85). Растёт с уровнем и Spell Power, но ограничена сверху
     * так, что temporal rate никогда не доходит до 0 (не становится стазисом).
     */
    public double calculateSlowStrength(LivingEntity caster, int spellLevel) {
        double power = Math.max(1.0, schoolSpellPower(caster));
        double slow = BASE_SLOW
                + SLOW_PER_LEVEL * Math.max(0, spellLevel - 1)
                + SLOW_PER_FULL_SPELL_POWER * (power - 1.0);
        return Math.min(TemporalRate.MAX_DILATION_SLOW, Math.max(0.0, slow));
    }

    /** Итоговый temporal rate (уже с жёстким полом). */
    public double calculateTemporalRate(LivingEntity caster, int spellLevel) {
        return TemporalRate.clampDilation(1.0 - calculateSlowStrength(caster, spellLevel));
    }

    /** Длительность поля в секундах. */
    public double calculateDurationSeconds(int spellLevel) {
        return BASE_DURATION_SECONDS + DURATION_SECONDS_PER_LEVEL * Math.max(0, spellLevel - 1);
    }

    /** Длительность поля в тиках. */
    public int calculateDurationTicks(int spellLevel) {
        return Math.max(1, (int) Math.round(calculateDurationSeconds(spellLevel) * 20.0));
    }

    // =========================================================
    // TOOLTIP
    // =========================================================

    @Override
    public List<MutableComponent> getUniqueInfo(int spellLevel, LivingEntity caster) {
        double rate = calculateTemporalRate(caster, spellLevel);
        double slowPercent = (1.0 - rate) * 100.0;

        return List.of(
                Component.translatable("spell.chronomancy.time_dilation_field.radius",
                        Utils.stringTruncation((float) calculateFieldRadius(caster, spellLevel), 1)),
                Component.translatable("spell.chronomancy.time_dilation_field.slow",
                        Utils.stringTruncation((float) slowPercent, 0) + "%"),
                Component.translatable("spell.chronomancy.time_dilation_field.duration",
                        Utils.stringTruncation((float) calculateDurationSeconds(spellLevel), 1) + "s"),
                Component.translatable("spell.chronomancy.time_dilation_field.thrown"),
                Component.translatable("spell.chronomancy.time_dilation_field.sneak")
        );
    }

    // =========================================================
    // CAST
    // =========================================================

    @Override
    public void onCast(Level level, int spellLevel, LivingEntity caster,
                       CastSource castSource, MagicData playerMagicData) {

        if (level.isClientSide) {
            return;
        }

        double radius = calculateFieldRadius(caster, spellLevel);
        double rate = calculateTemporalRate(caster, spellLevel);
        int lifetime = calculateDurationTicks(spellLevel);

        // Зажатый Shift (присяд) = режим «личной сферы»: поле создаётся вокруг
        // кастера и следует за ним, но с уменьшенным на 30% радиусом. БЕЗ снаряда.
        if (caster.isShiftKeyDown()) {
            spawnStaticField(level, caster.position(),
                    radius * SELF_FIELD_RADIUS_MULTIPLIER, rate, lifetime, caster.getUUID(), true);
        } else {
            // Обычный каст: летит сгусток, и поле разворачивается уже в точке
            // его приземления/попадания. Параметры поля переносятся на снаряд.
            spawnOrb(level, caster, radius, rate, lifetime);
        }

        super.onCast(level, spellLevel, caster, castSource, playerMagicData);
    }

    /**
     * Запускает снаряд-сгусток от глаз кастера вдоль линии взгляда. Сам сгусток
     * ничего не замедляет — он лишь доставляет параметры поля до точки удара.
     */
    public static void spawnOrb(Level level, LivingEntity caster,
                                double radius, double rate, int lifetime) {
        spawnOrb(level, caster, radius, rate, lifetime, false);
    }

    /** То же, но сгусток может нести Accelerated Zone ({@code accelerating}). */
    public static void spawnOrb(Level level, LivingEntity caster,
                                double radius, double rate, int lifetime, boolean accelerating) {
        TimeDilationOrbEntity orb =
                ChronoEntityTypeRegistry.TIME_DILATION_ORB.get().create(level);
        if (orb == null) {
            return;
        }
        Vec3 eye = caster.getEyePosition();
        orb.setPos(eye.x, eye.y, eye.z);
        orb.setOwner(caster);
        // configure ДО addFreshEntity, чтобы параметры уже были в спавн-пакете
        orb.configure(radius, rate, lifetime);
        orb.setAccelerating(accelerating);
        orb.shootFromRotation(caster, caster.getXRot(), caster.getYRot(), 0.0F,
                (float) ORB_THROW_SPEED, 0.0F);
        level.addFreshEntity(orb);
    }

    /**
     * Создаёт поле дилейшена. Используется и для мгновенной «личной сферы»
     * (follow=true), и снарядом в момент приземления (follow=false).
     */
    public static void spawnStaticField(Level level, Vec3 center, double radius, double rate,
                                        int lifetime, UUID ownerUUID, boolean follow) {
        spawnStaticField(level, center, radius, rate, lifetime, ownerUUID, follow, false);
    }

    /** Поле замедления (сфера) или, при {@code accelerating}, Accelerated Zone (куб, темп больше 1). */
    public static void spawnStaticField(Level level, Vec3 center, double radius, double rate,
                                        int lifetime, UUID ownerUUID, boolean follow, boolean accelerating) {
        if (level.isClientSide) {
            return;
        }
        TimeDilationFieldEntity field =
                ChronoEntityTypeRegistry.TIME_DILATION_FIELD.get().create(level);
        if (field == null) {
            return;
        }
        // Область ставится НА землю: куб зоны — дном, сфера поля — приподнятой не больше чем на блок
        // (см. TimeDilationFieldEntity#centerLift). {@code center} — точка, куда упал сгусток, или ноги
        // владельца; под ней ищем опору.
        Vec3 ground = follow ? center : groundBelow(level, center);
        double lift = TimeDilationFieldEntity.centerLift(Math.max(0.5, radius), accelerating);
        field.setPos(ground.x, ground.y + lift, ground.z);
        // configure ДО addFreshEntity, чтобы SynchedEntityData сразу улетел клиентам
        if (accelerating) {
            field.configureZone(radius, rate, lifetime, ownerUUID, follow);
        } else {
            field.configure(radius, rate, lifetime, ownerUUID, follow);
        }
        level.addFreshEntity(field);

        if (accelerating) {
            com.chronomancy.spell.AcceleratedZoneSpell.spawnCastParticles(level, ground, radius);
        } else {
            spawnCastParticles(level, ground, radius);
        }
    }

    /** На сколько блоков вниз от точки попадания ищем опору для области. */
    private static final int GROUND_SEARCH = 4;

    /**
     * Опора под точкой попадания сгустка: верх ближайшего блока с коллизией не выше самой точки. Сгусток,
     * ударивший в стену или в моба, даёт точку в воздухе — область опускается на пол под ней. Если пола
     * рядом нет (обрыв, летающая цель высоко), область остаётся на высоте попадания.
     */
    public static Vec3 groundBelow(Level level, Vec3 point) {
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        int top = net.minecraft.util.Mth.floor(point.y + 0.01D);
        for (int y = top; y >= top - GROUND_SEARCH; y--) {
            pos.set(net.minecraft.util.Mth.floor(point.x), y, net.minecraft.util.Mth.floor(point.z));
            if (!level.isLoaded(pos)) {
                break;
            }
            var shape = level.getBlockState(pos).getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                continue;
            }
            double surface = y + shape.max(net.minecraft.core.Direction.Axis.Y);
            if (surface <= point.y + 0.51D) {
                return new Vec3(point.x, Math.min(surface, point.y + 0.51D), point.z);
            }
        }
        return point;
    }

    /**
     * Моментальный «всплеск» золотистой пыли по экватору рождающегося поля —
     * кинематический сигнал, что время здесь стало гуще. Немного частиц, не тысячи.
     */
    public static void spawnCastParticles(Level level, Vec3 center, double radius) {
        int count = 24;
        for (int i = 0; i < count; i++) {
            double angle = (i / (double) count) * Math.PI * 2.0;
            double x = center.x + Math.cos(angle) * radius;
            double z = center.z + Math.sin(angle) * radius;
            MagicManager.spawnParticles(
                    level, ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                    x, center.y, z,
                    1,
                    0.0D, 0.02D, 0.0D,
                    0.05D,
                    false);
        }
    }
}
