package com.chronomancy.mixin;

import com.chronomancy.client.CopperEchoRendering;
import com.chronomancy.client.RiftEchoClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Медные копии Rift (и фазирование копий/двойников, см. {@link com.chronomancy.client.VanillaPhase}): вызов {@code EntityRenderer#render} для копии получает буфер,
 * рисующий все entity-слои медным шейдером ({@link CopperEchoRendering}).
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class CopperEchoDispatchMixin {

    @WrapOperation(
            method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    )
    private <E extends Entity> void chronomancy$copperEcho(EntityRenderer<? super E> renderer, E entity, float yaw,
                                                           float partialTick, PoseStack poseStack,
                                                           MultiBufferSource buffer, int packedLight,
                                                           Operation<Void> original) {
        boolean copper = RiftEchoClient.isCopper(entity);
        com.chronomancy.client.VanillaPhase.Profile profile = com.chronomancy.client.VanillaPhase.profileOf(entity);
        if (profile != null) {
            // медь/стазис + «фазирование сквозь время»: несколько проходов одного рендера
            com.chronomancy.client.VanillaPhase.render(entity, partialTick, poseStack, buffer, profile,
                    source -> com.chronomancy.client.VanillaPhase.withSource(source,
                            () -> original.call(renderer, entity, yaw, partialTick, poseStack, source, packedLight)));
            return;
        }
        // эффекты заклинаний, сжатых Timeless Book: модель — медным шейдером, молнии и лучи — медью по цвету
        // цель под струёй Sands of Time медна настолько, насколько её уже занесло песком
        float sand = copper ? 0.0F : com.chronomancy.client.SandStacksClient.strength(entity);
        MultiBufferSource source = copper ? CopperEchoRendering.wrap(buffer)
                : com.chronomancy.client.SpellCopperClient.isCopper(entity)
                ? com.chronomancy.client.SpellCopperClient.wrap(buffer)
                : sand > 0.0F ? CopperEchoRendering.wrap(buffer, sand) : buffer;
        com.chronomancy.client.VanillaPhase.withSource(copper ? source : null,
                () -> original.call(renderer, entity, yaw, partialTick, poseStack, source, packedLight));
    }
}
