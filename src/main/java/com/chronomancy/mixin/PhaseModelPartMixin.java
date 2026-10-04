package com.chronomancy.mixin;

import com.chronomancy.client.VanillaPhase;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** «Выпадение» части модели у фазирующих копий/двойников (см. {@link VanillaPhase}). */
@Mixin(ModelPart.class)
public abstract class PhaseModelPartMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
            at = @At("HEAD"), cancellable = true)
    private void chronomancy$phaseHead(PoseStack poseStack, VertexConsumer buffer, int light, int overlay, int color,
                                       CallbackInfo ci) {
        if (VanillaPhase.onPartHead((ModelPart) (Object) this, poseStack, light, overlay)) {
            ci.cancel();
        }
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
            at = @At("RETURN"))
    private void chronomancy$phaseReturn(PoseStack poseStack, VertexConsumer buffer, int light, int overlay, int color,
                                         CallbackInfo ci) {
        VanillaPhase.onPartReturn();
    }
}
