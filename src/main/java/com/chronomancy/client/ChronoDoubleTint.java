package com.chronomancy.client;

/**
 * Render-scope copper tint that turns a ChronoDouble into a copper-colored copy of the player.
 *
 * <p>Mirrors the {@link NeedleStackTint} / {@link BorrowedFutureTint} window pattern: opened
 * around a single {@code LivingEntityRenderer#render} call (here, the double renderer re-rendering
 * the live owner player model) and read at the bottom of the pipeline in
 * {@link com.chronomancy.mixin.TemporalStasisModelTintMixin}. The real skin texture and its alpha
 * are preserved; RGB is blended strongly toward copper, so the double reads as the player's own
 * animated figure but recolored copper, perfectly aligned to the vanilla model transform.
 *
 * <p>Checked before every other tint window so the double is always copper while it renders, and
 * only ever active for the duration of that inner render call.
 */
public final class ChronoDoubleTint {
    // Copper blend target (metallic red-orange, distinct from the gilded needle/borrowed hues).
    private static final int TARGET_R = 0xB8, TARGET_G = 0x73, TARGET_B = 0x33;
    private static final float STRENGTH = 0.80F;
    private static boolean active;

    private ChronoDoubleTint() {}

    public static void begin() { active = true; }
    public static void end() { active = false; }
    /** Tint вершин — только если медный шейдер недоступен (иначе двойник красится шейдером). */
    public static boolean active() { return active && !CopperEchoRendering.available(); }

    public static int apply(int color) {
        int a = color & 0xFF000000;
        int r = (color >>> 16) & 255;
        int g = (color >>> 8) & 255;
        int b = color & 255;
        float t = STRENGTH;
        r = Math.round(r + (TARGET_R - r) * t);
        g = Math.round(g + (TARGET_G - g) * t);
        b = Math.round(b + (TARGET_B - b) * t);
        return a | (r << 16) | (g << 8) | b;
    }
}
