package com.chronomancy.mixin;

import com.chronomancy.temporal.TemporalDilationPlayerMovement;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Рывок прыжка с разбега (ваниль добавляет 0.2 блока/тик вперёд) под Time Dilation
 * масштабируется так же, как шаг, — иначе спринт-прыжки выносили игрока из поля.
 */
@Mixin(LivingEntity.class)
public abstract class TemporalDilationJumpMixin {
    @ModifyConstant(method = "jumpFromGround", constant = @Constant(doubleValue = 0.2D), require = 0)
    private double chronomancy$dilateSprintJump(double boost) {
        if ((Object) this instanceof Player player) {
            return boost * TemporalDilationPlayerMovement.slowFactor(player);
        }
        return boost;
    }
}
