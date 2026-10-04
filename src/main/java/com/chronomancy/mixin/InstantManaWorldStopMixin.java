package com.chronomancy.mixin;

import com.chronomancy.temporal.worldstop.InstantRestoration;
import io.redspace.ironsspellbooks.effect.InstantManaEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Мгновенная мана Iron's Spells работает и для кастера The World Stop: пока эффект применяется,
 * рост маны не отменяется (см. {@code WorldStopEvents#onChangeMana}). Пассивная регенерация
 * по-прежнему стоит. {@code applyEffectTick} у этого эффекта сам зовёт {@code applyInstantenousEffect}.
 */
@Mixin(value = InstantManaEffect.class, remap = false)
public abstract class InstantManaWorldStopMixin {

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
}
