package com.chronomancy.temporal.borrowed;

import net.minecraft.server.level.ServerPlayer;
import com.chronomancy.temporal.TemporalPlayerRate;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

public final class BorrowedFutureMining {
    private BorrowedFutureMining() {}

    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        double rate = TemporalPlayerRate.server(player);
        if (rate != 1.0) event.setNewSpeed((float) (event.getNewSpeed() * rate));
    }
}
