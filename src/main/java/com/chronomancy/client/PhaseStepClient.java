package com.chronomancy.client;

import com.chronomancy.network.PhaseStepPayload;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Клиент: визуал «шага сквозь время» ({@link PhaseStepPayload}). */
public final class PhaseStepClient {

    private PhaseStepClient() {
    }

    public static void apply(PhaseStepPayload payload) {
        List<Vec3> points = new ArrayList<>(payload.points().size());
        for (org.joml.Vector3f p : payload.points()) {
            points.add(new Vec3(p.x, p.y, p.z));
        }
        switch (payload.kind()) {
            case PhaseStepPayload.TIME_WALK -> {
                if (points.size() >= 2) {
                    RewindTrailClientState.addTrail(points, Math.max(2, payload.ticks()));
                }
                VanillaPhase.onTimeWalk(payload.entityId(), points, payload.ticks());
            }
            case PhaseStepPayload.BLINK -> VanillaPhase.onBlink(payload.entityId(), points);
            case PhaseStepPayload.PHASED -> VanillaPhase.onPhased(payload.entityId(), payload.ticks());
            default -> {
            }
        }
    }
}
