package com.chronomancy.mixin;

import com.chronomancy.registry.ChronoMobEffectRegistry;
import com.chronomancy.temporal.TemporalDilationHandler;
import com.chronomancy.temporal.TemporalRate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Серверное замедление «личного времени» сущностей внутри Time Dilation Field.
 *
 * <p>Работает ТОЛЬКО через пропуск тика по дробному накоплению прогресса
 * ({@link TemporalDilationHandler#shouldSkipTick}), а не через обнуление скорости
 * или MOVEMENT_SPEED. Моб продолжает двигаться, AI работает, анимации идут — просто
 * реже. Снаряд летит медленнее, но его исходная velocity/gravity не трогаются,
 * поэтому на выходе из поля он продолжает с нормальной скоростью (без затухания).
 *
 * <p>Стазис имеет приоритет: его миксин отменяет тик полностью; здесь стоят guards
 * {@code ci.isCancelled()} и {@link TemporalRate} (замороженные возвращают NORMAL).
 */
@Mixin(ServerLevel.class)
public abstract class TemporalDilationServerLevelMixin {

    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void chronomancy$dilateEntity(Entity entity, CallbackInfo ci) {
        if (ci.isCancelled()) {
            return;
        }
        chronomancy$maybeSkip((ServerLevel) (Object) this, entity, ci);
    }

    @Inject(method = "tickPassenger", at = @At("HEAD"), cancellable = true)
    private void chronomancy$dilatePassenger(Entity vehicle, Entity passenger, CallbackInfo ci) {
        if (ci.isCancelled()) {
            return;
        }
        chronomancy$maybeSkip((ServerLevel) (Object) this, passenger, ci);
    }

    /**
     * Ускорение (темп больше 1: Accelerated Zone, парадокс): после обычного тика сущность получает
     * дополнительные — тем же путём, что и обычный, со всеми событиями и пассажирами. Игроков это не
     * касается (у них атрибуты скорости); замороженную или пропустившую тик сущность тоже — до
     * конца метода она не доходит.
     */
    @Inject(method = "tickNonPassenger", at = @At("TAIL"))
    private void chronomancy$accelerateEntity(Entity entity, CallbackInfo ci) {
        if (TemporalDilationHandler.isAccelerating() || entity instanceof Player || entity.isRemoved()) {
            return;
        }
        ServerLevel level = (ServerLevel) (Object) this;
        double rate = TemporalDilationHandler.resolveRate(level, entity);
        int extra = TemporalDilationHandler.extraTicks(entity, rate);
        if (extra <= 0) {
            return;
        }
        TemporalDilationHandler.setAccelerating(true);
        try {
            for (int i = 0; i < extra && !entity.isRemoved(); i++) {
                level.tickNonPassenger(entity);
            }
        } finally {
            TemporalDilationHandler.setAccelerating(false);
        }
    }

    private void chronomancy$maybeSkip(ServerLevel level, Entity entity, CallbackInfo ci) {
        // Player packets and input have their own clock path; do not skip full ServerPlayer ticks.
        if (entity instanceof Player) return;
        // Приоритет у стазиса: полностью замороженную сущность не трогаем вообще,
        // чтобы не отменить её expire-проверку в стазис-миксине (порядок миксинов
        // на одном HEAD не гарантирован).
        if (entity instanceof Mob mob
                && mob.hasEffect(ChronoMobEffectRegistry.TEMPORAL_STASIS)) {
            return;
        }
        double rate = TemporalDilationHandler.resolveRate(level, entity);
        if (TemporalRate.isEffectivelyNormal(rate)) {
            return;
        }
        if (TemporalDilationHandler.shouldSkipTick(entity, rate)) {
            ci.cancel();
        }
    }
}
