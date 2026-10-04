package com.chronomancy.network;

import com.chronomancy.client.RewindTrailClientState;
import com.chronomancy.client.TemporalStasisClientState;
import com.chronomancy.client.ClientWorldStopState;
import com.chronomancy.client.BorrowedFutureClientState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Сетевой слой синхронизации состояния стазиса на клиент.
 */
public final class ChronoNetwork {

    private ChronoNetwork() {
    }

    /** Вызывается на MOD-шине через {@code RegisterPayloadHandlersEvent}. */
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TemporalNeedlePayload.TYPE, TemporalNeedlePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.chronomancy.client.TemporalNeedleClient.apply(payload)));
        registrar.playToClient(BorrowedFuturePayload.TYPE, BorrowedFuturePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> BorrowedFutureClientState.apply(payload)));

        registrar.playToClient(
                StasisSyncPayload.TYPE,
                StasisSyncPayload.STREAM_CODEC,
                ChronoNetwork::handleClient
        );

        registrar.playToClient(
                RewindTrailPayload.TYPE,
                RewindTrailPayload.STREAM_CODEC,
                ChronoNetwork::handleRewindTrail
        );

        // Time Walk / рывки хрономальных мобов / фазирование.
        registrar.playToClient(
                PhaseStepPayload.TYPE,
                PhaseStepPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.chronomancy.client.PhaseStepClient.apply(payload))
        );

        // Backtrack: короткая обратная нить B->A (те же точки, но клип короче).
        registrar.playToClient(
                BacktrackStreakPayload.TYPE,
                BacktrackStreakPayload.STREAM_CODEC,
                ChronoNetwork::handleBacktrackStreak
        );

        // The World Stop: ЕДИНСТВЕННЫЕ глобальные пакеты на весь мир.
        registrar.playToClient(
                WorldStopStartPayload.TYPE,
                WorldStopStartPayload.STREAM_CODEC,
                ChronoNetwork::handleWorldStopStart
        );

        registrar.playToClient(
                RiftEchoPayload.TYPE,
                RiftEchoPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        com.chronomancy.client.RiftEchoClient.set(payload.entityId(), payload.echo()))
        );

        // Sands of Time: слои песка на цели — клиент красит её в медь.
        registrar.playToClient(SandStacksPayload.TYPE, SandStacksPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.chronomancy.client.SandStacksClient.apply(payload)));

        // Парадокс: кто поражён — клиент рисует на нём «время вразнобой».
        registrar.playToClient(ParadoxSyncPayload.TYPE, ParadoxSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.chronomancy.client.ParadoxClient.apply(payload)));

        registrar.playToClient(
                TimeRiftTrialPayload.TYPE,
                TimeRiftTrialPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientWorldStopState.setTrial(payload.active()))
        );

        registrar.playToClient(
                WorldStopEndPayload.TYPE,
                WorldStopEndPayload.STREAM_CODEC,
                ChronoNetwork::handleWorldStopEnd
        );
    }

    private static void handleWorldStopStart(WorldStopStartPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                {
                    ClientWorldStopState.begin(payload.casterEntityId(), payload.durationTicks());
                    com.chronomancy.client.particle.ChronoParticles.spawnWorldStop(payload.casterEntityId());
                }
        );
    }

    private static void handleWorldStopEnd(WorldStopEndPayload payload, IPayloadContext context) {
        context.enqueueWork(ClientWorldStopState::clear);
    }

    private static void handleClient(StasisSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            boolean was = TemporalStasisClientState.isFrozen(payload.entityId());
            TemporalStasisClientState.setFrozen(payload.entityId(), payload.frozen());
            if (payload.frozen() && !was) {
                com.chronomancy.client.particle.ChronoParticles.spawnStasisFreeze(payload.entityId());
            }
        });
    }

    /** Rewind-траектория: серверный список точек превращаем в клиентский afterimage. */
    private static void handleRewindTrail(RewindTrailPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            java.util.List<Vec3> points = new java.util.ArrayList<>(payload.points().size());
            for (org.joml.Vector3f p : payload.points()) {
                points.add(new Vec3(p.x, p.y, p.z));
            }
            RewindTrailClientState.addTrail(points);
            com.chronomancy.client.VanillaPhase.onRewind(payload.entityId(), points);
        });
    }

    /**
     * Backtrack-нить: тот же afterimage-механизм, но пролёт за {@value #BACKTRACK_STREAK_TICKS}
     * тиков — позиционный откат мгновенный, длинная нить читалась бы как лаг.
     */
    private static void handleBacktrackStreak(BacktrackStreakPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            java.util.List<Vec3> points = new java.util.ArrayList<>(payload.points().size());
            for (org.joml.Vector3f p : payload.points()) {
                points.add(new Vec3(p.x, p.y, p.z));
            }
            RewindTrailClientState.addTrail(points, BACKTRACK_STREAK_TICKS);
            com.chronomancy.client.VanillaPhase.onBacktrack(payload.entityId(), points);
        });
    }

    /** Сервер: визуал «шага сквозь время» всем, кто видит сущность (и ей самой). */
    public static void broadcastPhaseStep(Entity entity, int kind, int ticks, java.util.List<Vec3> points) {
        java.util.List<org.joml.Vector3f> pts = new java.util.ArrayList<>(points.size());
        for (Vec3 p : points) {
            pts.add(new org.joml.Vector3f((float) p.x, (float) p.y, (float) p.z));
        }
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity,
                new PhaseStepPayload(entity.getId(), kind, ticks, pts));
    }

    /** Длительность пролёта обратной нити Backtrack (тиков) ~0.25 с. */
    private static final int BACKTRACK_STREAK_TICKS = 5;

    /** Сервер: сообщаем всем, кто трекитит сущность, что она заморожена / разморожена. */
    public static void sendStasisSync(Entity entity, boolean frozen) {
        if (entity.level().isClientSide) {
            return;
        }

        PacketDistributor.sendToPlayersTrackingEntity(
                entity,
                new StasisSyncPayload(entity.getId(), frozen)
        );
    }

    /** Сервер: точечно отправляем игроку, который только начал трекать уже замороженную сущность. */
    public static void sendStasisSyncToPlayer(ServerPlayer player, int entityId, boolean frozen) {
        PacketDistributor.sendToPlayer(
                player,
                new StasisSyncPayload(entityId, frozen)
        );
    }

    // =========================================================
    // THE WORLD STOP — глобальное состояние (один пакет на всех)
    // =========================================================

    /** Сервер: весь мир остановлен — сообщаем КАЖДОМУ клиенту одно глобальное состояние. */
    public static void broadcastWorldStopStart(int casterEntityId, int durationTicks) {
        PacketDistributor.sendToAllPlayers(
                new WorldStopStartPayload(casterEntityId, durationTicks)
        );
    }

    /** Сервер: время возобновлено. */
    public static void broadcastWorldStopEnd() {
        PacketDistributor.sendToAllPlayers(new WorldStopEndPayload());
    }

    /** Сервер: игрок зашёл/синхронизируется во время активного стопа — догоняет состояние. */
    /** Медная копия Rift: всем, кто видит сущность (и самому игроку, если это он). */
    public static void broadcastRiftEcho(Entity entity, boolean echo) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, new RiftEchoPayload(entity.getId(), echo));
    }

    public static void sendRiftEchoToPlayer(ServerPlayer player, Entity entity, boolean echo) {
        PacketDistributor.sendToPlayer(player, new RiftEchoPayload(entity.getId(), echo));
    }

    /** Испытание Time Rift началось/закончилось (глобально, как и сам стоп). */
    public static void broadcastRiftTrial(boolean active) {
        PacketDistributor.sendToAllPlayers(new TimeRiftTrialPayload(active));
    }

    public static void sendRiftTrialToPlayer(ServerPlayer player, boolean active) {
        PacketDistributor.sendToPlayer(player, new TimeRiftTrialPayload(active));
    }

    public static void sendWorldStopStartToPlayer(ServerPlayer player, int casterEntityId, int durationTicks) {
        PacketDistributor.sendToPlayer(
                player,
                new WorldStopStartPayload(casterEntityId, durationTicks)
        );
    }
}
