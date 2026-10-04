package com.chronomancy.spell;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.registry.ChronoSchools;
import com.chronomancy.temporal.needle.TemporalNeedleManager;
import io.redspace.ironsspellbooks.api.config.DefaultConfig;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import java.util.List;
import java.util.Locale;

public final class TimePiercingNeedleSpell extends AbstractSpell {
    public static final int MAX_LEVEL = 5;
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "time_piercing_needle");
    private final DefaultConfig config = new DefaultConfig().setMinRarity(SpellRarity.UNCOMMON)
            .setSchoolResource(ChronoSchools.CHRONOMANCY_RESOURCE).setMaxLevel(MAX_LEVEL)
            .setCooldownSeconds(1).build();

    /** Мана по уровням: прежние 35/40/45/50/55, сниженные на 30%. */
    private static final int[] MANA_COST_BY_LEVEL = {25, 28, 32, 35, 39};

    public TimePiercingNeedleSpell() {
        baseManaCost = MANA_COST_BY_LEVEL[0];
        manaCostPerLevel = 0;
        castTime = 0;
    }

    /** Таблица вместо «база + прирост за уровень»; множитель маны из конфига Iron's Spells сохраняется. */
    @Override
    public int getManaCost(int level) {
        int cost = MANA_COST_BY_LEVEL[Math.min(Math.max(level, 1), MANA_COST_BY_LEVEL.length) - 1];
        double multiplier = io.redspace.ironsspellbooks.api.config.SpellConfigManager.getSpellConfigValue(this,
                io.redspace.ironsspellbooks.api.config.SpellConfigParameter.MANA_MULTIPLIER);
        return (int) (cost * multiplier);
    }

    public static int maxStacks(int level) { return 8 + Math.max(0, Math.min(MAX_LEVEL - 1, level - 1)); }
    public static float collapseDamage(int stacks, double power) {
        return (float) com.chronomancy.temporal.needle.NeedleCharge.damage(stacks, power);
    }
    @Override public ResourceLocation getSpellResource() { return ID; }
    @Override public DefaultConfig getDefaultConfig() { return config; }
    @Override public CastType getCastType() { return CastType.INSTANT; }

    /** Анимация каста: бросок иглы. */
    @Override
    public io.redspace.ironsspellbooks.api.util.AnimationHolder getCastStartAnimation() {
        return io.redspace.ironsspellbooks.api.spells.SpellAnimations.THROW_SINGLE_ITEM;
    }
    @Override public List<MutableComponent> getUniqueInfo(int level, LivingEntity caster) {
        return List.of(Component.translatable("spell.chronomancy.time_piercing_needle.charge"),
                Component.translatable("spell.chronomancy.time_piercing_needle.stacks", maxStacks(level)),
                Component.translatable("spell.chronomancy.time_piercing_needle.collapse_delay"),
                Component.translatable("spell.chronomancy.time_piercing_needle.needle_delay"),
                Component.translatable("spell.chronomancy.time_piercing_needle.damage",
                        String.format(Locale.ROOT, "%.1f", collapseDamage(maxStacks(level), getEntityPowerMultiplier(caster)))));
    }
    @Override public void onCast(Level level, int spellLevel, LivingEntity caster, CastSource source, MagicData data) {
        if (!level.isClientSide) TemporalNeedleManager.enqueue(caster, spellLevel);
        super.onCast(level, spellLevel, caster, source, data);
    }
}
