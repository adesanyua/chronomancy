package com.chronomancy.mixin;

import com.chronomancy.temporal.TemporalPlayerClock;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Effects tick through vanilla so periodic healing, poison and expiration keep their semantics. */
@Mixin(LivingEntity.class)
public abstract class PersonalTimeLivingMixin {
    @Shadow protected abstract void tickEffects();
    @Shadow protected abstract void updateUsingItem(ItemStack stack);

    @Redirect(method = "baseTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;tickEffects()V"))
    private void chronomancy$effects(LivingEntity entity) {
        int steps = entity instanceof ServerPlayer player ? TemporalPlayerClock.steps(player) : 1;
        for (int i = 0; i < steps; i++) this.tickEffects();
    }

    @Redirect(method = "updatingUsingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;updateUsingItem(Lnet/minecraft/world/item/ItemStack;)V"))
    private void chronomancy$consume(LivingEntity entity, ItemStack stack) {
        boolean consumable = stack.getUseAnimation() == UseAnim.EAT || stack.getUseAnimation() == UseAnim.DRINK;
        int steps = consumable && entity instanceof ServerPlayer player ? TemporalPlayerClock.steps(player) : 1;
        if (consumable && entity.level().isClientSide() && entity instanceof net.minecraft.world.entity.player.Player player) {
            steps = com.chronomancy.client.BorrowedFutureClientState.consumptionSteps(player);
        }
        for (int i = 0; i < steps && entity.isUsingItem(); i++) this.updateUsingItem(stack);
    }

    @Redirect(method = "baseTick", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/common/CommonHooks;onLivingBreathe(Lnet/minecraft/world/entity/LivingEntity;II)V"))
    private void chronomancy$breathe(LivingEntity entity, int consume, int refill) {
        int steps = entity instanceof ServerPlayer player ? TemporalPlayerClock.steps(player) : 1;
        for (int i = 0; i < steps; i++) CommonHooks.onLivingBreathe(entity, consume, refill);
    }
}
