package com.chronomancy.spell;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.registry.ChronoParticleRegistry;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * ACCELERATED ZONE — противоположность {@link TimeDilationFieldSpell}.
 *
 * <p>Тот же бросок сгустка, но в точке приземления разворачивается не замедляющая сфера, а
 * ускоряющий КУБ: время врагов внутри спешит на столько же процентов, на сколько поле того же
 * уровня замедляет (40% на первом уровне), и на столько же процентов растёт получаемый ими урон.
 * Сами параметры (полусторона куба = радиус поля, процент, длительность, мана, перезарядка)
 * считаются формулами поля — заклинания зеркальны.
 *
 * <p>Зона и поле в одной точке дают временной парадокс: см.
 * {@link com.chronomancy.temporal.TimeParadoxBlast}.
 */
public class AcceleratedZoneSpell extends AbstractSpell {

    private final ResourceLocation spellId =
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "accelerated_zone");

    private final DefaultConfig defaultConfig =
            new DefaultConfig()
                    .setMinRarity(SpellRarity.RARE)
                    .setSchoolResource(ChronoSchools.CHRONOMANCY_RESOURCE)
                    .setMaxLevel(5)
                    .setCooldownSeconds(28)
                    .build();

    public AcceleratedZoneSpell() {
        this.baseManaCost = TimeDilationFieldSpell.MANA_COST_BY_LEVEL[0];
        this.manaCostPerLevel = 0;
        this.baseSpellPower = 0;
        this.spellPowerPerLevel = 0;
        this.castTime = 12; // как у поля: сгусток «набирается» в руках
    }

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
        int[] costs = TimeDilationFieldSpell.MANA_COST_BY_LEVEL;
        return costs[Math.min(Math.max(spellLevel, 1), costs.length) - 1];
    }

    @Override
    public AnimationHolder getCastStartAnimation() {
        return SpellAnimations.ANIMATION_CHARGED_CAST;
    }

    private static TimeDilationFieldSpell field() {
        return com.chronomancy.registry.ChronoSpellRegistry.TIME_DILATION_FIELD_SPELL;
    }

    /** Полусторона куба — радиус поля того же уровня. */
    public double calculateHalfSize(LivingEntity caster, int spellLevel) {
        return field().calculateFieldRadius(caster, spellLevel);
    }

    /** Доля ускорения (0.40 = +40%): ровно сила замедления поля того же уровня. */
    public double calculateAcceleration(LivingEntity caster, int spellLevel) {
        // та же формула, что у замедления поля, но база и прирост за уровень — из конфига мода
        double power = Math.max(1.0, ChronoSchools.totalSpellPower(caster));
        double haste = com.chronomancy.ChronoConfig.zoneBaseHaste()
                + com.chronomancy.ChronoConfig.zoneHastePerLevel() * Math.max(0, spellLevel - 1)
                + TimeDilationFieldSpell.SLOW_PER_FULL_SPELL_POWER * (power - 1.0);
        return Math.min(TemporalRate.MAX_DILATION_SLOW, Math.max(0.0, haste));
    }

    /** Урон врагу, пересёкшему границу зоны (вход или выход), до усиления самой зоной. */
    public static float crossingDamage(double zoneRate) {
        return (float) (Math.max(0.0, zoneRate - TemporalRate.NORMAL) * 100.0
                * com.chronomancy.ChronoConfig.zoneCrossingDamageShare());
    }

    /** Темп времени внутри зоны, {@code 1..}{@link TemporalRate#MAX_ACCELERATION_RATE}. */
    public double calculateTemporalRate(LivingEntity caster, int spellLevel) {
        return Math.min(TemporalRate.MAX_ACCELERATION_RATE,
                TemporalRate.NORMAL + calculateAcceleration(caster, spellLevel));
    }

    public int calculateDurationTicks(int spellLevel) {
        return field().calculateDurationTicks(spellLevel);
    }

    @Override
    public List<MutableComponent> getUniqueInfo(int spellLevel, LivingEntity caster) {
        String percent = Utils.stringTruncation((float) (calculateAcceleration(caster, spellLevel) * 100.0), 0) + "%";
        return List.of(
                Component.translatable("spell.chronomancy.accelerated_zone.size",
                        Utils.stringTruncation((float) (calculateHalfSize(caster, spellLevel) * 2.0), 1)),
                Component.translatable("spell.chronomancy.accelerated_zone.speed", percent),
                Component.translatable("spell.chronomancy.accelerated_zone.damage",
                        Utils.stringTruncation((float) (calculateAcceleration(caster, spellLevel) * 100.0
                                * com.chronomancy.ChronoConfig.zoneDamageTakenShare()), 0) + "%"),
                Component.translatable("spell.chronomancy.accelerated_zone.crossing",
                        Utils.stringTruncation(crossingDamage(calculateTemporalRate(caster, spellLevel)), 1)),
                Component.translatable("spell.chronomancy.time_dilation_field.duration",
                        Utils.stringTruncation((float) field().calculateDurationSeconds(spellLevel), 1) + "s"),
                Component.translatable("spell.chronomancy.accelerated_zone.paradox"),
                Component.translatable("spell.chronomancy.accelerated_zone.sneak")
        );
    }

    @Override
    public void onCast(Level level, int spellLevel, LivingEntity caster,
                       CastSource castSource, MagicData playerMagicData) {
        if (level.isClientSide) {
            return;
        }
        double half = calculateHalfSize(caster, spellLevel);
        double rate = calculateTemporalRate(caster, spellLevel);
        int lifetime = calculateDurationTicks(spellLevel);

        if (caster.isShiftKeyDown()) {
            // личная зона: уменьшенный куб следует за кастером (как Shift-сфера поля)
            TimeDilationFieldSpell.spawnStaticField(level, caster.position(),
                    half * TimeDilationFieldSpell.SELF_FIELD_RADIUS_MULTIPLIER, rate, lifetime,
                    caster.getUUID(), true, true);
        } else {
            TimeDilationFieldSpell.spawnOrb(level, caster, half, rate, lifetime, true);
        }
        super.onCast(level, spellLevel, caster, castSource, playerMagicData);
    }

    /** Всплеск при рождении зоны: искры по периметру куба, взлетающие вверх. */
    public static void spawnCastParticles(Level level, Vec3 center, double half) {
        int perSide = 7;
        for (int side = 0; side < 4; side++) {
            for (int i = 0; i < perSide; i++) {
                double t = (i + 0.5) / perSide * 2.0 - 1.0;
                double x = center.x + (side < 2 ? t * half : (side == 2 ? half : -half));
                double z = center.z + (side < 2 ? (side == 0 ? half : -half) : t * half);
                MagicManager.spawnParticles(level, ChronoParticleRegistry.TEMPORAL_SPARK.get(),
                        x, center.y + 0.1, z, 1, 0.0D, 0.25D, 0.0D, 0.12D, false);
            }
        }
    }
}
