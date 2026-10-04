package com.chronomancy.client;

import com.chronomancy.temporal.TemporalDilationHandler;
import com.chronomancy.temporal.TemporalPlayerRate;
import net.minecraft.world.entity.player.Player;

/** Client prediction of the server's shared personal rate. */
public final class TemporalPlayerClientRate {
    private TemporalPlayerClientRate() {}

    public static double get(Player player) {
        if (ClientWorldStopState.isActive() && player.getId() != ClientWorldStopState.getCasterEntityId()) return 0;
        if (TemporalStasisClientState.isFrozen(player.getId())) return 0;
        return Math.max(TemporalPlayerRate.MIN_RATE, Math.min(TemporalPlayerRate.MAX_RATE,
                BorrowedFutureClientState.rate(player.getId()) * TemporalDilationHandler.clientMoveScale(player)));
    }
}
