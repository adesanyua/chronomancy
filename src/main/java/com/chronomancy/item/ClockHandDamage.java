package com.chronomancy.item;

/** Attack-time multiplier shared with the standalone regression check. */
public final class ClockHandDamage {
    private ClockHandDamage() {}

    public static float amount(float base, boolean stasis, boolean stopped, boolean replaying) {
        return amount(base, stasis, stopped, replaying, 0.0);
    }

    /**
     * @param slow насколько цель замедлена во времени, 0..1. Замедленная цель получает +100% и ещё
     *             столько процентов, на сколько она замедлена: при 70% замедления — +170% (×2.7).
     *             Полная остановка (стазис, остановленное время) — те же +200% (×3), что и раньше.
     */
    public static float amount(float base, boolean stasis, boolean stopped, boolean replaying, double slow) {
        if (replaying || base <= 0) {
            return base;
        }
        if (stasis || stopped) {
            return base * 3.0f;
        }
        return slow > 1.0e-3 ? base * (2.0f + (float) Math.min(1.0, slow)) : base;
    }
}
