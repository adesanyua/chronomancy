package com.chronomancy.mixin;

import com.chronomancy.temporal.TemporalDilationHandler;
import com.chronomancy.temporal.TemporalRate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import com.chronomancy.client.TemporalStasisClientState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Плавное клиентское замедление Time Dilation.
 *
 * <p>Прямой пропуск клиентских тиков (как делает сервер) даёт дёрганье: тик
 * отменён — {@code tickCount} и старая позиция не обновляются, анимация и
 * интерполяция «замирают», а на разрешённом тике сущность прыгает. Вместо этого
 * клиент тикает КАЖДЫЙ тик, но само перемещение масштабируется temporal rate —
 * то есть сущность проходит лишь долю обычного расстояния за тик. Это даёт
 * непрерывное, плавное замедление и для мобов, и для снарядов.
 *
 * <p>Масштабируется именно вектор {@code move}, а НЕ {@code deltaMovement}:
 * исходная скорость снаряда не умножается каждый тик, поэтому нет экспоненциального
 * затухания, а на выходе из поля движение сразу возвращается к нормальному.
 *
 * <p>Хук на {@link Entity#move} — общий путь ВСЕГО перемещения (ход мобов через
 * travel, полёт снарядов, гравитация), поэтому решение универсально и не привязано
 * к конкретным классам. Серверная сторона не затрагивается (там — пропуск тиков).
 */
@Mixin(Entity.class)
public abstract class TemporalDilationClientMoveMixin {

    @ModifyVariable(
            method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    private Vec3 chronomancy$dilateMovement(Vec3 pos) {
        Entity self = (Entity) (Object) this;
        if (self instanceof Player && TemporalStasisClientState.isFrozen(self.getId())) return Vec3.ZERO;
        if (self instanceof Player) return pos;
        double scale = TemporalDilationHandler.clientMoveScale(self);
        // и замедление (поле), и ускорение (Accelerated Zone): сервер тикает сущность реже или чаще
        if (Math.abs(scale - TemporalRate.NORMAL) < 1.0E-4) {
            return pos;
        }
        return pos.scale(scale);
    }
}
