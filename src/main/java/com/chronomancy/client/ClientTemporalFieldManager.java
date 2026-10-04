package com.chronomancy.client;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Клиентский реестр активных Time Dilation Fields для volume post-process.
 *
 * <p>Шейдер НЕ привязан к Spell/Entity напрямую: менеджер каждый кадр собирает
 * синхронизированные ванильным entity-трекингом поля ({@code center/radius/rate}
 * читаются прямо из {@code TimeDilationFieldEntity}) и приводит их к единому
 * camera-relative виду, который понимает шейдер. Так будущие spells смогут
 * переиспользовать тот же volume renderer.
 *
 * <p>Экранная проекция сфер берётся из РЕАЛЬНОГО кадра уровня на
 * {@link RenderLevelStageEvent.Stage#AFTER_LEVEL} (там NeoForge отдаёт ровно те
 * {@code projection}/{@code modelView} матрицы, которыми рисился мир, включая
 * точные near/far/fov). В отличие от прежней depth-реконструкции маска на CPU
 * НЕ зависит от содержимого текстуры глубины, поэтому наружный вид гарантированно
 * совпадает с внутренним. Сам пост-эффект применяется позже (в районе
 * {@code doEntityOutline}), чтобы grades попадали и по руке.
 *
 * <p>{@code MAX_TEMPORAL_FIELDS} — осознанный лимит первой версии (ТЗ допускает
 * ограниченное число). При перегрузке выбираются поля, БЛИЖАЙШИЕ к камере.
 */
public final class ClientTemporalFieldManager {

    /** Полей за кадр поддерживается (по одному mat4-униформе на поле в шейдере). */
    public static final int MAX_TEMPORAL_FIELDS = 4;

    /** Радиус сканирования списка сущностей клиента (покрывает view-дистанцию трекинга). */
    private static final double SCAN_RADIUS = 192.0;

    // --- Снятые на AFTER_LEVEL матрицы текущего кадра уровня ---
    private static Matrix4f capturedProjection;
    private static Matrix4f capturedViewRotation;
    private static Vec3 cameraPos;
    private static boolean matricesValid;

    // --- Собранные за кадр поля, уже camera-relative ---
    private static final double[] REL_X = new double[MAX_TEMPORAL_FIELDS];
    private static final double[] REL_Y = new double[MAX_TEMPORAL_FIELDS];
    private static final double[] REL_Z = new double[MAX_TEMPORAL_FIELDS];
    private static final double[] RADIUS = new double[MAX_TEMPORAL_FIELDS];
    private static final float[] SLOW = new float[MAX_TEMPORAL_FIELDS];
    private static int fieldCount;
    private static boolean cameraInside;
    private static float dominantSlow;

    private ClientTemporalFieldManager() {
    }

