package com.chronomancy.spell;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.entity.SandsOfTimeProjectile;
import com.chronomancy.registry.ChronoSchools;
import com.chronomancy.temporal.SandsOfTime;
import io.redspace.ironsspellbooks.api.config.DefaultConfig;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.entity.spells.AbstractConeProjectile;
import io.redspace.ironsspellbooks.spells.EntityCastData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * SANDS OF TIME — конус песка, устроенный как дыхания Iron's Spells (Cone of Cold): непрерывный
 * каст на 5 секунд, импульс раз в полсекунды.
 *
 * <p>Начальный урон — {@value #BASE_DAMAGE} в секунду на первом уровне (+{@value #DAMAGE_PER_LEVEL}
 * за уровень, умножается на силу заклинаний). С каждой секундой под струёй цель замедляется ещё на
 * 20% и получает от этого заклинания на 20% меньше; кто простоял под песком весь каст — застывает
 * в стазисе на 2 секунды. Скорость замедления и стазис от силы заклинаний не зависят.
 * Вся механика цели — в {@link SandsOfTime}.
 */
public final class SandsOfTimeSpell extends AbstractSpell {
    public static final int MAX_LEVEL = 5;
    public static final int BASE_DAMAGE = 8;
    public static final int DAMAGE_PER_LEVEL = 2;
    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "sands_of_time");

    private final DefaultConfig config = new DefaultConfig()
            .setMinRarity(SpellRarity.COMMON)
            .setSchoolResource(ChronoSchools.CHRONOMANCY_RESOURCE)
            .setMaxLevel(MAX_LEVEL)
            .setCooldownSeconds(12)
            .build();

    public SandsOfTimeSpell() {
        this.baseSpellPower = BASE_DAMAGE;
        this.spellPowerPerLevel = DAMAGE_PER_LEVEL;
        this.castTime = 100;
        // мана списывается за каждый импульс, как у дыханий
        this.baseManaCost = 6;
        this.manaCostPerLevel = 1;
    }

    @Override
    public ResourceLocation getSpellResource() {
        return ID;
    }

    @Override
    public DefaultConfig getDefaultConfig() {
        return config;
    }

    @Override
    public CastType getCastType() {
        return CastType.CONTINUOUS;
    }

    /** Начальный урон в секунду — до ослабления слоями песка. */
    public float getDamage(int spellLevel, LivingEntity caster) {
        // Числа урона — из конфига мода; сила заклинаний кастера и множитель Iron's Spells остаются.
        float coded = BASE_DAMAGE + DAMAGE_PER_LEVEL * Math.max(0, spellLevel - 1);
        float configured = (float) (com.chronomancy.ChronoConfig.sandsBaseDamage()
                + com.chronomancy.ChronoConfig.sandsDamagePerLevel() * Math.max(0, spellLevel - 1));
        return coded <= 0.0F ? 0.0F : getSpellPower(spellLevel, caster) * configured / coded;
    }

    @Override
    public List<MutableComponent> getUniqueInfo(int spellLevel, LivingEntity caster) {
        return List.of(
                Component.translatable("spell.chronomancy.sands_of_time.damage",
                        Utils.stringTruncation(getDamage(spellLevel, caster), 1)),
                Component.translatable("spell.chronomancy.sands_of_time.slow", SandsOfTime.SLOW_PERCENT_PER_STACK + "%"),
                Component.translatable("spell.chronomancy.sands_of_time.stasis",
                        Utils.stringTruncation(SandsOfTime.stasisTicks() / 20.0F, 1) + "s"));
    }

    @Override
    public void onCast(Level level, int spellLevel, LivingEntity caster, CastSource castSource, MagicData magicData) {
        if (magicData.isCasting() && magicData.getCastingSpellId().equals(getSpellId())
                && magicData.getAdditionalCastData() instanceof EntityCastData castData
                && castData.getCastingEntity() instanceof AbstractConeProjectile cone) {
            cone.setDealDamageActive();
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.SAND_BREAK,
                    SoundSource.PLAYERS, 0.7F, 0.8F + caster.getRandom().nextFloat() * 0.3F);
        } else {
            SandsOfTimeProjectile cone = new SandsOfTimeProjectile(level, caster);
            cone.setPos(caster.position().add(0, caster.getEyeHeight() * 0.7D, 0));
            cone.setDamage(getDamage(spellLevel, caster));
            level.addFreshEntity(cone);
            magicData.setAdditionalCastData(new EntityCastData(cone));
        }
        super.onCast(level, spellLevel, caster, castSource, magicData);
    }
}
