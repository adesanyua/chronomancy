package com.chronomancy.sound;

import com.chronomancy.registry.ChronoSounds;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/** Standalone copper reflection sounds, not repitched vanilla samples. */
public final class ChronoDoubleSounds {
    private ChronoDoubleSounds() {}
    public static void playSummon(Level level, LivingEntity caster) {
        level.playSound(null, caster.getX(), caster.getY() + caster.getBbHeight() * 0.5, caster.getZ(),
                ChronoSounds.DOUBLE_SUMMON.get(), SoundSource.PLAYERS, 0.85f, 1f);
    }
    public static void playEcho(Level level, LivingEntity target) {
        level.playSound(null, target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                ChronoSounds.DOUBLE_ECHO.get(), SoundSource.PLAYERS, 0.7f, 1.1f);
    }
}
