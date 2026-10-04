package com.chronomancy.mixin;

import com.chronomancy.client.TemporalStasisClientState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Заморозка клиентского тика моба в стазисе.
 *
 * <p>Клиент сам тикает мобов ({@code tickCount++}, интерполяция позиции). Если
 * этого не делать, моб «дышит» и скользит даже при обнулённом partialTicks.
 * Отменяя {@code tickNonPassenger}/{@code tickPassenger} для замороженных сущностей,
 * мы фиксируем кадр. Решение о заморозке приходит с сервера через пакет
 * {@code StasisSyncPayload} в {@link TemporalStasisClientState}.
 */
@Mixin(ClientLevel.class)
public abstract class TemporalStasisClientLevelMixin {

    @Inject(
            method = "tickNonPassenger",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chronomancy$freezeEntity(
            Entity entity,
            CallbackInfo ci
    ) {
        if (!chronomancy$isFrozen(entity)) {
            return;
        }

        entity.setDeltaMovement(Vec3.ZERO);
        entity.setOldPosAndRot();
        ci.cancel();
    }

    @Inject(
            method = "tickPassenger",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chronomancy$freezePassenger(
            Entity vehicle,
            Entity passenger,
            CallbackInfo ci
    ) {
        if (!chronomancy$isFrozen(passenger)) {
            return;
        }

        passenger.setDeltaMovement(Vec3.ZERO);
        passenger.setOldPosAndRot();
        ci.cancel();
    }

    @Unique
    private static boolean chronomancy$isFrozen(Entity entity) {
        return (entity instanceof Mob || entity instanceof Player && entity != Minecraft.getInstance().player)
                && TemporalStasisClientState.isFrozen(entity.getId());
    }
}
