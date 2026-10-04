package com.chronomancy.mixin;

import com.chronomancy.client.TemporalStasisTint;
import com.chronomancy.client.BorrowedFutureTint;
import com.chronomancy.client.NeedleStackTint;
import com.chronomancy.client.ChronoDoubleTint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import net.minecraft.client.model.geom.ModelPart;

/**
 * Универсальный медно-песочный tint замороженной сущности.
 *
 * <p>{@code ModelPart#render(PoseStack, VertexConsumer, int, int, int)} — единственная
 * точка, через которую проходят ВСЕ части модели: базовое тело, броня, элитры и
 * любые дополнительные {@code RenderLayer} (каждый слой в итоге рисует свои
 * {@code ModelPart}). Последний int-аргумент {@code render} — это packed-цвет
 * (0xAARRGGBB), который перемножается с текстурой на вершинах. Подменяя только его
 * RGB-каналы (альфу сохраняем), мы получаем tint, не трогая саму текстуру и
 * {@code ResourceLocation}.
 *
 * <p>Применяется лишь пока {@link TemporalStasisTint#isActive()} открыто окном
 * отрисовки замороженной сущности (см. {@link TemporalStasisRendererFreezeMixin}),
 * поэтому для обычных сущностей цвет не меняется, а после frozen=false оригинальный
 * вид возвращается сам собой.
 *
 * <p>Среди int-аргументов цели: ordinal 0 = packedLight, 1 = packedOverlay,
 * 2 = color.
 */
@Mixin(ModelPart.class)
public abstract class TemporalStasisModelTintMixin {

    @ModifyVariable(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 2
    )
    private int chronomancy$applyStasisTint(int color) {
        if (ChronoDoubleTint.active()) {
            return ChronoDoubleTint.apply(color);
        }

        if (com.chronomancy.client.RiftEchoClient.tintActive()) {
            return com.chronomancy.client.RiftEchoClient.apply(color);
        }

        if (TemporalStasisTint.isActive()) {
            return TemporalStasisTint.apply(color);
        }

        if (BorrowedFutureTint.active()) {
            return BorrowedFutureTint.apply(color);
        }

        if (NeedleStackTint.active()) {
            return NeedleStackTint.apply(color);
        }

        return color;
    }
}
