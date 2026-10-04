package com.chronomancy.entity;

import net.minecraft.world.phys.Vec3;

/**
 * Геометрия струи Sands of Time у Rift Maker: шаровой сектор — конус с вершиной в груди босса,
 * ограниченный дальностью. Одна и та же проверка решает, кого песок задевает на сервере, и рисует
 * предупреждающую область на клиенте — то, что игрок видит, совпадает с тем, что его ранит.
 */
public final class SandsCone {
    private SandsCone() {
    }

    /** Попадает ли точка в струю: не дальше {@code range} от вершины и не шире конуса. */
    public static boolean contains(Vec3 origin, Vec3 dir, double x, double y, double z, double range, double coneCos) {
        double tx = x - origin.x, ty = y - origin.y, tz = z - origin.z;
        double dist = Math.sqrt(tx * tx + ty * ty + tz * tz);
        return dist <= range && dist >= 1.0e-3 && (tx * dir.x + ty * dir.y + tz * dir.z) / dist >= coneCos;
    }

    /**
     * След струи на горизонтальной плоскости {@code planeY}: для каждого из {@code samples} направлений
     * вокруг вершины — отрезок расстояний {@code [r0, r1]} от её оси, внутри которого точка плоскости
     * попадает в струю. Конус и шар выпуклы, поэтому на каждом луче это один отрезок.
     *
     * @return массив из {@code samples * 2} чисел: r0 и r1 по очереди; {@code NaN} — луч струю не задевает
     */
    public static float[] footprint(Vec3 origin, Vec3 dir, double range, double coneCos, double planeY, int samples) {
        float[] out = new float[samples * 2];
        double dy = planeY - origin.y;
        double reach = range * range - dy * dy;
        double maxR = reach <= 0.0D ? 0.0D : Math.sqrt(reach);
        final double step = 0.125D;
        for (int k = 0; k < samples; k++) {
            double a = (Math.PI * 2.0D) * k / samples;
            double cx = Math.cos(a), cz = Math.sin(a);
            double first = Double.NaN, last = Double.NaN;
            for (double r = 0.0D; r <= maxR + 1.0e-9; r += step) {
                if (contains(origin, dir, origin.x + cx * r, planeY, origin.z + cz * r, range, coneCos)) {
                    if (Double.isNaN(first)) {
                        first = r;
                    }
                    last = r;
                }
            }
            if (Double.isNaN(first)) {
                out[k * 2] = Float.NaN;
                out[k * 2 + 1] = Float.NaN;
                continue;
            }
            // уточняем обе границы делением пополам: край области получается гладким
            double lo = Math.max(0.0D, first - step), hi = first;
            for (int i = 0; i < 8 && first > 0.0D; i++) {
                double mid = (lo + hi) * 0.5D;
                if (contains(origin, dir, origin.x + cx * mid, planeY, origin.z + cz * mid, range, coneCos)) {
                    hi = mid;
                } else {
                    lo = mid;
                }
            }
            double r0 = first > 0.0D ? hi : 0.0D;
            lo = last;
            hi = Math.min(maxR, last + step);
            for (int i = 0; i < 8; i++) {
                double mid = (lo + hi) * 0.5D;
                if (contains(origin, dir, origin.x + cx * mid, planeY, origin.z + cz * mid, range, coneCos)) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            out[k * 2] = (float) r0;
            out[k * 2 + 1] = (float) lo;
        }
        return out;
    }

    /** Сколько чисел в {@link #footprintMesh} приходится на вершину: x, z и расстояние до края пятна. */
    public static final int MESH_STRIDE = 3;

    /**
     * След струи как сетка четырёхугольников для рендера. У каждой вершины, кроме положения на
     * плоскости (x, z относительно вершины конуса), записано расстояние до ближайшего края пятна в
     * блоках: по нему шейдер зажигает границу, не рисуя её линией.
     *
     * @return подряд идущие вершины, по четыре на четырёхугольник, по {@link #MESH_STRIDE} чисел на вершину
     */
    public static float[] footprintMesh(Vec3 origin, Vec3 dir, double range, double coneCos, double planeY,
                                        int samples, int radialSteps) {
        float[] fp = footprint(origin, dir, range, coneCos, planeY, samples);
        // по лучу: ближняя и дальняя точка пятна; у луча мимо струи — NaN
        float[] cos = new float[samples], sin = new float[samples];
        for (int k = 0; k < samples; k++) {
            double a = (Math.PI * 2.0D) * k / samples;
            cos[k] = (float) Math.cos(a);
            sin[k] = (float) Math.sin(a);
        }
        // --- контур пятна: отрезки дальнего края, ближнего края и боковых «схождений» ---
        float[] seg = new float[samples * 8 + 16];
        int segs = 0;
        int strips = 0;
        for (int k = 0; k < samples; k++) {
            int n = (k + 1) % samples;
            boolean hasA = !Float.isNaN(fp[k * 2]), hasB = !Float.isNaN(fp[n * 2]);
            if (!hasA && !hasB) {
                continue;
            }
            strips++;
            float a0 = hasA ? fp[k * 2] : (fp[n * 2] + fp[n * 2 + 1]) * 0.5F;
            float a1 = hasA ? fp[k * 2 + 1] : a0;
            float b0 = hasB ? fp[n * 2] : (fp[k * 2] + fp[k * 2 + 1]) * 0.5F;
            float b1 = hasB ? fp[n * 2 + 1] : b0;
            segs = segment(seg, segs, cos[k] * a1, sin[k] * a1, cos[n] * b1, sin[n] * b1);
            if (a0 > 0.01F || b0 > 0.01F) {
                segs = segment(seg, segs, cos[k] * a0, sin[k] * a0, cos[n] * b0, sin[n] * b0);
            }
        }
        // --- сама сетка ---
        float[] mesh = new float[strips * radialSteps * 4 * MESH_STRIDE];
        int at = 0;
        for (int k = 0; k < samples; k++) {
            int n = (k + 1) % samples;
            boolean hasA = !Float.isNaN(fp[k * 2]), hasB = !Float.isNaN(fp[n * 2]);
            if (!hasA && !hasB) {
                continue;
            }
            float a0 = hasA ? fp[k * 2] : (fp[n * 2] + fp[n * 2 + 1]) * 0.5F;
            float a1 = hasA ? fp[k * 2 + 1] : a0;
            float b0 = hasB ? fp[n * 2] : (fp[k * 2] + fp[k * 2 + 1]) * 0.5F;
            float b1 = hasB ? fp[n * 2 + 1] : b0;
            for (int s = 0; s < radialSteps; s++) {
                float f0 = s / (float) radialSteps, f1 = (s + 1) / (float) radialSteps;
                float ra0 = a0 + (a1 - a0) * f0, ra1 = a0 + (a1 - a0) * f1;
                float rb0 = b0 + (b1 - b0) * f0, rb1 = b0 + (b1 - b0) * f1;
                at = vertex(mesh, at, cos[k] * ra0, sin[k] * ra0, seg, segs);
                at = vertex(mesh, at, cos[k] * ra1, sin[k] * ra1, seg, segs);
                at = vertex(mesh, at, cos[n] * rb1, sin[n] * rb1, seg, segs);
                at = vertex(mesh, at, cos[n] * rb0, sin[n] * rb0, seg, segs);
            }
        }
        return mesh;
    }

    private static int segment(float[] seg, int count, float x0, float z0, float x1, float z1) {
        int i = count * 4;
        seg[i] = x0;
        seg[i + 1] = z0;
        seg[i + 2] = x1;
        seg[i + 3] = z1;
        return count + 1;
    }

    private static int vertex(float[] mesh, int at, float x, float z, float[] seg, int segs) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < segs; i++) {
            float x0 = seg[i * 4], z0 = seg[i * 4 + 1], dx = seg[i * 4 + 2] - x0, dz = seg[i * 4 + 3] - z0;
            float len = dx * dx + dz * dz;
            float t = len < 1.0e-8F ? 0.0F : Math.max(0.0F, Math.min(1.0F, ((x - x0) * dx + (z - z0) * dz) / len));
            float px = x0 + dx * t - x, pz = z0 + dz * t - z;
            float d = px * px + pz * pz;
            if (d < best) {
                best = d;
            }
        }
        mesh[at] = x;
        mesh[at + 1] = z;
        mesh[at + 2] = segs == 0 ? 0.0F : (float) Math.sqrt(best);
        return at + MESH_STRIDE;
    }
}
