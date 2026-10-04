package com.chronomancy.temporal.borrowed;

import com.chronomancy.ChronoConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Игрок под Borrowed Future (обе фазы — ускорение и долг, любой уровень заклинания) хуже поддаётся
 * чужой магии времени: стазис на нём короче, а замедление слабее (по умолчанию вдвое —
 * {@code borrowed_future.stasisAndSlowShare} в конфиге).
 *
 * <p>Касается только того, что приходит извне: длительности Temporal Stasis (луч, Cracked Dial, удар
 * Rift Maker) и замедления от полей, слоёв песка и «вязкой» половины парадокса. Собственный долг
 * заклинания, ускорение (зоны, быстрая половина парадокса) и The World Stop не меняются.
 */
public final class BorrowedFutureWard {
    private BorrowedFutureWard() {
    }

    /** Игрок сейчас под Borrowed Future. Работает на обеих сторонах: клиент знает фазу из рассылки. */
    public static boolean protects(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            return BorrowedFutureManager.isActive(player);
        }
        if (entity instanceof Player player && player.level().isClientSide) {
            return com.chronomancy.client.BorrowedFutureClientState.phase(player.getId()) != BorrowedFuturePhase.NORMAL;
        }
        return false;
    }

    /** Длительность стазиса на цели (не меньше одного тика). */
    public static long scaleStasis(Entity target, long ticks) {
        if (ticks <= 0L || !protects(target)) {
            return ticks;
        }
        return Math.max(1L, Math.round(ticks * ChronoConfig.borrowedWardShare()));
    }

    /** Темп времени цели: ослабляется само замедление (0.6 -> 0.8), ускорение не трогается. */
    public static double weakenSlow(Entity entity, double rate) {
        if (rate >= 1.0D || !protects(entity)) {
            return rate;
        }
        return 1.0D - (1.0D - rate) * ChronoConfig.borrowedWardShare();
    }
}
