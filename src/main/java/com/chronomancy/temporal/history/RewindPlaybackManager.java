package com.chronomancy.temporal.history;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Серверная «перемотка назад» игрока вдоль его собственной записанной
 * траектории: вместо мгновенного скачка телепорта позиция каждый тик
 * передвигается назад по полилинии (current -&gt; ... -&gt; старая точка).
 *
 * <p>Движение авторитетно сервером ({@code connection.teleport} — штатный
 * путь перемещения игрока раз в тик), поэтому мультиплеер не «резиночит»,
 * а сам клиент видит, как его персонажа реально откатывает назад по пути.
 *
 * <p>Система полностью изолирована от Stasis/Dilation: только свой
 * ServerTickEvent.Post-хэндлер, никаких миксинов. Гибель/выход игрока
 * во время перемотки — проигрывание молча отменяется.
 *
 * <p>Длительность ({@value #PLAYBACK_TICKS} тиков) синхронизирована с длиной
 * клиентской анимации золотого afterimage (RewindTrailClientState.ANIM_TICKS),
 * чтобы «призрак» частиц бежал ровно в ногу с телом.
 */
public final class RewindPlaybackManager {

    /** Длительность перемотки в тиках; совпадает с ANIM_TICKS на клиенте. */
    public static final int PLAYBACK_TICKS = 25;

    private RewindPlaybackManager() {
    }

    private static final Map<UUID, Playback> ACTIVE = new HashMap<>();

    public static boolean isPlaying(UUID uuid) {
        return ACTIVE.containsKey(uuid);
    }

    public static void cancel(UUID uuid) {
        ACTIVE.remove(uuid);
    }

    /**
     * Запускает перемотку. {@code waypoints} — точки пути в ПОРЯДКЕ ПРОХОЖДЕНИЯ
     * (от текущей позиции к старой), терминальная точка — финальная (безопасная)
     * позиция; в неё же в конце применяются повороты и скорость снимка.
     */
    public static void start(ServerPlayer player, List<Vec3> waypoints,
                             float endYaw, float endPitch, Vec3 endVelocity,
                             Runnable onArrive) {
        start(player, waypoints, endYaw, endPitch, endVelocity, PLAYBACK_TICKS, onArrive);
    }

    /** То же, но с произвольной длительностью пролёта (Time Walk — короткий рывок вперёд). */
    public static void start(ServerPlayer player, List<Vec3> waypoints,
                             float endYaw, float endPitch, Vec3 endVelocity, int ticks,
                             Runnable onArrive) {
        if (waypoints.size() < 2) {
            return;
        }
        ACTIVE.put(player.getUUID(), new Playback(player, waypoints, endYaw, endPitch, endVelocity,
                Math.max(1, ticks), onArrive));
    }

    /** Шаг всех активных перемоток. Один снимок позиции = один тик. */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Playback>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Playback playback = it.next().getValue();
            ServerPlayer player = playback.player;
            if (player.isRemoved() || !player.isAlive()) {
                it.remove(); // игрок умер/вышел — перемотка молча отменена
                continue;
            }
            if (playback.tick(player)) {
                it.remove();
            }
        }
    }

    // =========================================================
    // СОСТОЯНИЕ ОДНОЙ ПЕРЕМОТКИ
    // =========================================================

    private static final class Playback {
        private final ServerPlayer player;
        private final Vec3[] points;
        private final double[] cumulative; // кумулятивная длина до точки i
        private final double total;
        private final double step;         // дистанция за тик
        private final float startYaw;
        private final float startPitch;
        private final float endYaw;
        private final float endPitch;
        private final Vec3 endVelocity;
        private final Runnable onArrive;
        private final int totalTicks;
        private double cursor;
        private int tickCount;

        Playback(ServerPlayer player, List<Vec3> waypoints,
                 float endYaw, float endPitch, Vec3 endVelocity, int ticks, Runnable onArrive) {
            this.player = player;
            this.startYaw = player.getYRot();
            this.startPitch = player.getXRot();
            // Убираем подряд идущие дубли — нулевые сегменты дают рывок камеры
            List<Vec3> cleaned = new ArrayList<>(waypoints.size());
            for (Vec3 p : waypoints) {
                if (cleaned.isEmpty() || cleaned.get(cleaned.size() - 1).distanceToSqr(p) > 1.0E-4) {
                    cleaned.add(p);
                }
            }
            if (cleaned.size() < 2) {
                cleaned = waypoints; // страховка: путь почти нулевой
            }
            this.points = cleaned.toArray(new Vec3[0]);
            this.cumulative = new double[this.points.length];
            double acc = 0;
            for (int i = 1; i < this.points.length; i++) {
                acc += this.points[i - 1].distanceTo(this.points[i]);
                cumulative[i] = acc;
            }
            this.total = acc;
            this.totalTicks = ticks;
            this.step = total / totalTicks;
            this.endYaw = endYaw;
            this.endPitch = endPitch;
            this.endVelocity = endVelocity;
            this.onArrive = onArrive;
        }

        /** @return true — перемотка завершена (запись удаляется из карты). */
        boolean tick(ServerPlayer player) {
            tickCount++;
            cursor += step;

            if (cursor >= total || tickCount >= totalTicks) {
                finish(player);
                return true;
            }

            Vec3 pos = pointAt(cursor);
            float t = tickCount / (float) totalTicks;
            // Повороты плавно перетекают из текущих в снимковые к концу перемотки.
            float yaw = Mth.rotLerp(t, startYaw, endYaw);
            float pitch = Mth.lerp(t, startPitch, endPitch);

            // Во время перемотки физика игрока не управляет телом:
            // скорость обнулена, падение не накапливается.
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            // Авторитетно и на сервере, и на клиенте (ServerPlayer.teleportTo),
            // иначе сервер «думает», что игрок остался, и резинит обратно.
            player.teleportTo((ServerLevel) player.level(), pos.x, pos.y, pos.z, yaw, pitch);
            return false;
        }

        private void finish(ServerPlayer player) {
            Vec3 last = points[points.length - 1];
            player.teleportTo((ServerLevel) player.level(), last.x, last.y, last.z, endYaw, endPitch);
            player.setDeltaMovement(endVelocity);
            if (onArrive != null) {
                onArrive.run();
            }
        }

        /** Точка на полилинии по пройденной дистанции. */
        private Vec3 pointAt(double dist) {
            for (int i = 1; i < points.length; i++) {
                if (dist <= cumulative[i]) {
                    double segLen = cumulative[i] - cumulative[i - 1];
                    double t = segLen <= 1.0E-6 ? 0.0 : (dist - cumulative[i - 1]) / segLen;
                    return points[i - 1].lerp(points[i], t);
                }
            }
            return points[points.length - 1];
        }
    }
}
