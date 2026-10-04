package com.chronomancy.client;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.temporal.borrowed.BorrowedFuturePhase;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** Copper-gold translucent shell, confined to third-person player rendering. */
public final class BorrowedFutureVisualRenderer {
    private static final ResourceLocation SHELL = ResourceLocation.fromNamespaceAndPath(
            ChronomancyMod.MODID, "textures/stasis_white.png");
    private BorrowedFutureVisualRenderer() {}

    /** Called before LivingEntityRenderer restores its pose, so the shell inherits the player's rotation and model offset. */
    public static void render(AbstractClientPlayer player, PlayerRenderer renderer, float partialTick,
                              PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        if (BorrowedFutureClientState.phase(player.getId()) != BorrowedFuturePhase.EMPOWERED) return;
        // Шейдерная оболочка фазы (VanillaPhase) заменяет старую полупрозрачную скорлупу.
        if (com.chronomancy.client.entity.TimePhaseRendering.available()) return;
        Minecraft mc = Minecraft.getInstance();
        if (player == mc.player && mc.options.getCameraType().isFirstPerson()) return;
        float pulse = (float) (0.5 + 0.5 * Math.sin((player.tickCount + partialTick) * 0.55));
        int alpha = Math.round(48 + 22 * pulse);
        int color = (alpha << 24) | 0xD0AC58;
        poseStack.pushPose();
        poseStack.scale(1.07f + pulse * 0.015f, 1.025f, 1.07f + pulse * 0.015f);
        renderer.getModel().renderToBuffer(poseStack,
                buffers.getBuffer(RenderType.entityTranslucent(SHELL)),
                packedLight, OverlayTexture.NO_OVERLAY, color);
        poseStack.popPose();
    }
}
