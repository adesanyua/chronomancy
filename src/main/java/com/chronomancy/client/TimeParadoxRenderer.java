package com.chronomancy.client;

import com.chronomancy.client.entity.TimePhaseRendering;
import com.chronomancy.entity.TimeParadoxEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Вспышка временного парадокса. Поле (сфера) и зона (куб) рвут друг друга: из точки взрыва
 * расходятся золотая сфера и вращающийся голубой куб, в центре гаснет белое ядро, по земле бежит
 * кольцо знаков. Рябь на экране добавляет {@link AccelZonePostProcess}, частицы — сама сущность.
 * Никаких блоков и ванильного взрыва.
 */
public class TimeParadoxRenderer extends EntityRenderer<TimeParadoxEntity> {
    private static final ResourceLocation WHITE =
            ResourceLocation.fromNamespaceAndPath("chronomancy", "textures/stasis_white.png");
    private static final int GOLD = 0xFFC75C;
    private static final int SKY = 0x7FD8FF;
    private static final int CORE = 0xFFF6E0;
    private static final int LAT = 14;
    private static final int LON = 28;

    public TimeParadoxRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    /** Вспышка большая: её нельзя отсекать по маленькому хитбоксу сущности. */
    @Override
    public boolean shouldRender(TimeParadoxEntity entity, Frustum frustum, double x, double y, double z) {
        return true;
    }

    @Override
    public void render(TimeParadoxEntity entity, float yaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        float progress = entity.progress(partialTick);
        if (progress >= 1.0F) {
            return;
        }
        float radius = entity.getRadius();
        float fade = (float) Math.pow(1.0F - progress, 1.5);
        float shell = radius * AccelZonePostProcess.shellFraction(progress);

        poseStack.pushPose();
        poseStack.translate(0.0F, 0.6F, 0.0F);
        if (TimePhaseRendering.available()) {
            VertexConsumer consumer = bufferSource.getBuffer(TimePhaseRendering.paradoxShell(WHITE));
            // золотая сфера — бывшее поле замедления
            sphere(poseStack.last(), consumer, shell, argb(GOLD, 0.85F * fade));
            // голубой куб — бывшая зона: чуть отстаёт и кувыркается
            poseStack.pushPose();
            poseStack.mulPose(Axis.YP.rotationDegrees(progress * 220.0F));
            poseStack.mulPose(Axis.XP.rotationDegrees(progress * 95.0F));
            cube(poseStack.last(), consumer, shell * 0.62F, argb(SKY, 0.9F * fade));
            poseStack.popPose();
            // ядро: яркая точка разрыва, гаснет за первую треть
            float core = 1.0F - Math.min(1.0F, progress / 0.35F);
            if (core > 0.01F) {
                sphere(poseStack.last(), consumer, radius * (0.10F + 0.22F * (1.0F - core)), argb(CORE, core));
            }
        }
        ring(entity, poseStack, bufferSource, shell, fade, partialTick);
        poseStack.popPose();
    }

    private static int argb(int rgb, float alpha) {
        return rgb | ((int) (Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F) << 24);
    }

    private static void sphere(PoseStack.Pose pose, VertexConsumer consumer, float r, int argb) {
        for (int i = 0; i < LAT; i++) {
            double t0 = Math.PI * i / LAT, t1 = Math.PI * (i + 1) / LAT;
            for (int j = 0; j < LON; j++) {
                double p0 = 2 * Math.PI * j / LON, p1 = 2 * Math.PI * (j + 1) / LON;
                sphereVertex(pose, consumer, argb, r, t0, p0, j, i);
                sphereVertex(pose, consumer, argb, r, t1, p0, j, i + 1);
                sphereVertex(pose, consumer, argb, r, t1, p1, j + 1, i + 1);
                sphereVertex(pose, consumer, argb, r, t0, p1, j + 1, i);
            }
        }
    }

