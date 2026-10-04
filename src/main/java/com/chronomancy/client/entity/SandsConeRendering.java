package com.chronomancy.client.entity;

import com.chronomancy.entity.RiftMakerEntity;
import com.chronomancy.entity.SandsCone;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Предупреждающая область Sands of Time у Rift Maker — чтобы было видно, куда отбегать.
 *
 * <p>Рисуется только след струи на земле: пятно, внутри которого игрока заденет
 * ({@link SandsCone#footprintMesh}, та же проверка, что на сервере). Границу никто не чертит линией —
 * её показывает шейдер {@code sands_footprint}: к краю пятно разгорается, по кромке бегут искры, от
 * неё внутрь расходится рябь, а внутри от босса течёт песок. На замахе граница мигает и пятно
 * наливается цветом.
 *
 * <p>Глубину пятно не пишет и видно с обеих сторон. Если шейдер не загрузился, пятно рисуется
 * ванильным (как у молнии): ровная заливка, светлеющая к краю.
 */
public final class SandsConeRendering {
    private static final int AZIMUTHS = 96;
    private static final int RADIAL_STEPS = 6;
    /** На какой высоте над ногами босса лежит центр тела игрока — для него и считается след. */
    private static final double VICTIM_HEIGHT = 0.9D;
    /** Сколько блоков расстояния до края умещается в канал цвета вершины (дальше край уже не светит). */
    private static final float EDGE_BLOCKS = 2.0F;
    private static final ResourceLocation WHITE =
            ResourceLocation.fromNamespaceAndPath("chronomancy", "textures/stasis_white.png");

    private static RenderType fallback;

    private SandsConeRendering() {
    }

    /** Простой тип без своего шейдера — запасной и для других областей босса. */
    static RenderType plainType() {
        return fallback();
    }

    private static RenderType fallback() {
        if (fallback == null) {
            fallback = RenderType.create("chronomancy_sands_footprint_plain",
                    DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 4096, false, true,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.RENDERTYPE_LIGHTNING_SHADER)
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .setCullState(RenderStateShard.NO_CULL)
                            .createCompositeState(false));
        }
        return fallback;
    }

    /** Вызывается из рендера босса; {@code poseStack} стоит в его ногах, оси — мировые. */
    public static void render(RiftMakerEntity boss, float partialTick, PoseStack poseStack, MultiBufferSource buffers) {
        int state = boss.sandsState();
        Vec3 dir = state == RiftMakerEntity.SANDS_STATE_NONE ? null : boss.sandsDir(partialTick);
        if (dir == null) {
            return;
        }
        boolean windup = state == RiftMakerEntity.SANDS_STATE_WINDUP;
        float ramp = windup ? Mth.clamp(boss.sandsAge(partialTick) / RiftMakerEntity.SANDS_WINDUP, 0.0F, 1.0F) : 1.0F;
        Vec3 origin = new Vec3(0.0D, boss.getBbHeight() * 0.6D, 0.0D);
        float[] mesh = SandsCone.footprintMesh(origin, dir, RiftMakerEntity.SANDS_RANGE, RiftMakerEntity.SANDS_CONE_COS,
                VICTIM_HEIGHT, AZIMUTHS, RADIAL_STEPS);
        if (mesh.length == 0) {
            return;
        }
        // земля под пятном: поднять его над слоем снега и узнать, не пора ли на тёмную палитру
        double flat = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        double fx = flat < 1.0e-4 ? 0.0D : dir.x / flat, fz = flat < 1.0e-4 ? 0.0D : dir.z / flat;
        GroundDecals.Sample ground = GroundDecals.sample(boss.level(), boss.getX(), boss.getY(), boss.getZ(),
                0.0D, 0.0D, fx * 3.0D, fz * 3.0D, fx * 5.5D, fz * 5.5D, fx * 8.0D, fz * 8.0D);
        float y = ground.lift();
        int bright = Math.round(ground.bright() * 255.0F);
        if (TimePhaseRendering.sandsShaderActive()) {
            VertexConsumer vc = buffers.getBuffer(TimePhaseRendering.sandsFootprint(WHITE));
            PoseStack.Pose pose = poseStack.last();
            int flowing = windup ? 0 : 255;
            int filled = Math.round(ramp * 255.0F);
            for (int i = 0; i < mesh.length; i += SandsCone.MESH_STRIDE) {
                float x = mesh[i], z = mesh[i + 1];
                int edge = Math.round(Mth.clamp(mesh[i + 2] / EDGE_BLOCKS, 0.0F, 1.0F) * 255.0F);
                // цвет вершины несёт не цвет, а данные для шейдера (см. sands_footprint.fsh)
                vc.addVertex(pose, x, y, z)
                        .setColor(edge, flowing, filled, bright)
                        .setUv(x, z)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LightTexture.FULL_BRIGHT)
                        .setNormal(pose, 0.0F, 1.0F, 0.0F);
            }
            return;
        }
        // запасной вид без своего шейдера
        VertexConsumer vc = buffers.getBuffer(fallback());
        Matrix4f m = poseStack.last().pose();
        float time = (Util.getMillis() % 600_000L) / 1000.0F;
        float blink = windup ? 0.6F + 0.4F * Mth.sin(time * 20.0F) : 1.0F;
        for (int i = 0; i < mesh.length; i += SandsCone.MESH_STRIDE) {
            float glow = (float) Math.exp(-mesh[i + 2] * 2.4F);
            float alpha = Mth.clamp((windup ? 0.08F + 0.16F * ramp : 0.26F) * (1.0F + ground.bright()) + 0.6F * glow * blink, 0.0F, 0.95F);
            float dim = 1.0F - 0.55F * ground.bright(); // на светлой земле — темнее
            vc.addVertex(m, mesh[i], y + 0.03F, mesh[i + 1])
                    .setColor(dim, (0.70F + 0.25F * glow) * dim, (0.18F + 0.5F * glow) * dim, alpha);
        }
    }
}
