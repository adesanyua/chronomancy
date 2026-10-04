package com.chronomancy.client.entity;

import com.chronomancy.entity.RiftMakerEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * Rift Maker: GeckoLib-модель, светящиеся разломы/глаза ({@code rift_maker_glowmask.png}),
 * взгляд головы и «фазирование сквозь время» (см. {@link PhasingGeoRenderer}).
 */
public final class RiftMakerRenderer extends PhasingGeoRenderer<RiftMakerEntity> {
    /** Части, которые могут «выпасть из времени» (вместе с дочерними костями). */
    private static final String[] PHASE_BONES = {"head", "right_arm", "left_arm", "robe", "halo"};

    public RiftMakerRenderer(EntityRendererProvider.Context context) {
        super(context, new ChronoEntityGeoModel<>("rift_maker", true, true), PHASE_BONES, TimePhaseRendering.SHELL_ALPHA);
        this.shadowRadius = 2.0F;
        withScale(2.0F); // босс вдвое больше исходной модели; хитбокс — 2.2 × 6.4
    }

    /** После самой модели — области его ударов на земле: след песков времени, круги усиленного удара и стазиса. */
    @Override
    public void render(RiftMakerEntity entity, float entityYaw, float partialTick,
                       com.mojang.blaze3d.vertex.PoseStack poseStack,
                       net.minecraft.client.renderer.MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        SandsConeRendering.render(entity, partialTick, poseStack, bufferSource);
        BossGroundFxRendering.render(entity, partialTick, poseStack, bufferSource);
    }

    @Override
    protected float projectileImmunity(RiftMakerEntity entity, float partialTick) {
        return entity.projectileImmunity(partialTick);
    }

    /** Смерть — анимацией распада, без заваливания на бок. */
    @Override
    protected float getDeathMaxRotation(RiftMakerEntity animatable) {
        return 0.0F;
    }
}
