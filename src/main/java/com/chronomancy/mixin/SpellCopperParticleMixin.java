package com.chronomancy.mixin;

import com.chronomancy.client.SpellCopperClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Timeless Book: частицы, которые порождает сущность сжатого заклинания, уходят в медный слой.
 * Любая новая частица проходит через {@code ParticleEngine#add}; здесь она подменяется медной
 * обёрткой, если её создал тик «медной» сущности (см. {@link SpellCopperClient}).
 */
@Mixin(ParticleEngine.class)
public abstract class SpellCopperParticleMixin {

    @ModifyVariable(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), argsOnly = true)
    private Particle chronomancy$copperSpellParticle(Particle particle) {
        return SpellCopperClient.wrapParticle(particle);
    }
}
