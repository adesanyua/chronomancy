package com.chronomancy.sound;

import com.chronomancy.registry.ChronoSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/** Original synthesized clockwork sounds, broadcast once from the server. */
public final class ChronoStasisSounds {
    private ChronoStasisSounds() {}
    private static void play(Level level, LivingEntity e, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, e.getX(), e.getY() + e.getBbHeight() * 0.5, e.getZ(),
                sound, SoundSource.PLAYERS, volume, pitch);
    }
    public static void playHit(Level level, LivingEntity target) {
        play(level, target, ChronoSounds.TEMPORAL_CRACK.get(), 0.9f, 0.85f);
    }
    public static void playActiveTick(Level level, LivingEntity target) {
        play(level, target, ChronoSounds.CLOCK_TICK.get(), 0.2f, 0.7f);
        if (level.random.nextFloat() < 0.18f) play(level, target, ChronoSounds.TEMPORAL_SAND.get(), 0.2f, 1f);
    }
    public static void playCapacityBreak(Level level, LivingEntity target) {
        play(level, target, ChronoSounds.TEMPORAL_CRACK.get(), 1f, 0.75f);
        play(level, target, ChronoSounds.TEMPORAL_SAND.get(), 0.6f, 0.8f);
    }
    public static void playRewind(Level level, LivingEntity caster) {
        play(level, caster, ChronoSounds.TEMPORAL_REWIND.get(), 0.9f, 0.85f);
    }
    public static void playRewindArrive(Level level, LivingEntity caster) {
        play(level, caster, ChronoSounds.TEMPORAL_RELEASE.get(), 0.7f, 1.2f);
    }
    public static void playWorldStopStart(Level level, LivingEntity caster) {
        play(level, caster, ChronoSounds.TEMPORAL_CRACK.get(), 1f, 0.65f);
        play(level, caster, ChronoSounds.TEMPORAL_HUM.get(), 0.55f, 0.7f);
    }
    public static void playWorldStopEnd(Level level, LivingEntity caster) {
        play(level, caster, ChronoSounds.TEMPORAL_RELEASE.get(), 1f, 1f);
    }
}
