package com.chronomancy.item;

public final class ClockHandDamageTest {
    public static void main(String[] args) {
        check(8, false, false, false, 8);
        check(8, true, false, false, 24);
        check(8, false, true, false, 24);
        check(8, true, true, false, 24); // Never 9x when both freezes apply.
        check(24, false, false, true, 24);
        check(24, true, false, true, 24); // Target may enter Stasis before buffer release.
        check(24, true, true, true, 24);
        check(0, true, true, false, 0);
        check(-1, true, true, false, -1);
        check(2.5f, true, false, false, 7.5f);
        System.out.println("ClockHandDamage: normal/stasis/stop/overlap/replay/nonpositive/fractional PASS");
    }
    private static void check(float base, boolean stasis, boolean stopped, boolean replaying, float expected) {
        float actual = ClockHandDamage.amount(base, stasis, stopped, replaying);
        if (actual != expected) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
