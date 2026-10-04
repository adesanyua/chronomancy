package com.chronomancy.client.entity;

import com.chronomancy.entity.TimeRiftEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Time Rift: полупрозрачный светящийся разрыв, всегда развёрнутый к камере по вертикальной
 * оси (billboard) и не зависящий от освещения.
 */
public final class TimeRiftRenderer extends GeoEntityRenderer<TimeRiftEntity> {
    /** Высота центра разрыва в модели (pivot костей — 19 px). */
    private static final float MODEL_CENTER_Y = 19.0F / 16.0F;

    public TimeRiftRenderer(EntityRendererProvider.Context context) {
        super(context, new ChronoEntityGeoModel<>("time_rift", false));
        this.shadowRadius = 0.0F;
    }

    @Override
    public RenderType getRenderType(TimeRiftEntity animatable, ResourceLocation texture,
                                    @Nullable MultiBufferSource bufferSource, float partialTick) {
        return RenderType.entityTranslucentEmissive(texture);
    }

    @Override
    public void render(TimeRiftEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        if (entity.getPhase() == TimeRiftEntity.Phase.DORMANT) {
            return; // ещё не раскрылся
        }
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, LightTexture.FULL_BRIGHT);
    }

    @Override
    protected void applyRotations(TimeRiftEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick, float nativeScale) {
        if (animatable.isOverhead()) {
            // Врата босса: разрыв лежит горизонтально над ареной и раскрыт вниз. Центр модели
            // (ось раскрытия, 19 px от низа) совмещаем с позицией сущности.
            float scale = animatable.getRiftScale();
            poseStack.scale(scale, scale, scale);
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            poseStack.translate(0.0F, -MODEL_CENTER_Y, 0.0F);
            return;
        }
        float cameraYaw = this.entityRenderDispatcher.camera.getYRot();
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - cameraYaw));
    }
}
