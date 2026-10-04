package com.chronomancy.client;

import com.chronomancy.entity.BacktrackBoltEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** Small airborne glint with its own texture, not the school particle atlas. */
public class BacktrackBoltRenderer extends EntityRenderer<BacktrackBoltEntity> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "chronomancy", "textures/entity/backtrack_bolt.png");
    private static final RenderType TYPE = RenderType.entityTranslucentEmissive(TEXTURE);

    public BacktrackBoltRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0;
    }

    @Override
    public void render(BacktrackBoltEntity entity, float yaw, float partialTick,
                       PoseStack poses, MultiBufferSource buffers, int light) {
        float age = entity.tickCount + partialTick;
        poses.pushPose();
        poses.translate(0, entity.getBbHeight() * 0.5, 0);
        poses.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poses.mulPose(Axis.ZP.rotationDegrees(age * -9));
        TemporalProjectileSprite.draw(poses, buffers.getBuffer(TYPE), 0.42f + 0.025f * Mth.sin(age * 0.7f));
        poses.popPose();
        super.render(entity, yaw, partialTick, poses, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(BacktrackBoltEntity entity) { return TEXTURE; }
}
