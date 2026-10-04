package com.chronomancy.mixin;

import com.chronomancy.client.TemporalStasisClientState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Косметика рендера для замороженной сущности на уровне диспетчера.
 *
 * <p>Сама заморозка анимации модели выполняется в
 * {@link TemporalStasisRendererFreezeMixin} (фиксация {@code partialTicks} в
 * {@code LivingEntityRenderer#render}). Здесь остаётся только скрытие огня:
 * {@code displayFireAnimation()} вызывается именно в {@code EntityRenderDispatcher},
 * поэтому перехватываем его тут.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class TemporalStasisRenderMixin {

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;displayFireAnimation()Z"
            )
    )
    private boolean chronomancy$hideFire(
            Entity entity
    ) {
        if (chronomancy$isFrozen(entity)) {
            return false;
        }

        return entity.displayFireAnimation();
    }

    @Unique
    private static boolean chronomancy$isFrozen(
            Entity entity
    ) {
        return TemporalStasisClientState.isFrozen(entity.getId());
    }
}
