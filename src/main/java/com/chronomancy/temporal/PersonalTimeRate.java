package com.chronomancy.temporal;

import net.minecraft.world.entity.Entity;

/**
 * Личный темп сущности, который задают не поля (см. {@link TemporalDilationHandler}), а эффекты
 * на ней самой: слои песка {@link SandsOfTime} и качели {@link TimeParadox парадокса}.
 * Только сервер: клиенту эти состояния не рассылаются (моб движется по серверным позициям,
 * игрок — через атрибуты скорости).
 */
public final class PersonalTimeRate {
    private PersonalTimeRate() {
    }

    /** 1.0 — личных эффектов нет; меньше — замедлен, больше — ускорен. */
    public static double rate(Entity entity) {
        // игрок под Borrowed Future: замедление от песка и вязкой половины парадокса вдвое слабее
        return com.chronomancy.temporal.borrowed.BorrowedFutureWard.weakenSlow(entity, SandsOfTime.rate(entity))
                * com.chronomancy.temporal.borrowed.BorrowedFutureWard.weakenSlow(entity, TimeParadox.rate(entity));
    }
}
