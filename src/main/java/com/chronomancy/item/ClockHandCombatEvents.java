package com.chronomancy.item;

import com.chronomancy.registry.ChronoItemRegistry;
import com.chronomancy.registry.ChronoMobEffectRegistry;
import com.chronomancy.temporal.worldstop.GlobalTimeStopManager;
import com.chronomancy.temporal.worldstop.TemporalDamageBuffer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** Runs before Stasis/World Stop capture; buffered hits are already multiplied. */
public final class ClockHandCombatEvents {
    private ClockHandCombatEvents() {}

    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity().level().isClientSide || TemporalDamageBuffer.isReplayingDamage()) return;
        var source = event.getSource();
        if (!source.is(DamageTypes.PLAYER_ATTACK) && !source.is(DamageTypes.MOB_ATTACK)) return;
        if (!(source.getEntity() instanceof LivingEntity attacker)
                || source.getDirectEntity() != attacker
                || !attacker.getMainHandItem().is(ChronoItemRegistry.CLOCK_HAND.get())) return;
        if (attacker.hasEffect(ChronoMobEffectRegistry.TEMPORAL_STASIS)
                || (GlobalTimeStopManager.isActive() && !GlobalTimeStopManager.isCaster(attacker))) return;
        var victim = event.getEntity();
        boolean stasis = victim.hasEffect(ChronoMobEffectRegistry.TEMPORAL_STASIS);
        // Chronomaly не заморожена стопом — тройной урон по «замороженным» к ней не относится.
        boolean stopped = GlobalTimeStopManager.isActive() && !GlobalTimeStopManager.isExempt(victim);
        // Замедленные (полем, песками времени, парадоксом) получают тем больше, чем сильнее замедлены.
        double slow = stasis || stopped ? 0.0
                : com.chronomancy.temporal.TemporalDilationHandler.slowFraction(victim.level(), victim);
        event.setAmount(ClockHandDamage.amount(event.getAmount(), stasis, stopped,
                TemporalDamageBuffer.isReplayingDamage(), slow));
    }
}
