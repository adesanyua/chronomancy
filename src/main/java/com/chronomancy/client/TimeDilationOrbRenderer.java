package com.chronomancy.client;

import com.chronomancy.entity.TimeDilationOrbEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** Large rotating temporal vortex, rendered as a luminous sprite rather than a cube cluster. */
public class TimeDilationOrbRenderer extends EntityRenderer<TimeDilationOrbEntity> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "chronomancy", "textures/entity/time_dilation_orb.png");
    private static final RenderType TYPE = RenderType.entityTranslucentEmissive(TEXTURE);
    /** Сгусток Accelerated Zone: голубое ядро, золотой обод — и крутится втрое быстрее. */
    private static final ResourceLocation ZONE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "chronomancy", "textures/entity/accelerated_zone_orb.png");
    private static final RenderType ZONE_TYPE = RenderType.entityTranslucentEmissive(ZONE_TEXTURE);

    public TimeDilationOrbRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0;
    }

    @Override
    public void render(TimeDilationOrbEntity entity, float yaw, float partialTick,
                       PoseStack poses, MultiBufferSource buffers, int light) {
        float age = entity.tickCount + partialTick;
        poses.pushPose();
        poses.translate(0, entity.getBbHeight() * 0.5, 0);
        poses.mulPose(this.entityRenderDispatcher.cameraOrientation());
        boolean zone = entity.isAccelerating();
        poses.mulPose(Axis.ZP.rotationDegrees(age * (zone ? 16 : 5)));
        TemporalProjectileSprite.draw(poses, buffers.getBuffer(zone ? ZONE_TYPE : TYPE),
                1.15f + 0.07f * Mth.sin(age * (zone ? 0.7f : 0.24f)));
        poses.popPose();
        super.render(entity, yaw, partialTick, poses, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(TimeDilationOrbEntity entity) {
        return entity.isAccelerating() ? ZONE_TEXTURE : TEXTURE;
    }
}
