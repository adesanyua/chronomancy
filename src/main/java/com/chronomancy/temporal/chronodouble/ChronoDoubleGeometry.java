package com.chronomancy.temporal.chronodouble;

import com.chronomancy.spell.ChronoDoubleSpell;
import net.minecraft.world.phys.Vec3;

/** Shared server anchor and per-frame client prediction; yaw avoids instability when looking vertically. */
public final class ChronoDoubleGeometry {
    private ChronoDoubleGeometry() {}
    public static Vec3 anchor(Vec3 ownerPosition, float yaw, boolean left) {
        double angle = Math.toRadians(yaw);
        double distance = ChronoDoubleSpell.SIDE_LATERAL * (left ? 1 : -1);
        return ownerPosition.add(-Math.cos(angle) * distance, 0, -Math.sin(angle) * distance);
    }
}
