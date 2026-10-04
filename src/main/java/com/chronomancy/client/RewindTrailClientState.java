package com.chronomancy.client;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Клиентское состояние траекторий Rewind: активные «нитки» afterimage.
 *
 * <p>Хранит только сэмплированные точки, полученные от сервера
 * (см. {@link com.chronomancy.network.RewindTrailPayload}) — полный
 * history-буфер на клиент никогда не попадает.
 */
public final class RewindTrailClientState {

    /**
     * Сколько тиков длится пролёт частиц по всей траектории. Синхронизировано
     * с серверной перемоткой тела: RewindPlaybackManager.PLAYBACK_TICKS.
     */
    private static final int ANIM_TICKS = 25;
    /** Сколько маркеров за тик выдаём на один трейл (спавн решает клиент-хаб). */
    private static final int SPAWNS_PER_TICK = 3;

    private RewindTrailClientState() {
    }

    private static final List<Trail> TRAILS = new ArrayList<>();
    /** Точки назначения завершённых в этом тике трейлов (схлопывание). */
    private static final List<Vec3> ARRIVALS = new ArrayList<>();

    /** Маркер: позиция на полилинии + направление движения к точке отката. */
    public record Marker(Vec3 pos, Vec3 forward) {
    }

    /** Одна траектория: точки упорядочены ОТ текущей позиции К старой. */
    private static final class Trail {
        final List<Vec3> points;
        final double[] segEnd;   // кумулятивная длина по сегментам
        final double total;
        final double speed;      // длина за тик
        double cursor;           // пройденная от текущей позиции дистанция

        Trail(List<Vec3> points, int animTicks) {
            this.points = points;
            this.segEnd = new double[points.size()];
            double acc = 0;
            for (int i = 1; i < points.size(); i++) {
                acc += points.get(i - 1).distanceTo(points.get(i));
                segEnd[i] = acc;
            }
            this.total = acc;
            this.speed = total / Math.max(1, animTicks);
        }

        /** Точка на полилинии по пройденной дистанции. */
        Vec3 pointAt(double dist) {
            if (points.size() == 1) {
                return points.get(0);
            }
            for (int i = 1; i < points.size(); i++) {
                if (dist <= segEnd[i]) {
                    double segStart = segEnd[i - 1];
                    double segLen = segEnd[i] - segStart;
                    double t = segLen <= 1.0e-6 ? 0.0 : (dist - segStart) / segLen;
                    return points.get(i - 1).lerp(points.get(i), t);
                }
            }
            return points.get(points.size() - 1);
        }

        boolean finished() {
            return cursor >= total;
        }
    }

    /** Вызывается с сетевого треда через enqueueWork — синхронизация не нужна. */
    public static void addTrail(List<Vec3> orderedPoints) {
        addTrail(orderedPoints, ANIM_TICKS);
    }

    /**
     * То же, но с управляемой длительностью пролёта. Backtrack использует
     * короткий reverse-streak (3–6 тиков) — позиция откатывается МГНОВЕННО,
     * длинная нить Rewind здесь читалась бы как лаг.
     */
    public static void addTrail(List<Vec3> orderedPoints, int animTicks) {
        if (orderedPoints == null || orderedPoints.size() < 2) {
            return;
        }
        TRAILS.add(new Trail(List.copyOf(orderedPoints), animTicks));
    }

    /**
     * Шаг анимации: маркер движется от текущей позиции к старой. Возвращает
     * маркеры для спавна трейл-частиц (позиция + направление движения);
     * завершённые трейлы откладывают точку назначения в {@link #drainArrivals()}.
     */
    public static List<Marker> tickAndGetMarkers() {
        List<Marker> out = new ArrayList<>();
        Iterator<Trail> it = TRAILS.iterator();
        while (it.hasNext()) {
            Trail trail = it.next();
            for (int i = 0; i < SPAWNS_PER_TICK; i++) {
                trail.cursor += trail.speed / SPAWNS_PER_TICK;
                if (trail.finished()) {
                    break;
                }
                Vec3 pos = trail.pointAt(trail.cursor);
                // направление: следующий шаг маркера (обрезан до сегмента)
                Vec3 ahead = trail.pointAt(Math.min(trail.cursor + trail.speed, trail.total));
                Vec3 dir = ahead.subtract(pos);
                if (dir.lengthSqr() < 1.0e-8) {
                    dir = new Vec3(0.0, 0.04, 0.0);
                }
                out.add(new Marker(pos, dir));
            }
            if (trail.finished()) {
                ARRIVALS.add(trail.points.get(trail.points.size() - 1));
                it.remove();
            }
        }
        return out;
    }

    /** Забрать и очистить точки назначений, завершившиеся с прошлого тика. */
    public static List<Vec3> drainArrivals() {
        if (ARRIVALS.isEmpty()) {
            return List.of();
        }
        List<Vec3> out = new ArrayList<>(ARRIVALS);
        ARRIVALS.clear();
        return out;
    }

    /** Сброс при выходе из мира (чтобы траектории не «текли» в новое лобби). */
    public static void clear() {
        TRAILS.clear();
        ARRIVALS.clear();
    }
}
