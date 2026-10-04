package com.chronomancy.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.LivingEntity;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

import java.util.List;

/**
 * GeckoLib-рендерер сущностей, «фазирующих сквозь время» (Rift Maker, Chronomaly):
 * взгляд головы ({@link HeadLook}), светящаяся маска и слой {@link TimePhaseLayer}.
 *
 * <p>Кто рисует кубы кости: основной проход пропускает «выпавшую» часть (её рисует проход
 * растворения), светящаяся маска у неё гаснет на пике, оболочка у неё ярче; в момент глитча
 * часть сдвигается вбок.
 */
public abstract class PhasingGeoRenderer<T extends LivingEntity & GeoAnimatable> extends GeoEntityRenderer<T> {

    private final String[] phaseBones;
    private final float shellAlpha;

    TimePhaseLayer.Pass pass;
    private boolean reRendering;
    private TimePhaseRendering.Phase phase;
    private String phaseBone;
    private Iterable<TimePhaseRendering.Ghost> ghosts = List.of();
    private final float[] savedHead = new float[2];

    protected PhasingGeoRenderer(EntityRendererProvider.Context context, GeoModel<T> model,
                                 String[] phaseBones, float shellAlpha) {
        super(context, model);
        this.phaseBones = phaseBones;
        this.shellAlpha = shellAlpha;
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
        addRenderLayer(new TimePhaseLayer<>(this));
    }

    TimePhaseRendering.Phase currentPhase() {
        return phase;
    }

    Iterable<TimePhaseRendering.Ghost> ghosts() {
        return ghosts;
    }

    float shellAlpha() {
        return shellAlpha;
    }

    /** 0..1: сквозь сущность сейчас проходят снаряды — оболочка рисуется «неуязвимой» палитрой. */
    protected float projectileImmunity(T entity, float partialTick) {
        return 0.0F;
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        if (TimePhaseRendering.available()) {
            phase = TimePhaseRendering.phaseOf(entity, partialTick);
            phaseBone = phase == null ? null : phaseBones[phase.selector() % phaseBones.length];
            ghosts = TimePhaseRendering.track(entity);
        } else {
            phase = null;
            phaseBone = null;
            ghosts = List.of();
        }
        try {
            super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        } finally {
            phase = null;
            phaseBone = null;
            pass = null;
        }
    }

    /** Взгляд головы — только на время отрисовки кости (см. {@link HeadLook}). */
    @Override
    public void renderRecursively(PoseStack poseStack, T animatable, GeoBone bone, RenderType renderType,
                                  MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                                  float partialTick, int packedLight, int packedOverlay, int colour) {
        reRendering = isReRender;
        boolean look = HeadLook.apply(bone, animatable, partialTick, savedHead);
        try {
            super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender,
                    partialTick, packedLight, packedOverlay, colour);
        } finally {
            if (look) {
                HeadLook.restore(bone, savedHead);
            }
        }
    }

    @Override
    public void renderCubesOfBone(PoseStack poseStack, GeoBone bone, VertexConsumer buffer, int packedLight,
                                  int packedOverlay, int colour) {
        TimePhaseRendering.Phase ph = phase;
        boolean phased = ph != null && TimePhaseRendering.inSubtree(bone, phaseBone);
        if (!reRendering) {
            if (phased) {
                return;
            }
        } else if (pass == null) {
            if (phased && ph.progress() > 0.4F) {
                return;
            }
        } else if (pass == TimePhaseLayer.Pass.DISSOLVE) {
            if (!phased) {
                return;
            }
        } else if (pass == TimePhaseLayer.Pass.SHELL && phased) {
            colour = TimePhaseRendering.whiteWithAlpha(Math.max(shellAlpha, 0.35F + 0.65F * ph.progress()));
        }
        if (phased && ph.glitchFrame() && pass != TimePhaseLayer.Pass.GHOST) {
            poseStack.pushPose();
            poseStack.translate(ph.glitchSign() * 0.07F, 0.0F, 0.0F);
            super.renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, colour);
            poseStack.popPose();
            return;
        }
        super.renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, colour);
    }
}
