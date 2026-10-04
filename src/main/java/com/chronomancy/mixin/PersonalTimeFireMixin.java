package com.chronomancy.mixin;

import com.chronomancy.temporal.TemporalPlayerClock;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Advance fire by personal ticks, including each scheduled burn pulse. */
@Mixin(Entity.class)
public abstract class PersonalTimeFireMixin {
    @Redirect(method = "baseTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setRemainingFireTicks(I)V"))
    private void chronomancy$fireTimer(Entity entity, int remaining) {
        if (!(entity instanceof ServerPlayer player) || entity.fireImmune()
                || remaining != entity.getRemainingFireTicks() - 1) {
            entity.setRemainingFireTicks(remaining);
            return;
        }
        int old = entity.getRemainingFireTicks();
        int steps = TemporalPlayerClock.steps(player);
        // Vanilla already processed the first burn pulse (if any).
        for (int i = 1; i < steps && i < old; i++) {
            if ((old - i) % 20 == 0 && !entity.isInLava()) {
                entity.hurt(entity.damageSources().onFire(), 1.0F);
            }
        }
        entity.setRemainingFireTicks(Math.max(0, old - steps));
    }

    @Redirect(method = "baseTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean chronomancy$firstFirePulse(Entity entity, DamageSource source, float amount) {
        if (entity instanceof ServerPlayer player && TemporalPlayerClock.steps(player) == 0) return false;
        return entity.hurt(source, amount);
    }
}
