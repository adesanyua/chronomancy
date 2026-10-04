package com.chronomancy.mixin;

import com.chronomancy.client.TemporalStasisTint;
import net.minecraft.client.renderer.entity.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Медно-песочный tint для предметов, которые держат замороженные мобы.
 *
 * <p>{@link ItemRenderer} рисует предметы не через {@code ModelPart}, а запекёнными
 * {@code BakedQuad}-ами в {@code renderQuadList}: поштучный цвет {@code int i} там
 * инициализируется константой {@code -1} (белый, 0xFFFFFFFF) и перемножается с
 * текстурой. Поэтому основной {@link TemporalStasisModelTintMixin} (цепляющий
 * {@code ModelPart#render}) до held-предметов не достаёт, и их нужно красить
 * отдельно — здесь, в единственной точке, где рождается цвет вершины предмета.
 *
 * <p>Подменяем именно эту константу {@code -1} только пока открыто окно tint'а
 * замороженной сущности ({@link TemporalStasisTint#isActive()}), которое ставится в
 * {@link TemporalStasisRendererFreezeMixin} строго на время {@code render(...)} одной
 * сущности. Значит:
 * <ul>
 *   <li>окрашиваются только предметы в руках у залётанного стазисом моба;</li>
 *   <li>рука/предметы самого игрока, предметы в рамках и on-ground дроп не
 *       трогаются — они рендерятся вне этого окна;</li>
 *   <li>тонированные предметы (зелья, кожа) сохраняют свою краску — ниже по коду
 *       {@code i} перезаписывается из {@code ItemColors}.</li>
 * </ul>
 *
 * <p>Альфа оригинала ({@code 0xFF} из {@code -1}) сохраняется, меняется только RGB.
 */
@Mixin(ItemRenderer.class)
public abstract class TemporalStasisHeldItemTintMixin {

    @ModifyConstant(
            method = "renderQuadList("
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lcom/mojang/blaze3d/vertex/VertexConsumer;"
                    + "Ljava/util/List;"
                    + "Lnet/minecraft/world/item/ItemStack;II)V",
            constant = @Constant(intValue = -1)
    )
    private int chronomancy$applyHeldItemTint(int constant) {
        if (TemporalStasisTint.isActive()) {
            return TemporalStasisTint.apply(constant);
        }

        return constant;
    }
}
