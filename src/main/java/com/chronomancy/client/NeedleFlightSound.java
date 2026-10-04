package com.chronomancy.client;

import com.chronomancy.entity.TemporalNeedleEntity;
import com.chronomancy.registry.ChronoSounds;
import com.chronomancy.temporal.TemporalDilationHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/** One quiet positional loop per flying needle; lifetime is bound to the tracked projectile. */
public final class NeedleFlightSound extends AbstractTickableSoundInstance {
    private final TemporalNeedleEntity needle;
    private NeedleFlightSound(TemporalNeedleEntity needle) {
        super(ChronoSounds.NEEDLE_FLIGHT.get(), SoundSource.PLAYERS, RandomSource.create());
        this.needle = needle;
        looping = true;
        delay = 0;
        volume = .16f;
        x = needle.getX(); y = needle.getY(); z = needle.getZ();
    }
    public static void start(TemporalNeedleEntity needle) {
        Minecraft.getInstance().getSoundManager().play(new NeedleFlightSound(needle));
    }
    @Override public boolean canStartSilent() { return true; }
    @Override public void tick() {
        if (needle.isRemoved() || Minecraft.getInstance().level != needle.level()) {
            stop();
            return;
        }
        x = needle.getX(); y = needle.getY(); z = needle.getZ();
        double rate = TemporalDilationHandler.clientMoveScale(needle);
        volume = ClientWorldStopState.isActive() || needle.getDeltaMovement().lengthSqr() < 1e-6 ? 0 : .16f;
        pitch = (float)Math.clamp(.7 + .3 * rate, .5, 1.1);
    }
}
