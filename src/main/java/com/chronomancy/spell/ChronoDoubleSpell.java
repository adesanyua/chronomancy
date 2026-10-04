package com.chronomancy.spell;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.registry.ChronoAttributes;
import com.chronomancy.registry.ChronoSchools;
import com.chronomancy.temporal.chronodouble.ChronoDoubleManager;
import io.redspace.ironsspellbooks.api.config.DefaultConfig;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;

/**
 * CHRONO_DOUBLE — conjures a copper-tinted echo of the caster that lives for
 * {@value #DURATION_TICKS} ticks (20 seconds, cooldown 2 minutes).
 *
 * <p>The double is a synced mirror: it re-renders the owner's own live, animated player model
 * (real skin, held item, swings, cast gestures) on the owner's other side, tinted copper, so both
 * converge on a single target. On melee and on the school's own direct-damage spells it lands a
 * reduced secondary hit ({@link #damageFraction}); every other spell is echoed visually. All server
 * behaviour lives in {@link ChronoDoubleManager}.
 */
public class ChronoDoubleSpell extends AbstractSpell {

    private final ResourceLocation spellId =
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "chrono_double");

    private final DefaultConfig defaultConfig = new DefaultConfig()
            .setMinRarity(SpellRarity.LEGENDARY)
            .setSchoolResource(ChronoSchools.CHRONOMANCY_RESOURCE)
            .setMaxLevel(1)
            .setCooldownSeconds(120) // 2 minutes
            .build();

    /** Double lifetime: 20 seconds (400 server ticks). */
    public static final int DURATION_TICKS = 400;
    /** Lateral distance the anchor is pinned from the owner (blocks). */
    public static final double SIDE_LATERAL = 0.95;

    /** Base share of the owner's own hit that the double's echo repeats. */
    public static final float MIRROR_FRACTION_BASE = 0.40F;
    /** Extra fraction per spell level above 1, capped by {@link #MIRROR_FRACTION_MAX}. */
    public static final float MIRROR_FRACTION_PER_LEVEL = 0.035F;
    public static final float MIRROR_FRACTION_MAX = 0.55F;
    /** Hard ceiling: the echo can never out-damage the owner (100%). */
    public static final float MIRROR_FRACTION_CAP = 1.0F;
    /** The echo gains +1% damage for every +10% of the owner's spell power. */
    public static final float SPELL_POWER_TO_MIRROR_RATIO = 0.1F;

    public ChronoDoubleSpell() {
        this.baseManaCost = 500;
        this.manaCostPerLevel = 0;
        this.castTime = 25; // 1.25 с: двойник «проступает» из потока времени
    }

    /**
     * The owner's spell-power bonus as a fraction (0.5 == +50%), taken as the greater of the generic
     * {@code SPELL_POWER} and the Chronomancy school power ("normal or chronomancy spell power").
     */
    public static float spellPowerBonus(LivingEntity owner) {
        if (owner == null) return 0.0F;
        double generic = owner.getAttributeValue(AttributeRegistry.SPELL_POWER);
        double chronomancy = owner.getAttributeValue(ChronoAttributes.CHRONOMANCY_SPELL_POWER);
        double power = Math.max(generic, chronomancy);
        return (float) Math.max(0.0, power - 1.0);
    }

    /**
     * Echo strength: the level share (0.40..0.55) buffed by the owner's spell power
     * (+1% mirror damage per +10% spell power), never exceeding {@link #MIRROR_FRACTION_CAP} (100%).
     */
    public static float damageFraction(int spellLevel, LivingEntity owner) {
        float levelShare = Math.min(MIRROR_FRACTION_MAX, MIRROR_FRACTION_BASE + MIRROR_FRACTION_PER_LEVEL * (spellLevel - 1));
        float buffed = levelShare + spellPowerBonus(owner) * SPELL_POWER_TO_MIRROR_RATIO;
        return Math.min(MIRROR_FRACTION_CAP, buffed);
    }

    @Override
    public CastType getCastType() {
        return CastType.LONG;
    }

    /** Анимация каста: волнообразный замах, затем выброс руки. */
    @Override
    public io.redspace.ironsspellbooks.api.util.AnimationHolder getCastStartAnimation() {
        return io.redspace.ironsspellbooks.api.spells.SpellAnimations.CHARGE_WAVY_ANIMATION;
    }

    @Override
    public io.redspace.ironsspellbooks.api.util.AnimationHolder getCastFinishAnimation() {
        return io.redspace.ironsspellbooks.api.spells.SpellAnimations.ANIMATION_LONG_CAST_FINISH;
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
    public List<MutableComponent> getUniqueInfo(int spellLevel, LivingEntity caster) {
        return List.of(
                Component.translatable("spell.chronomancy.chrono_double.duration", DURATION_TICKS / 20.0 + "s"),
                Component.translatable("spell.chronomancy.chrono_double.mirror",
                        String.format(Locale.ROOT, "%d%%", Math.round(damageFraction(spellLevel, caster) * 100)))
        );
    }

    @Override
    public void onCast(Level level, int spellLevel, LivingEntity caster,
                       CastSource castSource, MagicData playerMagicData) {
        if (!level.isClientSide && caster instanceof ServerPlayer player) {
            ChronoDoubleManager.spawn(player, spellLevel);
        }
        super.onCast(level, spellLevel, caster, castSource, playerMagicData);
    }
}
