package com.chronomancy.mixin;

import com.chronomancy.temporal.TemporalPlayerClock;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Damage immunity is a personal clock too; the normal ServerPlayer tick still runs once. */
@Mixin(ServerPlayer.class)
public abstract class PersonalTimeServerPlayerMixin {
    @Unique private int chronomancy$immunityBefore;

    @Inject(method = "tick", at = @At("HEAD"))
    private void chronomancy$saveImmunity(CallbackInfo ci) {
        chronomancy$immunityBefore = ((ServerPlayer) (Object) this).invulnerableTime;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void chronomancy$advanceImmunity(CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        int before = chronomancy$immunityBefore;
        if (before > 0 && player.invulnerableTime == before - 1) {
            player.invulnerableTime = Math.max(0, before - TemporalPlayerClock.steps(player));
        }
    }
}
