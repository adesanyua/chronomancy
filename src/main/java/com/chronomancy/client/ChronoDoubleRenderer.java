package com.chronomancy.client;

import com.chronomancy.entity.ChronoDoubleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Renders the ChronoDouble as a copper-tinted mirror of the owner's live, animated player model.
 *
 * <p>Rather than reproducing a player model by hand, we simply re-invoke the owner's real
 * {@link PlayerRenderer} on the owner entity. The entity dispatcher has already translated the
 * {@code poseStack} to the double's synced anchor position (the mirror offset set by
 * {@link com.chronomancy.temporal.chronodouble.ChronoDoubleManager}), so the model is drawn on the
 * double's side while every animation value — swings, cast gesture, walk cycle, crouch, held item —
 * is read live from the owner, giving a perfect action echo for free.
 *
 * <p>The copper recolor is bracketed by {@link ChronoDoubleTint.begin()}/{@link ChronoDoubleTint.end()}
 * around that inner render: the model tint mixin blends every skin/armor {@code ModelPart} toward
 * copper while preserving the real texture and its alpha, so the double reads as the player's own
 * figure recolored copper. If the owner is not currently client-side (left / not tracked), we draw
 * nothing.
 */
public class ChronoDoubleRenderer extends EntityRenderer<ChronoDoubleEntity> {

    public ChronoDoubleRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F; // the copper echo is translucent light, it casts no shadow
    }

    @Override
    public void render(ChronoDoubleEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        UUID ownerUUID = entity.getOwnerUUID();
        if (ownerUUID == null || !(entity.level() instanceof ClientLevel clientLevel)) {
            return;
        }
        if (!(clientLevel.getPlayerByUUID(ownerUUID) instanceof AbstractClientPlayer owner)) {
            return; // owner not tracked here (left / out of range): nothing to mirror
        }

        EntityRenderer<?> base = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(owner);
        if (!(base instanceof PlayerRenderer playerRenderer)) {
            return;
        }

        // Do not render at the anchor's packet-stepped position. Predict from the SAME
        // interpolated owner used by the animation, including local first-person movement.
        float yaw = net.minecraft.util.Mth.rotLerp(partialTick, owner.yRotO, owner.getYRot());
        var ownerPosition = new net.minecraft.world.phys.Vec3(
                net.minecraft.util.Mth.lerp(partialTick, owner.xOld, owner.getX()),
                net.minecraft.util.Mth.lerp(partialTick, owner.yOld, owner.getY()),
                net.minecraft.util.Mth.lerp(partialTick, owner.zOld, owner.getZ()));
        var desired = com.chronomancy.temporal.chronodouble.ChronoDoubleGeometry.anchor(
                ownerPosition, yaw, entity.isLeftSide());
        // LevelRenderer positions entities from xOld/yOld/zOld, NOT xo/yo/zo.
        var renderedAnchor = new net.minecraft.world.phys.Vec3(
                net.minecraft.util.Mth.lerp(partialTick, entity.xOld, entity.getX()),
                net.minecraft.util.Mth.lerp(partialTick, entity.yOld, entity.getY()),
                net.minecraft.util.Mth.lerp(partialTick, entity.zOld, entity.getZ()));
        var correction = desired.subtract(renderedAnchor);
        poseStack.pushPose();
        poseStack.translate(correction.x, correction.y, correction.z);
        ChronoDoubleTint.begin();
        try {
            playerRenderer.render(owner, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        } finally {
            ChronoDoubleTint.end();
            poseStack.popPose();
        }
        // No super.render(): the double is a light-echo and has no shadow of its own.
    }

    @Override
    public ResourceLocation getTextureLocation(ChronoDoubleEntity entity) {
        // The owner's own PlayerRenderer binds the real skin; this renderer never binds a texture.
        return null;
    }
}
