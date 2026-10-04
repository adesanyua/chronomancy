package com.chronomancy.temporal.borrowed;

import com.chronomancy.registry.ChronoSchools;
import net.minecraft.world.entity.LivingEntity;

public record BorrowedFutureStats(int durationTicks, double empoweredRate, double debtRate) {
    public static final int DURATION_TICKS = 20 * 20;

    /**
     * Цена ускорения: пока идёт заёмное время, получаемый урон выше на десятую долю ускорения —
     * при x2.75 (275%) это +27.5%. Возвращает долю (0.275).
     */
    public static double damageTakenBonus(double empoweredRate) {
        return Math.max(0.0, empoweredRate) * com.chronomancy.ChronoConfig.borrowedVulnerability();
    }

    public static BorrowedFutureStats calculate(int level, LivingEntity caster) {
        // Level I (Uncommon) = 250% (x2.5); each additional scroll level adds
        // 30 percentage points (x0.3). School power remains an extra bonus.
        int rank = Math.max(0, Math.min(4, level - 1));
        double power = ChronoSchools.totalSpellPower(caster);
        double bonus = Math.max(0.0, power - 1.0);
        double empowered = Math.min(10.0, 2.5 + rank * 0.3 + bonus * 0.3);
        double debt = Math.max(0.25, 0.5 - (empowered - 2.0) * 0.25);
        return new BorrowedFutureStats(DURATION_TICKS, empowered, debt);
    }
}
