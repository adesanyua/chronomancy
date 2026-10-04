package com.chronomancy.client;

import com.chronomancy.client.entity.TimePhaseRendering;
import com.chronomancy.entity.TimeDilationFieldEntity;
import com.chronomancy.temporal.TemporalRate;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Облик Accelerated Zone — противоположность куполу поля замедления (см. {@link TimeDilationFieldRenderer}).
 *
 * <p>Поле: золотая плёнка на блоках, плавный переливающийся пузырь, неторопливые кольца рун.
 * Зона: голубая плёнка на блоках, КУБ со светящимися рёбрами, по граням вверх несутся росчерки
 * (шейдер {@code accel_zone}), а по рёбрам и граням бегут быстрые штрихи. Чем сильнее ускорение,
 * тем быстрее всё движется.
 */
final class AcceleratedZoneRendering {
    private static final ResourceLocation WHITE =
            ResourceLocation.fromNamespaceAndPath("chronomancy", "textures/stasis_white.png");

    private static final int C_DEEP = 0x2E7BEF;
    private static final int C_BLUE = 0x58B8FF;
    private static final int C_SKY = 0xA8DCFF;
    private static final int C_WHITE = 0xE9F7FF;
    private static final int FILM_RGB = 0x58C8FF;
    private static final int FILM_ALPHA = 58;
    private static final double FILM_INFLATE = 0.0025;
    private static final int MAX_FILM_BLOCKS = 700;

    private AcceleratedZoneRendering() {
    }

    static void render(TimeDilationFieldEntity entity, PoseStack poseStack, MultiBufferSource bufferSource,
                       Vec3 center, double half, float partialTick) {
        renderBlockFilm(entity.level(), poseStack, bufferSource, center, half);
        renderEdges(entity, poseStack, bufferSource, half, partialTick);
        renderFaces(entity, poseStack, bufferSource, half);
    }

    // =========================================================
    // Плёнка на блоках внутри куба
    // =========================================================