    /** Подписывается на игровую шину (только клиент). Запоминает матрицы кадра уровня. */
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        Camera camera = event.getCamera();
        if (!camera.isInitialized()) {
            matricesValid = false;
            return;
        }
        // Копируем — оригиналы переиспользуются ванилью на следующий кадр.
        capturedProjection = new Matrix4f(event.getProjectionMatrix());
        capturedViewRotation = new Matrix4f(event.getModelViewMatrix());
        cameraPos = camera.getPosition();
        matricesValid = true;
    }

    /** Собирает поля текущего клиента. Вызывается каждый кадр перед применением эффекта. */
    public static void collect(ClientLevel level) {
        fieldCount = 0;
        cameraInside = false;
        dominantSlow = 0.0F;
        if (level == null || !matricesValid || cameraPos == null) {
            return;
        }

        // Собираем кандидатов, затем оставляем MAX_TEMPORAL_FIELDS ближайших к камере.
        double[] cx = new double[MAX_TEMPORAL_FIELDS];
        double[] cy = new double[MAX_TEMPORAL_FIELDS];
        double[] cz = new double[MAX_TEMPORAL_FIELDS];
        double[] rr = new double[MAX_TEMPORAL_FIELDS];
        float[] ss = new float[MAX_TEMPORAL_FIELDS];
        double[] key = new double[MAX_TEMPORAL_FIELDS]; // dist^2 центра до камеры
        int count = 0;

        // Полный список трекащихся полей вокруг камеры (НЕ entitiesForRendering —
        // тот отсечён frustum-куллингом, а поле может быть видно лишь частично или
        // камера стоит ВНУТРИ него и центр ушёл за край экрана).
        AABB scan = new AABB(cameraPos.x, cameraPos.y, cameraPos.z,
                cameraPos.x, cameraPos.y, cameraPos.z).inflate(SCAN_RADIUS);
        for (Entity entity : level.getEntities((Entity) null, scan,
                e -> e instanceof com.chronomancy.entity.TimeDilationFieldEntity)) {
            if (!(entity instanceof com.chronomancy.entity.TimeDilationFieldEntity field)) {
                continue;
            }
            if (field.isAccelerating()) {
                continue; // зону ускорения рисует свой пост-эффект (AccelZonePostProcess)
            }
            double radius = field.getRadius();
            if (radius <= 0.0) {
                continue;
            }
            Vec3 center = field.getRenderCenter();
            if (center == null) {
                center = field.position();
            }
            double rate = field.getTemporalRate();
            float slow = (float) clamp01(1.0 - rate);
            double dx = center.x - cameraPos.x;
            double dy = center.y - cameraPos.y;
            double dz = center.z - cameraPos.z;
            double distSqr = dx * dx + dy * dy + dz * dz;

            if (count < MAX_TEMPORAL_FIELDS) {
                int at = count++;
                cx[at] = center.x; cy[at] = center.y; cz[at] = center.z;
                rr[at] = radius; ss[at] = slow; key[at] = distSqr;
            } else {
                // Заменяем самое ДАЛЬНЕЕ из текущих, если это поле ближе.
                int far = 0;
                for (int i = 1; i < MAX_TEMPORAL_FIELDS; i++) {
                    if (key[i] > key[far]) {
                        far = i;
                    }
                }
                if (distSqr < key[far]) {
                    cx[far] = center.x; cy[far] = center.y; cz[far] = center.z;
                    rr[far] = radius; ss[far] = slow; key[far] = distSqr;
                }
            }
        }

        for (int i = 0; i < count; i++) {
            REL_X[i] = cx[i] - cameraPos.x;
            REL_Y[i] = cy[i] - cameraPos.y;
            REL_Z[i] = cz[i] - cameraPos.z;
            RADIUS[i] = rr[i];
            SLOW[i] = ss[i];
            // Камера внутри этого поля?
            double insideDist = Math.sqrt(key[i]);
            if (insideDist <= rr[i] && !cameraInside) {
                cameraInside = true;
            }
        }
        fieldCount = count;

        if (cameraInside) {
            for (int i = 0; i < count; i++) {
                if (Math.sqrt(key[i]) <= RADIUS[i] && SLOW[i] > dominantSlow) {
                    dominantSlow = SLOW[i];
                }
            }
        }
    }

    // =========================== getters для шейдера ===========================

    public static int fieldCount() {
        return fieldCount;
    }

    public static double relX(int i) {
        return REL_X[i];
    }

    public static double relY(int i) {
        return REL_Y[i];
    }

    public static double relZ(int i) {
        return REL_Z[i];
    }

    public static double radius(int i) {
        return RADIUS[i];
    }

    public static float slow(int i) {
        return SLOW[i];
    }

    public static boolean isCameraInside() {
        return cameraInside;
    }

    public static float dominantSlow() {
        return dominantSlow;
    }

    public static boolean hasMatrices() {
        return matricesValid && fieldCount > 0;
    }

    /** Матрицы кадра уровня сняты (независимо от того, есть ли поля) — для соседних объёмных эффектов. */
    public static boolean matricesReady() {
        return matricesValid && cameraPos != null && capturedProjection != null && capturedViewRotation != null;
    }

    /** Позиция камеры снятого кадра. */
    public static Vec3 cameraPos() {
        return cameraPos;
    }

    /** proj * viewRot снятого кадра: проецирует точку ОТНОСИТЕЛЬНО камеры в clip-пространство. */
    public static Matrix4f viewProj() {
        return new Matrix4f(capturedProjection).mul(capturedViewRotation);
    }

    /**
     * inverse(proj * viewRot) — обе сняты с реального кадра уровня. Шейдеру он
     * нужен ТОЛЬКО чтобы получить НАПРАВЛЕНИЕ луча (NDC far-plane -> camera-relative
     * world dir). Текстура глубины НЕ используется — она в ванильном PostChain
     * ненадёжна. Направление + ray-sphere дают форму полусферы без depth.
     */
    public static Matrix4f invViewProj() {
        Matrix4f viewProj = new Matrix4f(capturedProjection).mul(capturedViewRotation);
        return viewProj.invert(new Matrix4f());
    }

    /** Полный сброс при выходе из мира — не держим ссылки на старый кадр/сущности. */
    public static void clear() {
        capturedProjection = null;
        capturedViewRotation = null;
        cameraPos = null;
        matricesValid = false;
        fieldCount = 0;
        cameraInside = false;
        dominantSlow = 0.0F;
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }
}
