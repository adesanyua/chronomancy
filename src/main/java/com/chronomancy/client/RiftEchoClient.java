package com.chronomancy.client;

import com.chronomancy.entity.PlayerEchoEntity;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.world.entity.Entity;

/**
 * Клиентский список медных копий (заклинание Rift) + медное окно tint'а для их модели.
 * Двойники игрока ({@link PlayerEchoEntity}) медные всегда. Основной способ окраски —
 * шейдер {@link CopperEchoRendering}; tint ниже — запасной вариант без шейдера.
 */
public final class RiftEchoClient {
    private static final IntSet ECHOES = new IntOpenHashSet();
    private static final int TARGET_R = 0xB8, TARGET_G = 0x73, TARGET_B = 0x33;
    private static final float STRENGTH = 0.78F;
    private static boolean tintActive;

    private RiftEchoClient() {
    }

    public static void set(int entityId, boolean echo) {
        if (echo) ECHOES.add(entityId); else ECHOES.remove(entityId);
    }

    public static void clear() {
        ECHOES.clear();
        tintActive = false;
    }

    public static boolean isCopper(Entity entity) {
        return entity instanceof PlayerEchoEntity || entity instanceof com.chronomancy.entity.ChronoDoubleEntity
                || (entity != null && ECHOES.contains(entity.getId()));
    }

    public static void beginTint() { tintActive = true; }
    public static void endTint() { tintActive = false; }
    /** Старый tint вершин — только если медный шейдер недоступен (иначе медь двоилась бы). */
    public static boolean tintActive() { return tintActive && !CopperEchoRendering.available(); }

    public static int apply(int color) {
        int a = color & 0xFF000000;
        int r = (color >>> 16) & 255;
        int g = (color >>> 8) & 255;
        int b = color & 255;
        r = Math.round(r + (TARGET_R - r) * STRENGTH);
        g = Math.round(g + (TARGET_G - g) * STRENGTH);
        b = Math.round(b + (TARGET_B - b) * STRENGTH);
        return a | (r << 16) | (g << 8) | b;
    }
}
