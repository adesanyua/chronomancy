package com.chronomancy.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** Full-bright camera-facing quad. Geometry and lighting never resemble a solid block. */
final class TemporalProjectileSprite {
    private TemporalProjectileSprite() {}

    static void draw(PoseStack poses, VertexConsumer vertices, float size) {
        PoseStack.Pose pose = poses.last();
        float h = size * 0.5f;
        vertex(pose, vertices, -h, -h, 0, 1);
        vertex(pose, vertices, h, -h, 1, 1);
        vertex(pose, vertices, h, h, 1, 0);
        vertex(pose, vertices, -h, h, 0, 0);
    }

    /** Reveal a rune clockwise in wedges, using the existing projectile material and texture. */
    static void drawAssembling(PoseStack poses, VertexConsumer vertices, float size, float progress) {
        float end = Math.clamp(progress, 0, 1) * 12;
        for (int i = 0; i < Math.ceil(end); i++) {
            double a = i * Math.PI / 6, b = Math.min(i + 1, end) * Math.PI / 6;
            float x0 = (float)Math.cos(a) * .5f, y0 = (float)Math.sin(a) * .5f;
            float x1 = (float)Math.cos(b) * .5f, y1 = (float)Math.sin(b) * .5f;
            vertex(poses.last(), vertices, 0, 0, .5f, .5f);
            vertex(poses.last(), vertices, x0 * size, y0 * size, x0 + .5f, .5f - y0);
            vertex(poses.last(), vertices, x1 * size, y1 * size, x1 + .5f, .5f - y1);
            vertex(poses.last(), vertices, 0, 0, .5f, .5f);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vertices,
                               float x, float y, float u, float v) {
        vertices.addVertex(pose, x, y, 0).setColor(255, 255, 255, 255)
                .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
    }
}
