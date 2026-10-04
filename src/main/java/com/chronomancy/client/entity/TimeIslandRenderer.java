package com.chronomancy.client.entity;

import com.chronomancy.entity.TimeIslandEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * Островок времени — цилиндр, родственный кубу Accelerated Zone.
 *
 * <ul>
 *   <li>Стена (шейдер {@code time_island_wall}): светящаяся кромка у земли, вверх по стене бегут росчерки
 *       и поднимаются кольца, к верхнему краю свет тает — «колодец» времени. При появлении стена
 *       вырастает из земли, при закрытии — уходит обратно.</li>
 *   <li>Пятно на земле (шейдер {@code time_island}): от центра бегут круги, по кругу идёт стрелка,
 *       внешнее кольцо показывает оставшийся срок.</li>
 * </ul>
 * Рисунок целиком считают шейдеры; сюда приходит только геометрия и несколько чисел в цвете вершин.
 * То, что внутри цилиндра экран снова цветной, делает {@code WorldStopPostProcess}.
 */
public class TimeIslandRenderer extends EntityRenderer<TimeIslandEntity> {
    private static final int SEGMENTS = 48;
    private static final ResourceLocation WHITE =
            ResourceLocation.fromNamespaceAndPath("chronomancy", "textures/stasis_white.png");

    public TimeIslandRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    /** Пятно намного шире маленького хитбокса сущности — по нему его отсекать нельзя. */
    @Override
    public boolean shouldRender(TimeIslandEntity entity, Frustum frustum, double x, double y, double z) {
        return true;
    }

    @Override
    public void render(TimeIslandEntity entity, float yaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight) {
        float strength = entity.strength(partialTick);
        if (strength <= 0.01F) {
            return;
        }
        float radius = entity.getRadius();
        double d = radius * 0.6D;
        GroundDecals.Sample ground = GroundDecals.sample(entity.level(), entity.getX(), entity.getY(), entity.getZ(),
                0.0D, 0.0D, d, 0.0D, -d, 0.0D, 0.0D, d, 0.0D, -d);
        float y = ground.lift();
        wall(entity, partialTick, poseStack, buffers, radius, strength, y);
        if (!TimePhaseRendering.timeIslandShaderActive()) {
            plain(poseStack, buffers, radius, strength, y, ground.bright());
            return;
        }
        VertexConsumer vc = buffers.getBuffer(TimePhaseRendering.timeIsland(WHITE));
        PoseStack.Pose pose = poseStack.last();
        // цвет вершины несёт не цвет, а данные для шейдера (см. time_island.fsh)
        int r = Math.round(strength * 255.0F), g = Math.round(entity.remaining(partialTick) * 255.0F);
        int b = Math.round(Mth.clamp(radius / 16.0F, 0.0F, 1.0F) * 255.0F), a = Math.round(ground.bright() * 255.0F);
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = Math.PI * 2.0D * i / SEGMENTS, a1 = Math.PI * 2.0D * (i + 1) / SEGMENTS;
            float x0 = (float) Math.cos(a0), z0 = (float) Math.sin(a0), x1 = (float) Math.cos(a1), z1 = (float) Math.sin(a1);
            vertex(vc, pose, 0.0F, y, 0.0F, 0.0F, 0.0F, r, g, b, a);
            vertex(vc, pose, 0.0F, y, 0.0F, 0.0F, 0.0F, r, g, b, a);
            vertex(vc, pose, x0 * radius, y, z0 * radius, x0, z0, r, g, b, a);
            vertex(vc, pose, x1 * radius, y, z1 * radius, x1, z1, r, g, b, a);
        }
    }

    /** Стена цилиндра: {@code SEGMENTS} граней от земли до высоты действия островка. */
    private static void wall(TimeIslandEntity entity, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                             float radius, float strength, float base) {
        if (!TimePhaseRendering.timeIslandWallShaderActive()) {
            return;
        }
        // стена вырастает и уходит вместе с яркостью: быстро поднимается, плавно добирает высоту
        float grown = 1.0F - (1.0F - strength) * (1.0F - strength);
        float top = base + (float) TimeIslandEntity.HEIGHT * grown;
        if (top - base < 0.05F) {
            return;
        }
        VertexConsumer vc = buffers.getBuffer(TimePhaseRendering.timeIslandWall(WHITE));
        PoseStack.Pose pose = poseStack.last();
        int left = Math.round(entity.remaining(partialTick) * 255.0F);
        int alpha = Math.round(Mth.clamp(strength, 0.0F, 1.0F) * 0.9F * 255.0F);
        float height = top - base;
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = Math.PI * 2.0D * i / SEGMENTS, a1 = Math.PI * 2.0D * (i + 1) / SEGMENTS;
            float x0 = (float) Math.cos(a0), z0 = (float) Math.sin(a0), x1 = (float) Math.cos(a1), z1 = (float) Math.sin(a1);
            // u — вдоль окружности в блоках, v — высота в блоках (росчерки и кольца бегут вверх)
            float u0 = (float) (a0 * radius), u1 = (float) (a1 * radius);
            wallVertex(vc, pose, x0 * radius, base, z0 * radius, u0, 0.0F, 0, left, alpha, x0, z0);
            wallVertex(vc, pose, x1 * radius, base, z1 * radius, u1, 0.0F, 0, left, alpha, x1, z1);
            wallVertex(vc, pose, x1 * radius, top, z1 * radius, u1, height, 255, left, alpha, x1, z1);
            wallVertex(vc, pose, x0 * radius, top, z0 * radius, u0, height, 255, left, alpha, x0, z0);
        }
    }

    private static void wallVertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, float u, float v,
                                   int heightFraction, int left, int alpha, float nx, float nz) {
        // цвет вершины несёт данные для шейдера: g — высота в долях цилиндра, b — остаток срока, a — яркость
        vc.addVertex(pose, x, y, z)
                .setColor(0, heightFraction, left, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, nx, 0.0F, nz);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, float u, float v,
                               int r, int g, int b, int a) {
        vc.addVertex(pose, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    /** Запасной вид без своего шейдера: ровный мятный круг, светлеющий к краю. */
    private static void plain(PoseStack poseStack, MultiBufferSource buffers, float radius, float strength,
                              float lift, float bright) {
        VertexConsumer vc = buffers.getBuffer(SandsConeRendering.plainType());
        Matrix4f m = poseStack.last().pose();
        float y = lift + 0.03F;
        float dim = 1.0F - 0.55F * bright;
        float centre = 0.16F * strength * (1.0F + bright), rim = 0.7F * strength;
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = Math.PI * 2.0D * i / SEGMENTS, a1 = Math.PI * 2.0D * (i + 1) / SEGMENTS;
            vc.addVertex(m, 0.0F, y, 0.0F).setColor(0.3F * dim, dim, 0.8F * dim, centre);
            vc.addVertex(m, 0.0F, y, 0.0F).setColor(0.3F * dim, dim, 0.8F * dim, centre);
            vc.addVertex(m, (float) Math.cos(a0) * radius, y, (float) Math.sin(a0) * radius).setColor(0.5F * dim, dim, 0.8F * dim, rim);
            vc.addVertex(m, (float) Math.cos(a1) * radius, y, (float) Math.sin(a1) * radius).setColor(0.5F * dim, dim, 0.8F * dim, rim);
        }
    }

    @Override
    public ResourceLocation getTextureLocation(TimeIslandEntity entity) {
        return WHITE;
    }
}
