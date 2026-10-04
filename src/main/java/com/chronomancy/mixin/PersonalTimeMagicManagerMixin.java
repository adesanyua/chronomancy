package com.chronomancy.mixin;

import com.chronomancy.temporal.PersonalTimeMana;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep ISS's world-clock regeneration for ordinary players; replace it only during dilation. */
@Mixin(value = MagicManager.class, remap = false)
public abstract class PersonalTimeMagicManagerMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void chronomancy$personalMana(Level level, CallbackInfo ci) {
        if (!level.isClientSide()) PersonalTimeMana.onMagicTick((MagicManager) (Object) this, level);
    }

    @Inject(method = "regenPlayerMana", at = @At("HEAD"), cancellable = true)
    private void chronomancy$skipWorldMana(ServerPlayer player, MagicData data, CallbackInfoReturnable<Boolean> ci) {
        if (PersonalTimeMana.controls(player) && !PersonalTimeMana.isReplaying()) ci.setReturnValue(false);
    }
}
