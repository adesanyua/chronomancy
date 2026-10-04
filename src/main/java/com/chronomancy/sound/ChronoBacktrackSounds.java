package com.chronomancy.sound;

import com.chronomancy.registry.ChronoSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/** Backtrack launch, rewind impact and deferred temporal wound. */
public final class ChronoBacktrackSounds {
    private ChronoBacktrackSounds() {}
    private static void play(Level level, LivingEntity e, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, e.getX(), e.getY() + e.getBbHeight() * 0.5, e.getZ(),
                sound, SoundSource.PLAYERS, volume, pitch);
    }
    public static void playCast(Level level, LivingEntity caster) {
        play(level, caster, ChronoSounds.PROJECTILE_LAUNCH.get(), 0.65f, 1.25f);
    }
    public static void playRewindHit(Level level, LivingEntity target) {
        play(level, target, ChronoSounds.TEMPORAL_REWIND.get(), 0.85f, 0.75f);
    }
    public static void playDelayedDamageCrack(Level level, LivingEntity target) {
        play(level, target, ChronoSounds.TEMPORAL_CRACK.get(), 0.85f, 1.15f);
    }
}
