package com.chronomancy.effect;

import com.chronomancy.registry.ChronoMobEffectRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

public final class TemporalStasisCombatEvents {

    private TemporalStasisCombatEvents() {
    }

    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        Entity attacker = event.getSource().getEntity();
        Entity directEntity = event.getSource().getDirectEntity();

        if (isInStasis(attacker)) {
            event.setCanceled(true);
            return;
        }

        if (directEntity instanceof Projectile projectile) {
            Entity owner = projectile.getOwner();

            if (isInStasis(owner)) {
                event.setCanceled(true);
            }
        }
    }

    private static boolean isInStasis(Entity entity) {
        return entity instanceof LivingEntity living
                && living.hasEffect(ChronoMobEffectRegistry.TEMPORAL_STASIS);
    }
}