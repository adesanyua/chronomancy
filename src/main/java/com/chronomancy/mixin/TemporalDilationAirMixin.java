package com.chronomancy.mixin;

import com.chronomancy.temporal.TemporalDilationPlayerMovement;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Управление в воздухе под Time Dilation.
 *
 * <p>Шаг по земле замедляется атрибутом скорости, но в прыжке ваниль берёт фиксированную
 * «воздушную» скорость 0.02/0.026 — атрибут на неё не влияет, и из купола можно было уйти
 * прыжками почти на полной скорости. Теперь воздушная скорость масштабируется тем же
 * множителем, что и шаг (полёт в креативе не трогаем).
 */
@Mixin(Player.class)
public abstract class TemporalDilationAirMixin {
    @Inject(method = "getFlyingSpeed", at = @At("RETURN"), cancellable = true)
    private void chronomancy$dilateAirSpeed(CallbackInfoReturnable<Float> cir) {
        Player self = (Player) (Object) this;
        if (self.getAbilities().flying) return;
        float f = TemporalDilationPlayerMovement.slowFactor(self);
        if (f < 1.0F) cir.setReturnValue(cir.getReturnValueF() * f);
    }
}
