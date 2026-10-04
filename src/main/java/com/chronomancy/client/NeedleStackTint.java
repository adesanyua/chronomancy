package com.chronomancy.client;

import net.minecraft.world.entity.LivingEntity;

/**
 * Render-scope copper/gold tint that deepens with each temporal-needle stack on a target.
 *
 * <p>Mirrors the {@link BorrowedFutureTint} window pattern: opened by
 * {@link com.chronomancy.mixin.TemporalStasisRendererFreezeMixin} for the duration of a single
 * entity's {@code LivingEntityRenderer#render} call and read at the bottom of the pipeline in
 * {@link com.chronomancy.mixin.TemporalStasisModelTintMixin}. The skin texture and its alpha are
 * preserved; RGB is blended toward gold-copper in proportion to the current stack fraction, so a
 * target becomes gradually more gilded instead of showing stuck needles.
 */
public final class NeedleStackTint {
    // Gold-copper blend target (warm, matches the Chronomancy palette without flattening detail).
    private static final int TARGET_R = 0xE4, TARGET_G = 0xA6, TARGET_B = 0x4C;
    private static float strength;
    private NeedleStackTint() {}

    public static void begin(LivingEntity entity) {
        strength = TemporalNeedleClient.stackTintStrength(entity.getId());
    }
    public static void end() { strength = 0; }
    /** С фазовыми шейдерами заряд показывается нарастающим «глитчем» (VanillaPhase), а не позолотой. */
    public static boolean active() { return strength > 0 && !com.chronomancy.client.entity.TimePhaseRendering.available(); }

    public static int apply(int color) {
        int a = color & 0xFF000000;
        int r = (color >>> 16) & 255;
        int g = (color >>> 8) & 255;
        int b = color & 255;
        float t = strength;
        r = Math.round(r + (TARGET_R - r) * t);
        g = Math.round(g + (TARGET_G - g) * t);
        b = Math.round(b + (TARGET_B - b) * t);
        return a | (r << 16) | (g << 8) | b;
    }
}
