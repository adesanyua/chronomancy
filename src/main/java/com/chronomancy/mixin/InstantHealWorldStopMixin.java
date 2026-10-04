package com.chronomancy.mixin;

import com.chronomancy.temporal.worldstop.InstantRestoration;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ванильное Мгновенное лечение работает и для кастера The World Stop: всё, что оно вылечило, пока
 * время стоит, сохраняется (обычное лечение откатывается — биологическое время кастера заморожено).
 * Оба пути эффекта: {@code applyInstantenousEffect} (выпитое, взрывное, туманное зелье, стрела) и
 * {@code applyEffectTick} (эффект, выданный через {@code addEffect}: эликсиры, команды).
 */
@Mixin(targets = "net.minecraft.world.effect.HealOrHarmMobEffect")
public abstract class InstantHealWorldStopMixin {

    @Inject(method = "applyInstantenousEffect", at = @At("HEAD"))
    private void chronomancy$instantBegin(Entity source, Entity indirectSource, LivingEntity target,
                                          int amplifier, double health, CallbackInfo ci) {
        if (!target.level().isClientSide) InstantRestoration.begin(target);
    }

    @Inject(method = "applyInstantenousEffect", at = @At("RETURN"))
    private void chronomancy$instantEnd(Entity source, Entity indirectSource, LivingEntity target,
                                        int amplifier, double health, CallbackInfo ci) {
        if (!target.level().isClientSide) InstantRestoration.end(target);
    }

    @Inject(method = "applyEffectTick", at = @At("HEAD"))
    private void chronomancy$tickBegin(LivingEntity target, int amplifier, CallbackInfoReturnable<Boolean> ci) {
        if (!target.level().isClientSide) InstantRestoration.begin(target);
    }

    @Inject(method = "applyEffectTick", at = @At("RETURN"))
    private void chronomancy$tickEnd(LivingEntity target, int amplifier, CallbackInfoReturnable<Boolean> ci) {
        if (!target.level().isClientSide) InstantRestoration.end(target);
    }
}
