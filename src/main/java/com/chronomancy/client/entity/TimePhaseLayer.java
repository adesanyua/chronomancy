package com.chronomancy.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * Слой «фазы» для GeckoLib-сущностей: растворяющаяся часть тела, переливающаяся оболочка и
 * эхо-силуэты (см. {@link TimePhaseRendering}). Рисуется после основной модели и светящейся маски.
 */
public final class TimePhaseLayer<T extends LivingEntity & GeoAnimatable> extends GeoRenderLayer<T> {

    /** Какой проход слоя сейчас перерисовывает модель (читает {@link PhasingGeoRenderer}). */
    enum Pass { DISSOLVE, SHELL, GHOST }

    private final PhasingGeoRenderer<T> renderer;

    public TimePhaseLayer(PhasingGeoRenderer<T> renderer) {
        super(renderer);
        this.renderer = renderer;
    }

    @Override
    public void render(PoseStack poseStack, T animatable, BakedGeoModel bakedModel, RenderType renderType,
                       MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight,
                       int packedOverlay) {
        if (!TimePhaseRendering.available() || animatable.isInvisible()) {
            return;
        }
        ResourceLocation texture = getTextureResource(animatable);
        TimePhaseRendering.Phase phase = renderer.currentPhase();
        try {
            // 1) «выпавшая» часть тела — попиксельное растворение со светящейся кромкой
            if (phase != null) {
                renderer.pass = Pass.DISSOLVE;
                RenderType type = TimePhaseRendering.geoDissolve(texture);
                renderer.reRender(bakedModel, poseStack, bufferSource, animatable, type, bufferSource.getBuffer(type),
                        partialTick, packedLight, packedOverlay, TimePhaseRendering.whiteWithAlpha(1.0F - phase.progress()));
            }
            // 2) переливающаяся оболочка вокруг всего тела; пока сквозь сущность проходят снаряды —
            //    другой палитры и ярче, чтобы стрелок видел, что стрелять сейчас бесполезно
            renderer.pass = Pass.SHELL;
            RenderType shell = TimePhaseRendering.geoShell(texture);
            float immune = renderer.projectileImmunity(animatable, partialTick);
            RenderType body = immune > 0.01F ? TimePhaseRendering.geoShellImmune(texture, immune) : shell;
            renderer.reRender(bakedModel, poseStack, bufferSource, animatable, body, bufferSource.getBuffer(body),
                    partialTick, packedLight, packedOverlay,
                    TimePhaseRendering.whiteWithAlpha(Mth.lerp(immune, renderer.shellAlpha(), 0.9F)));
            // 3) эхо-силуэты (телепорт / рывок), гаснут со временем
            double x = Mth.lerp(partialTick, animatable.xOld, animatable.getX());
            double y = Mth.lerp(partialTick, animatable.yOld, animatable.getY());
            double z = Mth.lerp(partialTick, animatable.zOld, animatable.getZ());
            float bodyYaw = Mth.rotLerp(partialTick, animatable.yBodyRotO, animatable.yBodyRot);
            float now = animatable.tickCount + partialTick;
            renderer.pass = Pass.GHOST;
            for (TimePhaseRendering.Ghost g : renderer.ghosts()) {
                float alpha = g.alpha(now);
                if (alpha <= 0.02F || (g.x - x) * (g.x - x) + (g.z - z) * (g.z - z) < 0.25D) {
                    continue;
                }
                poseStack.pushPose();
                poseStack.translate(g.x - x, g.y - y, g.z - z);
                // reRender повернёт модель на текущий yaw тела — доворачиваем до yaw силуэта.
                poseStack.mulPose(Axis.YP.rotationDegrees(bodyYaw - g.yaw));
                renderer.reRender(bakedModel, poseStack, bufferSource, animatable, shell, bufferSource.getBuffer(shell),
                        partialTick, packedLight, packedOverlay, TimePhaseRendering.whiteWithAlpha(alpha));
                poseStack.popPose();
            }
        } finally {
            renderer.pass = null;
        }
    }
}
