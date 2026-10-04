package com.chronomancy.client.entity;

import com.chronomancy.entity.RiftMakerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * Круги на земле под Rift Maker для двух его ударов по области.
 *
 * <ul>
 *   <li>Усиленный удар (каждый третий взмах): шейдер {@code heavy_shockwave} — на замахе от босса к краю
 *       круга ползут синие трещины-разломы, в момент удара от центра проходит ударная волна.</li>
 *   <li>Удар стазиса: шейдер {@code stasis_dial} — циферблат со стрелкой, которая за замах делает полный
 *       оборот; в момент удара он вспыхивает, «застывает» и гаснет.</li>
 * </ul>
 * Круг совпадает с настоящей областью удара. Сам рисунок целиком считает шейдер; сюда приходит только
 * диск и три числа в цвете вершин: ход фазы, замах это или удар, и радиус.
 */
public final class BossGroundFxRendering {
    private static final int SEGMENTS = 48;
    private static final ResourceLocation WHITE =
            ResourceLocation.fromNamespaceAndPath("chronomancy", "textures/stasis_white.png");

    private BossGroundFxRendering() {
    }

    /** Вызывается из рендера босса; {@code poseStack} стоит в его ногах, оси — мировые. */
    public static void render(RiftMakerEntity boss, float partialTick, PoseStack poseStack, MultiBufferSource buffers) {
        int fx = boss.groundFx();
        if (fx == RiftMakerEntity.FX_NONE) {
            return;
        }
        boolean heavy = fx == RiftMakerEntity.FX_HEAVY_WINDUP || fx == RiftMakerEntity.FX_HEAVY_IMPACT;
        boolean impact = fx == RiftMakerEntity.FX_HEAVY_IMPACT || fx == RiftMakerEntity.FX_STASIS_IMPACT;
        float progress = Mth.clamp(boss.groundFxAge(partialTick) / RiftMakerEntity.groundFxDuration(fx), 0.0F, 1.0F);
        float radius = (float) (heavy ? RiftMakerEntity.HEAVY_RADIUS : boss.stasisSlamRadius());

        // земля под кругом: поднять его над слоем снега и узнать, не пора ли на тёмную палитру
        double d = radius * 0.6D;
        GroundDecals.Sample ground = GroundDecals.sample(boss.level(), boss.getX(), boss.getY(), boss.getZ(),
                0.0D, 0.0D, d, 0.0D, -d, 0.0D, 0.0D, d, 0.0D, -d);
        float y = ground.lift();
        boolean shader = heavy ? TimePhaseRendering.heavyShaderActive() : TimePhaseRendering.stasisDialShaderActive();
        if (!shader) {
            plain(poseStack, buffers, radius, progress, impact, y, ground.bright());
            return;
        }
        RenderType type = heavy ? TimePhaseRendering.heavyShockwave(WHITE) : TimePhaseRendering.stasisDial(WHITE);
        VertexConsumer vc = buffers.getBuffer(type);
        PoseStack.Pose pose = poseStack.last();
        // цвет вершины несёт не цвет, а данные для шейдера (см. heavy_shockwave.fsh / stasis_dial.fsh)
        int r = Math.round(progress * 255.0F), g = impact ? 255 : 0, b = Math.round(Mth.clamp(radius / 16.0F, 0.0F, 1.0F) * 255.0F);
        int a = Math.round(ground.bright() * 255.0F);
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = Math.PI * 2.0D * i / SEGMENTS, a1 = Math.PI * 2.0D * (i + 1) / SEGMENTS;
            float x0 = (float) Math.cos(a0), z0 = (float) Math.sin(a0), x1 = (float) Math.cos(a1), z1 = (float) Math.sin(a1);
            vertex(vc, pose, 0.0F, y, 0.0F, 0.0F, 0.0F, r, g, b, a);
            vertex(vc, pose, 0.0F, y, 0.0F, 0.0F, 0.0F, r, g, b, a);
            vertex(vc, pose, x0 * radius, y, z0 * radius, x0, z0, r, g, b, a);
            vertex(vc, pose, x1 * radius, y, z1 * radius, x1, z1, r, g, b, a);
        }
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

    /** Запасной вид без своих шейдеров: ровный круг, светлеющий к краю. */
    private static void plain(PoseStack poseStack, MultiBufferSource buffers, float radius,
                              float progress, boolean impact, float lift, float bright) {
        VertexConsumer vc = buffers.getBuffer(SandsConeRendering.plainType());
        Matrix4f m = poseStack.last().pose();
        float y = lift + 0.03F;
        float dim = 1.0F - 0.55F * bright; // на светлой земле — темнее и плотнее
        float centre = (impact ? 0.35F * (1.0F - progress) : 0.08F + 0.18F * progress) * (1.0F + bright);
        float rim = impact ? 0.8F * (1.0F - progress) : 0.75F;
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = Math.PI * 2.0D * i / SEGMENTS, a1 = Math.PI * 2.0D * (i + 1) / SEGMENTS;
            vc.addVertex(m, 0.0F, y, 0.0F).setColor(dim, 0.72F * dim, 0.2F * dim, centre);
            vc.addVertex(m, 0.0F, y, 0.0F).setColor(dim, 0.72F * dim, 0.2F * dim, centre);
            vc.addVertex(m, (float) Math.cos(a0) * radius, y, (float) Math.sin(a0) * radius).setColor(dim, 0.9F * dim, 0.6F * dim, rim);
            vc.addVertex(m, (float) Math.cos(a1) * radius, y, (float) Math.sin(a1) * radius).setColor(dim, 0.9F * dim, 0.6F * dim, rim);
        }
    }
}
