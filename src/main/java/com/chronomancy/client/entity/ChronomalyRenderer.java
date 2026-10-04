package com.chronomancy.client.entity;

import com.chronomancy.entity.ChronomalyEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * Chronomaly: GeckoLib-модель + светящиеся ядро/глаз ({@code chronomaly_glowmask.png}), взгляд головы
 * и «фазирование сквозь время», как у Rift Maker (см. {@link PhasingGeoRenderer}).
 */
public final class ChronomalyRenderer extends PhasingGeoRenderer<ChronomalyEntity> {
    private static final String[] PHASE_BONES = {"head", "right_arm", "left_arm", "tail", "orbit"};

    public ChronomalyRenderer(EntityRendererProvider.Context context) {
        super(context, new ChronoEntityGeoModel<>("chronomaly", true, true), PHASE_BONES, 0.5F);
        this.shadowRadius = 0.45F;
    }

    /** Сразу после телепорта модель скрыта на время клиентской интерполяции — «мгновенный» прыжок. */
    @Override
    public void render(ChronomalyEntity entity, float entityYaw, float partialTick,
                       com.mojang.blaze3d.vertex.PoseStack poseStack,
                       net.minecraft.client.renderer.MultiBufferSource bufferSource, int packedLight) {
        if (entity.isHiddenForBlink()) {
            return;
        }
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    /** Смерть проигрывается анимацией распада — без ванильного заваливания на бок. */
    @Override
    protected float getDeathMaxRotation(ChronomalyEntity animatable) {
        return 0.0F;
    }
}
