package com.chronomancy.client;

import com.chronomancy.entity.TemporalNeedleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** A thin, directional ivory/brass needle. No Backtrack texture, spinning star or oversized halo. */
public class TemporalNeedleRenderer extends EntityRenderer<TemporalNeedleEntity> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("chronomancy", "textures/entity/temporal_needle.png");
    private static final RenderType TYPE = RenderType.entityTranslucentEmissive(TEXTURE);
    private static final RenderType TIP = RenderType.entityTranslucentEmissive(
            ResourceLocation.fromNamespaceAndPath("chronomancy", "textures/entity/temporal_needle_tip.png"));
    public TemporalNeedleRenderer(EntityRendererProvider.Context context) { super(context); shadowRadius = 0; }

    @Override public void render(TemporalNeedleEntity entity, float yaw, float partialTick, PoseStack poses, MultiBufferSource buffers, int light) {
        Vec3 direction = entity.getDeltaMovement().normalize();
        if (direction.lengthSqr() < 1e-6) direction = entity.getLookAngle();
        poses.pushPose();
        poses.mulPose(new Quaternionf().rotationTo(0, 1, 0, (float)direction.x, (float)direction.y, (float)direction.z));
        // Crossed flat sprites remain visible from the side without making a chunky 3D model.
        for (int i = 0; i < 2; i++) {
            poses.pushPose();
            poses.mulPose(Axis.YP.rotationDegrees(i * 90));
            poses.scale(.34f, .78f, 1);
            TemporalProjectileSprite.draw(poses, buffers.getBuffer(TYPE), 1);
            poses.popPose();
        }
        poses.popPose();
        // Tiny end-on glint keeps the shaft visible to the caster looking along its axis.
        poses.pushPose();
        poses.translate(direction.x * .3, direction.y * .3, direction.z * .3);
        poses.mulPose(this.entityRenderDispatcher.cameraOrientation());
        TemporalProjectileSprite.draw(poses, buffers.getBuffer(TIP), .12f);
        poses.popPose();
        super.render(entity, yaw, partialTick, poses, buffers, light);
    }
    @Override public ResourceLocation getTextureLocation(TemporalNeedleEntity entity) { return TEXTURE; }
}
