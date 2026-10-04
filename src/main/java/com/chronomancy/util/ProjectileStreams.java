package com.chronomancy.util;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Струи заклинаний-снарядов. Рисунок складывается из ПОЛОЖЕНИЯ огоньков вдоль пройденного отрезка
 * (сами огоньки — точки без направления), фаза узора привязана к пройденному расстоянию, поэтому
 * спираль/зигзаг/волна непрерывны от тика к тику при любой скорости снаряда:
 * <ul>
 *   <li>{@link #spiral} — Time-Piercing Needle: тонкая спираль;</li>
 *   <li>{@link #massive} — Time Dilation Field: массивная плотная струя;</li>
 *   <li>{@link #zigzag} — Backtrack: зигзаг;</li>
 *   <li>{@link #wave} — Temporal Stasis: волна.</li>
 * </ul>
 * Общий код (без клиентских классов): вызывающий передаёт, куда класть частицы.
 */
public final class ProjectileStreams {

    @FunctionalInterface
    public interface Sink {
        void put(ParticleOptions type, double x, double y, double z, double vx, double vy, double vz);
    }

    private ProjectileStreams() {
    }

    /** Два единичных вектора, перпендикулярных направлению полёта. */
    private static Vec3[] basis(Vec3 dir) {
        Vec3 up = Math.abs(dir.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = dir.cross(up).normalize();
        Vec3 v = dir.cross(u).normalize();
        return new Vec3[]{u, v};
    }

    private static int count(double length, double perBlock, int max) {
        return Mth.clamp((int) Math.ceil(length * perBlock), 1, max);
    }

    /**
     * Тонкая спираль вокруг оси полёта.
     *
     * @param travel пройденное снарядом расстояние в точке {@code from}
     * @return пройденное расстояние в точке {@code to}
     */
    public static double spiral(Sink sink, ParticleOptions type, Vec3 from, Vec3 to, double travel,
                                double radius, double turnsPerBlock, double perBlock, int max) {
        Vec3 seg = to.subtract(from);
        double len = seg.length();
        if (len < 1.0e-4) return travel;
        Vec3 dir = seg.scale(1.0 / len);
        Vec3[] b = basis(dir);
        int n = count(len, perBlock, max);
        for (int i = 0; i < n; i++) {
            double t = (i + 1) / (double) n;
            double s = travel + len * t;
            double a = s * turnsPerBlock * Mth.TWO_PI;
            Vec3 off = b[0].scale(Math.cos(a) * radius).add(b[1].scale(Math.sin(a) * radius));
            Vec3 p = from.add(seg.scale(t)).add(off);
            // чуть «раскручивается» наружу, но без собственного направления
            sink.put(type, p.x, p.y, p.z, off.x * 0.02, off.y * 0.02, off.z * 0.02);
        }
        return travel + len;
    }

    /** Зигзаг в плоскости, перпендикулярной полёту (треугольная волна). */
    public static double zigzag(Sink sink, ParticleOptions type, Vec3 from, Vec3 to, double travel,
                                double amplitude, double period, double perBlock, int max) {
        Vec3 seg = to.subtract(from);
        double len = seg.length();
        if (len < 1.0e-4) return travel;
        Vec3 dir = seg.scale(1.0 / len);
        Vec3 u = basis(dir)[0];
        int n = count(len, perBlock, max);
        for (int i = 0; i < n; i++) {
            double t = (i + 1) / (double) n;
            double s = travel + len * t;
            double phase = s / period;
            double tri = 4.0 * Math.abs(phase - Math.floor(phase) - 0.5) - 1.0; // -1..1, острые изломы
            Vec3 p = from.add(seg.scale(t)).add(u.scale(tri * amplitude));
            sink.put(type, p.x, p.y, p.z, 0.0, 0.0, 0.0);
        }
        return travel + len;
    }

    /** Плавная волна (синусоида) в плоскости, перпендикулярной полёту. */
    public static double wave(Sink sink, ParticleOptions type, Vec3 from, Vec3 to, double travel,
                              double amplitude, double wavelength, double perBlock, int max) {
        Vec3 seg = to.subtract(from);
        double len = seg.length();
        if (len < 1.0e-4) return travel;
        Vec3 dir = seg.scale(1.0 / len);
        Vec3 u = basis(dir)[0];
        int n = count(len, perBlock, max);
        for (int i = 0; i < n; i++) {
            double t = (i + 1) / (double) n;
            double s = travel + len * t;
            double w = Math.sin(s / wavelength * Mth.TWO_PI);
            Vec3 p = from.add(seg.scale(t)).add(u.scale(w * amplitude));
            sink.put(type, p.x, p.y, p.z, 0.0, 0.002, 0.0);
        }
        return travel + len;
    }

    /** Массивная струя: плотный «столб» сгустков в круге радиуса {@code radius} вокруг оси. */
    public static double massive(Sink sink, ParticleOptions core, ParticleOptions fine, Vec3 from, Vec3 to,
                                 double travel, double radius, double perBlock, int max, RandomSource random) {
        Vec3 seg = to.subtract(from);
        double len = seg.length();
        Vec3 dir = len < 1.0e-4 ? new Vec3(0, 1, 0) : seg.scale(1.0 / len);
        Vec3[] b = basis(dir);
        int n = count(Math.max(len, 0.25), perBlock, max);
        for (int i = 0; i < n; i++) {
            double t = random.nextDouble();
            double a = random.nextDouble() * Mth.TWO_PI;
            double r = radius * Math.sqrt(random.nextDouble());
            Vec3 off = b[0].scale(Math.cos(a) * r).add(b[1].scale(Math.sin(a) * r));
            Vec3 p = from.add(seg.scale(t)).add(off);
            // медленно расплывается от оси — струя «густеет» позади снаряда
            double k = 0.012 + random.nextDouble() * 0.01;
            Vec3 drift = r > 1.0e-4 ? off.scale(k / r) : Vec3.ZERO;
            sink.put(i % 3 == 2 ? fine : core, p.x, p.y, p.z, drift.x, drift.y, drift.z);
        }
        return travel + len;
    }
}
