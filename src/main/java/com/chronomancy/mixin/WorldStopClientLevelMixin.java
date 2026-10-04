package com.chronomancy.mixin;

import com.chronomancy.client.ClientWorldStopState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Фаза 6-фикс — КЛИЕНТСКАЯ заморозка сущностей The World Stop без джиттера.
 *
 * <p>Прежняя реализация отменяла {@code EntityTickEvent.Pre}, но это событие
 * fires уже ПОСЛЕ {@code entity.setOldPosAndRot()} и {@code entity.tickCount++}
 * в {@link ClientLevel#tickNonPassenger(Entity)} (см. исходники 1.21.1: L297-301).
 * Для неподвижных мобов этого хватает, но у движущегося моба {@code tickCount}
 * продолжал расти, а остаточная {@code deltaMovement}/{partialTicks}-интерполяция
 * шага давали видимый джиттер на клиенте.
 *
 * <p>Ровно ту же проблему уже решает рабочий механизм Temporal Stasis
 * ({@link TemporalStasisClientLevelMixin}): отмена тика НА HEAD — ДО
 * {@code setOldPosAndRot}/{@code tickCount++}, с собственным вызовом
 * {@code setOldPosAndRot()} (пин xo/yo/zo и yRotO/yHeadRot → кадр зафиксирован,
 * интерполяция даёт ту же точку) и обнулением {@code deltaMovement}. Здесь то же
 * правило, но по ГЛОБАЛЬНОМУ состоянию стопа и с исключением единственного
 * entity id кастера (он продолжает тикать/двигаться/смотреть).
 *
 * <p>Пассажиры ({@code tickPassenger}) не покрыты {@code EntityTickEvent.Pre}
 * в принципе — этот миксин закрывает и их.
 */
@Mixin(ClientLevel.class)
public abstract class WorldStopClientLevelMixin {

    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void chronomancy$freezeEntity(Entity entity, CallbackInfo ci) {
        if (chronomancy$shouldFreeze(entity)) {
            entity.setDeltaMovement(Vec3.ZERO);
            entity.setOldPosAndRot();
            ci.cancel();
        }
    }

    @Inject(method = "tickPassenger", at = @At("HEAD"), cancellable = true)
    private void chronomancy$freezePassenger(Entity vehicle, Entity rider, CallbackInfo ci) {
        if (chronomancy$shouldFreeze(rider)) {
            rider.setDeltaMovement(Vec3.ZERO);
            rider.setOldPosAndRot();
            ci.cancel();
        }
    }

    private static boolean chronomancy$shouldFreeze(Entity entity) {
        // Исключения — кастер (и наш LocalPlayer, если кастовали мы) и сущности вне
        // времени (Chronomaly, Time Rift).
        return ClientWorldStopState.isFrozen(entity);
    }
}
