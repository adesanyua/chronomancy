package com.chronomancy.temporal;

import com.chronomancy.registry.ChronoMobEffectRegistry;
import com.chronomancy.temporal.borrowed.BorrowedFutureManager;
import com.chronomancy.temporal.worldstop.GlobalTimeStopManager;
import net.minecraft.server.level.ServerPlayer;

/** Shared personal tempo for player-facing temporal mechanics. */
public final class TemporalPlayerRate {
    public static final double MIN_RATE = 0.25;
    public static final double MAX_RATE = 10.0;

    private TemporalPlayerRate() {}

    public static double server(ServerPlayer player) {
        if (GlobalTimeStopManager.isActive() && !GlobalTimeStopManager.isCaster(player)) return 0;
        if (player.hasEffect(ChronoMobEffectRegistry.TEMPORAL_STASIS)) return 0;
        double borrowed = BorrowedFutureManager.rate(player);
        double field = TemporalDilationHandler.resolveRate(player.level(), player);
        return Math.max(MIN_RATE, Math.min(MAX_RATE, borrowed * field));
    }

}
