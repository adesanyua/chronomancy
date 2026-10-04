package com.chronomancy.spell;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.registry.ChronoSchools;
import com.chronomancy.temporal.borrowed.BorrowedFutureManager;
import com.chronomancy.temporal.borrowed.BorrowedFutureStats;
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

public final class BorrowedFutureSpell extends AbstractSpell {
    public static final String SPELL_ID = "chronomancy:borrowed_future";
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "borrowed_future");
    private final DefaultConfig config = new DefaultConfig().setMinRarity(SpellRarity.UNCOMMON)
            .setSchoolResource(ChronoSchools.CHRONOMANCY_RESOURCE).setMaxLevel(5)
            .setCooldownSeconds(120).build();

    public BorrowedFutureSpell() {
        baseManaCost = 300;
        manaCostPerLevel = 40;
        castTime = 20; // 1 с: сделка с будущим собой
    }

    @Override public ResourceLocation getSpellResource() { return ID; }
    @Override public DefaultConfig getDefaultConfig() { return config; }
    @Override public CastType getCastType() { return CastType.LONG; }

    /** Анимация каста: на колено, затем обе руки к себе. */
    @Override
    public io.redspace.ironsspellbooks.api.util.AnimationHolder getCastStartAnimation() {
        return io.redspace.ironsspellbooks.api.spells.SpellAnimations.CAST_KNEELING_PRAYER;
    }

    @Override
    public io.redspace.ironsspellbooks.api.util.AnimationHolder getCastFinishAnimation() {
        return io.redspace.ironsspellbooks.api.spells.SpellAnimations.SELF_CAST_TWO_HANDS;
    }

    @Override public List<MutableComponent> getUniqueInfo(int level, LivingEntity caster) {
        BorrowedFutureStats stats = BorrowedFutureStats.calculate(level, caster);
        return List.of(
                Component.translatable("spell.chronomancy.borrowed_future.empowered", String.format(Locale.ROOT, "%.1f", stats.durationTicks() / 20.0), String.format(Locale.ROOT, "%.2f", stats.empoweredRate())),
                Component.translatable("spell.chronomancy.borrowed_future.debt", String.format(Locale.ROOT, "%.1f", stats.durationTicks() / 20.0), String.format(Locale.ROOT, "%.2f", stats.debtRate())),
                Component.translatable("spell.chronomancy.borrowed_future.recovery", String.format(Locale.ROOT, "+%.0f%%", (stats.empoweredRate() - 1) * 100), String.format(Locale.ROOT, "%.0f%%", (stats.debtRate() - 1) * 100)),
                Component.translatable("spell.chronomancy.borrowed_future.vulnerable", String.format(Locale.ROOT, "+%.1f%%", BorrowedFutureStats.damageTakenBonus(stats.empoweredRate()) * 100)),
                Component.translatable("spell.chronomancy.borrowed_future.ward", String.format(Locale.ROOT, "-%.0f%%", (1.0 - com.chronomancy.ChronoConfig.borrowedWardShare()) * 100))
        );
    }

    @Override public void onCast(Level level, int spellLevel, LivingEntity caster, CastSource source, MagicData data) {
        if (!level.isClientSide && caster instanceof ServerPlayer player) {
            BorrowedFutureManager.start(player, BorrowedFutureStats.calculate(spellLevel, caster));
        } else if (!level.isClientSide && caster instanceof com.chronomancy.entity.ChronoMobCaster mob) {
            mob.mobBorrowedFuture(spellLevel); // Часовщик: разгон, затем долг
        }
        super.onCast(level, spellLevel, caster, source, data);
    }
}
