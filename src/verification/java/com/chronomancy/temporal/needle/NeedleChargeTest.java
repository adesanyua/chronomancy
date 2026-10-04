package com.chronomancy.temporal.needle;

/** Dependency-free regression checks, executed by Gradle's check/build. */
public final class NeedleChargeTest {
    public static void main(String[] args) {
        NeedleCharge chain = new NeedleCharge(8);
        for (int hit = 1; hit <= 8; hit++) {
            check(chain.hit(hit * 20), "hit must add a stack");
            check(chain.stacks() == hit, "stack count");
            check(close(NeedleCharge.damage(chain.stacks(), 1, BASE, GROWTH), BASE * Math.pow(GROWTH, hit - 1)),
                    "first needle = base, every further one multiplies by the growth");
        }
        check(!chain.hit(200), "cap must not add a needle");
        check(chain.stacks() == 8 && chain.deadline() == 280, "cap refreshes full timeout");
        check(!chain.expired(279) && chain.expired(280), "80 external ticks");
        chain.hit(278); // 3.9 s after previous hit
        check(!chain.expired(280) && chain.expired(358), "late hit resets all four seconds");
        check(NeedleCharge.damage(1, 1, BASE, GROWTH) == BASE, "one needle deals the base damage");
        check(close(NeedleCharge.damage(4, 1, BASE, GROWTH), 22.864), "four needles");
        check(close(NeedleCharge.damage(8, 1, BASE, GROWTH), 101.07), "eight needles");
        check(close(NeedleCharge.damage(8, 2, BASE, GROWTH), 2 * NeedleCharge.damage(8, 1, BASE, GROWTH)),
                "power applied once, not at each hit");
        check(close(NeedleCharge.damage(12, 1, BASE, GROWTH), 446.79), "level V max collapse");
        check(NeedleCharge.damage(13, 1, BASE, GROWTH) == NeedleCharge.damage(12, 1, BASE, GROWTH), "never more than twelve needles");
        check(NeedleCharge.damage(0, 1, BASE, GROWTH) == 0 && NeedleCharge.damage(3, -1, BASE, GROWTH) == 0, "no needles or no power - no damage");
        NeedleCharge otherCaster = new NeedleCharge(9);
        otherCaster.hit(278);
        check(otherCaster.stacks() == 1 && chain.stacks() == 8, "independent chain state");
        check(otherCaster.expired(358), "no target tick is needed to expire");
        check(!new NeedleCharge(8).expired(Long.MAX_VALUE), "empty chain never collapses");
        System.out.println("NeedleCharge: sequence, cap, refresh, timeout, power and independent chains PASS");
    }
    /** Default balance (needles.firstNeedleDamage, needles.growthPerStack); the config itself needs the game. */
    private static final double BASE = 7.5, GROWTH = 1.45;
    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) <= Math.max(1.0e-9, Math.abs(expected) * 1.0e-3);
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
