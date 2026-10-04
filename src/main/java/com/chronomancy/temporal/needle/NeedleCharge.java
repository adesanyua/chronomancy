package com.chronomancy.temporal.needle;

/** Pure chain arithmetic. The caller supplies external server ticks, never entity time. */
public final class NeedleCharge {
    public static final int COLLAPSE_DELAY = 80;
    private final int maxStacks;
    private final int collapseDelay;
    private int stacks;
    private long deadline;

    public NeedleCharge(int maxStacks) {
        this(maxStacks, COLLAPSE_DELAY);
    }

    public NeedleCharge(int maxStacks, int collapseDelay) {
        this.collapseDelay = collapseDelay;
        if (maxStacks < 1 || maxStacks > 12) throw new IllegalArgumentException("Needle stack limit outside 1..12");
        this.maxStacks = maxStacks;
    }
    /** @return true only if the hit adds a new attachment (cap hits merely refresh the deadline). */
    public boolean hit(long externalTick) {
        deadline = externalTick + collapseDelay;
        if (stacks >= maxStacks) return false;
        stacks++;
        return true;
    }
    public int stacks() { return stacks; }
    public long deadline() { return deadline; }
    public boolean expired(long externalTick) { return stacks > 0 && externalTick >= deadline; }
    /**
     * Урон схлопывания: одна игла — {@code needles.baseDamage}, каждая следующая умножает его на
     * {@code needles.growthPerStack}. Раньше было 0.2 × 2^иглы: первые иглы почти не ранили (0.4, 0.8,
     * 1.6), а двенадцать давали 819. Теперь 7.5 и ×1.45: малые стаки намного сильнее, самые большие —
     * слабее (4 иглы — 22.9 вместо 3.2, 8 — 101 вместо 51, 12 — 447 вместо 819). Кривая одна на всех:
     * игроки, хрономали и Часовщик.
     */
    public static double damage(int stacks, double power) {
        return damage(stacks, power, com.chronomancy.ChronoConfig.needleBaseDamage(),
                com.chronomancy.ChronoConfig.needleGrowth());
    }

    /** The same curve with explicit numbers: pure arithmetic, used by the standalone regression check. */
    public static double damage(int stacks, double power, double baseDamage, double growth) {
        if (stacks <= 0) return 0;
        return baseDamage * Math.pow(growth, Math.min(12, stacks) - 1) * Math.max(0, power);
    }
}