    private static void sphereVertex(PoseStack.Pose pose, VertexConsumer consumer, int argb, float r,
                                     double theta, double phi, int u, int v) {
        float nx = (float) (Math.sin(theta) * Math.cos(phi));
        float ny = (float) Math.cos(theta);
        float nz = (float) (Math.sin(theta) * Math.sin(phi));
        consumer.addVertex(pose, nx * r, ny * r, nz * r)
                .setColor(argb)
                .setUv(u / (float) LON, v / (float) LAT)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, nx, ny, nz);
    }

    private static void cube(PoseStack.Pose pose, VertexConsumer c, float h, int argb) {
        quad(pose, c, argb, -h, -h, -h, h, -h, -h, h, h, -h, -h, h, -h, 0, 0, -1);
        quad(pose, c, argb, h, -h, h, -h, -h, h, -h, h, h, h, h, h, 0, 0, 1);
        quad(pose, c, argb, -h, -h, h, -h, -h, -h, -h, h, -h, -h, h, h, -1, 0, 0);
        quad(pose, c, argb, h, -h, -h, h, -h, h, h, h, h, h, h, -h, 1, 0, 0);
        quad(pose, c, argb, -h, h, -h, h, h, -h, h, h, h, -h, h, h, 0, 1, 0);
        quad(pose, c, argb, -h, -h, h, h, -h, h, h, -h, -h, -h, -h, -h, 0, -1, 0);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer c, int argb,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float nx, float ny, float nz) {
        // UV граней куба сдвинуты в [2..3]: по этому шейдер paradox_blast отличает куб от сферы
        vertex(pose, c, argb, ax, ay, az, 2.0F, 2.0F, nx, ny, nz);
        vertex(pose, c, argb, bx, by, bz, 3.0F, 2.0F, nx, ny, nz);
        vertex(pose, c, argb, cx, cy, cz, 3.0F, 3.0F, nx, ny, nz);
        vertex(pose, c, argb, dx, dy, dz, 2.0F, 3.0F, nx, ny, nz);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer c, int argb, float x, float y, float z,
                               float u, float v, float nx, float ny, float nz) {
        c.addVertex(pose, x, y, z)
                .setColor(argb)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, nx, ny, nz);
    }

    /** Кольцо знаков по земле: бежит вместе с оболочкой, золото и голубое вперемешку. */
    private static void ring(TimeParadoxEntity entity, PoseStack poseStack, MultiBufferSource bufferSource,
                             float shell, float fade, float partialTick) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (!camera.isInitialized() || fade <= 0.02F) {
            return;
        }
        Quaternionf rot = camera.rotation();
        Vector3f right = rot.transform(1.0F, 0.0F, 0.0F, new Vector3f());
        Vector3f up = rot.transform(0.0F, 1.0F, 0.0F, new Vector3f());
        VertexConsumer consumer = bufferSource.getBuffer(TimeDilationFieldRenderer.glyphType());
        PoseStack.Pose pose = poseStack.last();
        int seed = entity.getId();
        int count = (int) Math.max(16, Math.min(72, shell * 7.0F));
        double spin = (entity.tickCount + partialTick) * 0.06;
        double size = Math.max(0.3, Math.min(0.9, entity.getRadius() * 0.09));
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count + spin * ((i & 1) == 0 ? 1.0 : -1.0);
            int tile = 3 + (int) (TimeDilationFieldRenderer.hash(seed, i) * 5.0);
            TimeDilationFieldRenderer.glyphQuad(pose, consumer, right, up,
                    Math.cos(angle) * shell, -0.45 + 0.25 * TimeDilationFieldRenderer.hash(seed, i + 40),
                    Math.sin(angle) * shell, size, tile, (i & 1) == 0 ? GOLD : SKY, fade);
        }
    }

    @Override
    public ResourceLocation getTextureLocation(TimeParadoxEntity entity) {
        return WHITE;
    }
}
