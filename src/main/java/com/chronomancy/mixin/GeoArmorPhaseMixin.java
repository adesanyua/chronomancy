package com.chronomancy.mixin;

import com.chronomancy.client.VanillaPhase;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.GeoArmorRenderer;

/**
 * GeckoLib-броня рисуется не в переданный ей буфер, а в общий {@code RenderBuffers#bufferSource()},
 * поэтому медь, застывшее золото, оболочка и эхо-силуэты сущности её не задевали. Пока идёт
 * проход фазирования ({@link VanillaPhase#activeSource()}), броня рисуется в буфер этого прохода —
 * повторяем {@code renderToBuffer} с подменённым источником.
 */
@Mixin(value = GeoArmorRenderer.class, remap = false)
public abstract class GeoArmorPhaseMixin {

    @Shadow
    protected Item animatable;
    @Shadow
    protected ItemStack currentStack;
    @Shadow
    protected MultiBufferSource bufferSource;

    @Inject(method = "renderToBuffer", at = @At("HEAD"), cancellable = true)
    private void chronomancy$phaseArmor(PoseStack poseStack, VertexConsumer ignored, int packedLight, int packedOverlay,
                                        int color, CallbackInfo ci) {
        MultiBufferSource source = VanillaPhase.activeSource();
        if (source == null || this.animatable == null) {
            return;
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        GeoArmorRenderer self = (GeoArmorRenderer) (Object) this;
        Item item = this.animatable;
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        RenderType renderType = self.getRenderType(item, self.getTextureLocation((GeoAnimatable) item), source, partialTick);
        VertexConsumer buffer = ItemRenderer.getArmorFoilBuffer(source, renderType,
                this.currentStack != null && this.currentStack.hasFoil());
        this.bufferSource = source;
        self.defaultRender(poseStack, (GeoAnimatable) item, source, null, buffer, 0.0F, partialTick, packedLight);
        this.animatable = null;
        ci.cancel();
    }

}
