package com.chronomancy.client;

import net.minecraft.world.entity.LivingEntity;

/** Render-scope tint; preserves the skin texture and its original alpha. */
public final class BorrowedFutureTint {
    private static float strength;
    private BorrowedFutureTint() {}

    public static void begin(LivingEntity entity) {
        strength = BorrowedFutureClientState.debtTintStrength(entity.getId());
    }
    public static void end() { strength = 0; }
    /** При рабочих шейдерах долг показывается растворением (см. VanillaPhase), а не затемнением. */
    public static boolean active() {
        return strength > 0 && !com.chronomancy.client.entity.TimePhaseRendering.available();
    }

    public static int apply(int color) {
        int a = color & 0xFF000000;
        int r = (color >>> 16) & 255;
        int g = (color >>> 8) & 255;
        int b = color & 255;
        float t = strength;
        r = Math.round(r * (1 - 0.27f * t));
        g = Math.round(g * (1 - 0.39f * t));
        b = Math.round(b * (1 - 0.65f * t));
        return a | (r << 16) | (g << 8) | b;
    }
}
