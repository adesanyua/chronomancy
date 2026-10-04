package com.chronomancy.sound;

import com.chronomancy.registry.ChronoSounds;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Rising-pitch custom needle tick; charge collapse has its own snap. */
public final class ChronoNeedleSounds {
    private ChronoNeedleSounds() {}
    public static void playHit(Level level, Vec3 position, int stacks) {
        float pitch = Math.min(2f, 0.88f + Math.min(stacks, 12) * 0.075f);
        level.playSound(null, position.x, position.y, position.z, ChronoSounds.NEEDLE_HIT.get(),
                SoundSource.PLAYERS, 0.72f, pitch);
    }
    public static void playCollapse(Level level, LivingEntity target, int stacks) {
        level.playSound(null, target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                ChronoSounds.NEEDLE_COLLAPSE.get(), SoundSource.PLAYERS,
                Math.min(1.25f, 0.65f + stacks * 0.045f), 0.95f);
    }
}
