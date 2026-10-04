package com.chronomancy.client;

import com.chronomancy.client.particle.ChronoParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Клиентский шаг траекторий Rewind: продвигает маркеры
 * {@link RewindTrailClientState} от текущей позиции игрока к старой и порождает
 * вдоль них направленные temporal-streak'ы ({@link ChronoParticles#spawnRewindTrail}).
 * Когда маркер доходит — короткое схлопывание частиц в точку назначения.
 * Никаких vanilla GOLD_DUST-крупинок: Rewind обязан читаться как направленное
 * «утекание назад», а не как облачко стазиса.
 *
 * <p>Вызывается из {@link ChronoParticles#onClientTick} СРАЗУ после сброса
 * бюджета спавна и ДО стазис-тикера — иначе стазис, съев {@code MAX_SPAWNS_PER_TICK},
 * голодал бы rewind-частицы (общий счётчик на один тик).
 */
public final class RewindTrailHandler {

    private RewindTrailHandler() {
    }

    /** Один клиентский тик: маркеры → streak'ы, прибытия → схлопывание. */
    public static void tick(ClientLevel level) {
        for (RewindTrailClientState.Marker marker : RewindTrailClientState.tickAndGetMarkers()) {
            ChronoParticles.spawnRewindTrail(level, marker.pos(), marker.forward());
        }
        for (Vec3 dest : RewindTrailClientState.drainArrivals()) {
            ChronoParticles.spawnRewindImplode(level, dest);
        }
    }
}