    private static void renderBlockFilm(Level level, PoseStack poseStack, MultiBufferSource bufferSource,
                                        Vec3 center, double half) {
        VertexConsumer consumer = bufferSource.getBuffer(TimeDilationFieldRenderer.filmType());
        int minX = (int) Math.floor(center.x - half), maxX = (int) Math.ceil(center.x + half);
        int minY = (int) Math.floor(center.y - half), maxY = (int) Math.ceil(center.y + half);
        int minZ = (int) Math.floor(center.z - half), maxZ = (int) Math.ceil(center.z + half);
        int drawn = 0;
        // сверху вниз: при переполнении бюджета закрашенной остаётся видимая поверхность, а не дно
        for (int y = maxY; y >= minY; y--) {
            if (Math.abs(y + 0.5 - center.y) > half) {
                continue;
            }
            for (int x = minX; x <= maxX; x++) {
                if (Math.abs(x + 0.5 - center.x) > half) {
                    continue;
                }
                for (int z = minZ; z <= maxZ; z++) {
                    if (Math.abs(z + 0.5 - center.z) > half) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)
                            || !TimeDilationFieldRenderer.isFilmCandidate(level.getBlockState(pos), level, pos)
                            || TimeDilationFieldRenderer.isBuried(level, pos)) {
                        continue;
                    }
                    int light = LightTexture.pack(level.getRawBrightness(pos, 0), 0);
                    filmCube(poseStack.last(), consumer, light,
                            (float) (x - center.x - FILM_INFLATE), (float) (y - center.y - FILM_INFLATE),
                            (float) (z - center.z - FILM_INFLATE), (float) (x + 1.0 - center.x + FILM_INFLATE),
                            (float) (y + 1.0 - center.y + FILM_INFLATE), (float) (z + 1.0 - center.z + FILM_INFLATE));
                    if (++drawn >= MAX_FILM_BLOCKS) {
                        return;
                    }
                }
            }
        }
    }

    private static void filmCube(PoseStack.Pose pose, VertexConsumer c, int light,
                                 float x0, float y0, float z0, float x1, float y1, float z1) {
        filmQuad(pose, c, light, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
        filmQuad(pose, c, light, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1);
        filmQuad(pose, c, light, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
        filmQuad(pose, c, light, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
        filmQuad(pose, c, light, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        filmQuad(pose, c, light, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
    }

    private static void filmQuad(PoseStack.Pose pose, VertexConsumer c, int light,
                                 float ax, float ay, float az, float bx, float by, float bz,
                                 float cx, float cy, float cz, float dx, float dy, float dz) {
        filmVertex(pose, c, light, ax, ay, az);
        filmVertex(pose, c, light, bx, by, bz);
        filmVertex(pose, c, light, cx, cy, cz);
        filmVertex(pose, c, light, dx, dy, dz);
    }

    private static void filmVertex(PoseStack.Pose pose, VertexConsumer c, int light, float x, float y, float z) {
        c.addVertex(pose, x, y, z)
                .setColor(FILM_RGB | (FILM_ALPHA << 24))
                .setUv(0.5F, 0.5F)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    // =========================================================
    // Грани куба (шейдер accel_zone)
    // =========================================================

    private static void renderFaces(TimeDilationFieldEntity entity, PoseStack poseStack,
                                    MultiBufferSource bufferSource, double half) {
        if (!TimePhaseRendering.available()) {
            return;
        }
        double boost = (entity.getTemporalRate() - TemporalRate.NORMAL) / TemporalRate.MAX_DILATION_SLOW;
        int alpha = (int) (Math.min(0.95, 0.55 + 0.4 * boost) * 255.0);
        VertexConsumer consumer = bufferSource.getBuffer(TimePhaseRendering.zoneFace(WHITE));
        PoseStack.Pose pose = poseStack.last();
        float h = (float) half;
        float side = h * 2.0F;
        // четыре стены: u — вдоль стены в блоках, v — высота в блоках (росчерки бегут вверх)
        face(pose, consumer, alpha, side, side, -h, -h, -h, h, -h, -h, h, h, -h, -h, h, -h, 0, 0, -1);
        face(pose, consumer, alpha, side, side, h, -h, h, -h, -h, h, -h, h, h, h, h, h, 0, 0, 1);
        face(pose, consumer, alpha, side, side, -h, -h, h, -h, -h, -h, -h, h, -h, -h, h, h, -1, 0, 0);
        face(pose, consumer, alpha, side, side, h, -h, -h, h, -h, h, h, h, h, h, h, -h, 1, 0, 0);
        // крышка и дно: только контур и редкие росчерки
        face(pose, consumer, alpha, side, side, -h, h, -h, h, h, -h, h, h, h, -h, h, h, 0, 1, 0);
        face(pose, consumer, alpha, side, side, -h, -h, h, h, -h, h, h, -h, -h, -h, -h, -h, 0, -1, 0);
    }

    /** Грань A-B-C-D (A,B — низ; C,D — верх). В цвете вершины — координаты внутри грани для шейдера. */
    private static void face(PoseStack.Pose pose, VertexConsumer c, int alpha, float width, float height,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float nx, float ny, float nz) {
        faceVertex(pose, c, alpha, ax, ay, az, 0, 0, 0.0F, 0.0F, nx, ny, nz);
        faceVertex(pose, c, alpha, bx, by, bz, 255, 0, width, 0.0F, nx, ny, nz);
        faceVertex(pose, c, alpha, cx, cy, cz, 255, 255, width, height, nx, ny, nz);
        faceVertex(pose, c, alpha, dx, dy, dz, 0, 255, 0.0F, height, nx, ny, nz);
    }

    private static void faceVertex(PoseStack.Pose pose, VertexConsumer c, int alpha, float x, float y, float z,
                                   int fu, int fv, float u, float v, float nx, float ny, float nz) {
        // без собственного шейдера грань рисует обычная оболочка фазы — ей нужен белый цвет вершины
        boolean own = TimePhaseRendering.zoneShaderActive();
        c.addVertex(pose, x, y, z)
                .setColor(own ? fu : 255, own ? fv : 255, own ? 0 : 255, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, nx, ny, nz);
    }

    // =========================================================
    // Рёбра и бегущие штрихи
    // =========================================================

    private static void renderEdges(TimeDilationFieldEntity entity, PoseStack poseStack,
                                    MultiBufferSource bufferSource, double half, float partialTick) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        Quaternionf rot = camera.rotation();
        Vector3f right = rot.transform(1.0F, 0.0F, 0.0F, new Vector3f());
        Vector3f up = rot.transform(0.0F, 1.0F, 0.0F, new Vector3f());
        VertexConsumer consumer = bufferSource.getBuffer(TimeDilationFieldRenderer.glyphType());
        PoseStack.Pose pose = poseStack.last();

        double rate = entity.getTemporalRate();
        // в отличие от поля, где фаза замедляется вместе со временем, здесь она спешит
        double phase = (entity.tickCount + partialTick) * 0.05 * rate;
        int seed = entity.getId();
        double size = Math.max(0.22, Math.min(0.6, half * 0.11));
        int slots = (int) Math.max(8, Math.min(48, half * 8.0));

        // 4 вертикальных ребра: штрихи бегут вверх
        for (int e = 0; e < 4; e++) {
            double ex = (e & 1) == 0 ? -half : half;
            double ez = (e & 2) == 0 ? -half : half;
            for (int i = 0; i < slots; i++) {
                double along = frac((i + TimeDilationFieldRenderer.hash(seed + e, i) * 0.4) / slots + phase * 0.35);
                float alpha = (float) (ends(along) * (0.6 + 0.4 * TimeDilationFieldRenderer.hash(seed, i + e * 31)));
                TimeDilationFieldRenderer.glyphQuad(pose, consumer, right, up, ex, -half + along * half * 2.0, ez,
                        size, 2, color(TimeDilationFieldRenderer.hash(seed + e, i + 5)), alpha);
            }
        }
        // 8 горизонтальных рёбер (верх и низ): штрихи обегают куб по кругу, верх и низ — навстречу
        for (int e = 0; e < 8; e++) {
            boolean top = e >= 4;
            int sideIndex = e & 3;
            double y = top ? half : -half;
            double direction = top ? 1.0 : -1.0;
            for (int i = 0; i < slots; i++) {
                double along = frac((double) i / slots + direction * phase * 0.22);
                double t = along * 2.0 - 1.0;
                double x = sideIndex == 0 ? t * half : sideIndex == 1 ? half : sideIndex == 2 ? -t * half : -half;
                double z = sideIndex == 0 ? -half : sideIndex == 1 ? t * half : sideIndex == 2 ? half : -t * half;
                if (TimeDilationFieldRenderer.hash(seed + e * 17, i) > 0.82) {
                    continue; // разрывы: ребро пунктирное
                }
                TimeDilationFieldRenderer.glyphQuad(pose, consumer, right, up, x, y, z,
                        size, i % 3 == 0 ? 1 : 0, color(TimeDilationFieldRenderer.hash(seed + e, i + 11)),
                        (float) (0.55 + 0.4 * TimeDilationFieldRenderer.hash(seed, i + e * 7)));
            }
        }
        // редкие знаки (ромб/часы/руна), взлетающие по стенам
        int runes = (int) Math.max(6, Math.min(28, half * 5.0));
        for (int i = 0; i < runes; i++) {
            int wall = (int) (TimeDilationFieldRenderer.hash(seed, i + 101) * 4.0) & 3;
            double t = TimeDilationFieldRenderer.hash(seed, i + 211) * 2.0 - 1.0;
            double rise = frac(TimeDilationFieldRenderer.hash(seed, i + 307)
                    + phase * (0.10 + 0.12 * TimeDilationFieldRenderer.hash(seed, i + 401)));
            double x = wall == 0 ? t * half : wall == 1 ? half : wall == 2 ? t * half : -half;
            double z = wall == 0 ? -half : wall == 1 ? t * half : wall == 2 ? half : t * half;
            int tile = 3 + (int) (TimeDilationFieldRenderer.hash(seed, i + 503) * 3.0);
            TimeDilationFieldRenderer.glyphQuad(pose, consumer, right, up, x, -half + rise * half * 2.0, z,
                    size * 1.5, tile, color(TimeDilationFieldRenderer.hash(seed, i + 601)), (float) (ends(rise) * 0.8));
        }
    }

    /** Плавное появление у начала пути и исчезновение у конца. */
    private static double ends(double t) {
        double in = Math.min(1.0, t / 0.12);
        double out = Math.min(1.0, (1.0 - t) / 0.2);
        return Math.max(0.0, in * out);
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    private static int color(double r) {
        if (r < 0.25) return C_DEEP;
        if (r < 0.55) return C_BLUE;
        if (r < 0.85) return C_SKY;
        return C_WHITE;
    }
}
