package com.chronomancy.mixin;

import com.chronomancy.effect.TemporalStasisEvents;
import com.chronomancy.registry.ChronoMobEffectRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class TemporalStasisServerLevelMixin {

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

        /*
         * ServerLevel — внешние часы для замороженной сущности: она сама не
         * тикает (тик отменяется ниже), поэтому дедлайн проверяем здесь, ДО
         * ci.cancel(). Если время вышло — снимаем эффект и НЕ отменяем этот
         * тик: сущность оттикается штатно, и единый release-флоу отработает
         * через EntityTickEvent.Post (тот же путь, что и break по Damage Cap).
         */
        if (TemporalStasisEvents.expireStasisIfNeeded(
                entity,
                ((ServerLevel) (Object) this).getGameTime()
        )) {
            return;
        }

        entity.setDeltaMovement(Vec3.ZERO);
        entity.setOldPosAndRot();

        /*
         * Визуальные частицы стазиса генерируются ИСКЛЮЧИТЕЛЬНО на клиенте
         * (ChronoParticles.onClientTick знает замороженные сущности из
         * TemporalStasisClientState) — сервер не рассылает particle-пакеты.
         */
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

        /*
         * Аналогично tickNonPassenger: дедлайн по мировому времени для
         * замороженного пассажира, чтобы он не остался замороженным навечно.
         */
        if (TemporalStasisEvents.expireStasisIfNeeded(
                passenger,
                ((ServerLevel) (Object) this).getGameTime()
        )) {
            return;
        }

        passenger.setDeltaMovement(Vec3.ZERO);
        passenger.setOldPosAndRot();

        ci.cancel();
    }

    @Unique
    private static boolean chronomancy$isFrozen(
            Entity entity
    ) {
        return entity instanceof LivingEntity living
                && living.hasEffect(
                ChronoMobEffectRegistry.TEMPORAL_STASIS
        );
    }
}
