package com.chronomancy.client;

import com.chronomancy.temporal.worldstop.WorldStopExempt;
import net.minecraft.world.entity.Entity;

/**
 * Клиентское зеркало ЕДИНСТВЕННОГО глобального состояния World Stop.
 *
 * <p>Сервер шлёт два пакета ({@code WorldStopStartPayload} /
 * {@code WorldStopEndPayload}) — никаких per-entity пакетов: мир заморожен
 * глобально, клиенту нужен только факт стопа и entity id кастера
 * (единственного, кому разрешено тикать/двигаться).
 *
 * <p>Пишется из сетевого потока через {@code context.enqueueWork}
 * (см. {@code ChronoNetwork}), читается клиентскими обработчиками
 * {@code EntityTickEvent.Pre} и Phase 5/6 клиент-миксинами.
 */
public final class ClientWorldStopState {

    private static volatile boolean active;
    private static volatile int casterEntityId = -1;
    private static volatile int durationTicks;
    /** Идёт испытание Time Rift — снаряды кастера не замораживаются. */
    private static volatile boolean trial;

    private ClientWorldStopState() {
    }

    public static void begin(int casterEntityId, int durationTicks) {
        active = true;
        ClientWorldStopState.casterEntityId = casterEntityId;
        ClientWorldStopState.durationTicks = durationTicks;
    }

    public static void setTrial(boolean value) {
        trial = value;
    }

    public static void clear() {
        active = false;
        trial = false;
        casterEntityId = -1;
        durationTicks = 0;
    }

    public static boolean isActive() {
        return active;
    }

    public static int getCasterEntityId() {
        return casterEntityId;
    }

    public static int getDurationTicks() {
        return durationTicks;
    }

    /**
     * Должна ли сущность стоять на месте прямо сейчас: стоп активен, это не кастер и не
     * сущность вне времени ({@link WorldStopExempt}: Chronomaly, Time Rift).
     */
    public static boolean isFrozen(Entity entity) {
        return active
                && entity != null
                && entity.getId() != casterEntityId
                && !WorldStopExempt.is(entity)
                && !(trial && entity instanceof net.minecraft.world.entity.projectile.Projectile projectile
                        && projectile.getOwner() != null && projectile.getOwner().getId() == casterEntityId)
                // струя Sands of Time кастера течёт и в остановленном времени (как на сервере)
                && !(entity instanceof com.chronomancy.entity.SandsOfTimeProjectile cone
                        && cone.getOwner() != null && cone.getOwner().getId() == casterEntityId);
    }

    // =========================================================
    // Чистка застывших частиц
    // =========================================================

    /** Раз в столько клиентских тиков остановки застывшие частицы убираются (30 секунд). */
    public static final int PARTICLE_PURGE_INTERVAL = 600;
    private static int stopTicks;
    private static boolean purgingParticles;

    /**
     * Клиентский тик: считает, сколько длится остановка, и раз в {@value #PARTICLE_PURGE_INTERVAL}
     * тиков на один тик включает чистку. В долгой остановке (испытание разлома) дым, искры и следы
     * заклинаний копятся в воздухе и закрывают обзор — {@code WorldStopParticleFreezeMixin} в этот
     * тик не замораживает такие частицы, а гасит.
     */
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) {
        if (!active) {
            stopTicks = 0;
            purgingParticles = false;
            return;
        }
        stopTicks++;
        purgingParticles = stopTicks % PARTICLE_PURGE_INTERVAL == 0;
    }

    /**
     * Частица застыла вместе с миром: не тикает и рисуется в одной и той же точке тика. Цветная пыль и
     * собственные темпоральные частицы мода живут и под остановкой.
     */
    public static boolean holdsParticle(net.minecraft.client.particle.Particle particle) {
        return active
                && !(particle instanceof net.minecraft.client.particle.DustParticleBase)
                && !(particle instanceof com.chronomancy.client.particle.ChronoTemporalParticle);
    }

    /** В этот тик застывшие частицы нужно убрать, а не заморозить. */
    public static boolean purgingParticles() {
        return active && purgingParticles;
    }
}
