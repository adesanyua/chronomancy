package com.chronomancy.mixin;

import com.chronomancy.client.ClientWorldStopState;
import com.chronomancy.item.ClockworkKeyAcceleration;
import com.chronomancy.temporal.worldstop.GlobalTimeStopManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Фаза 5 — остановка BlockEntity-тиков для The World Stop (универсальный
 * механизм, а не перебор типов).
 *
 * <p>{@code Level.tickBlockEntities} обходит реестр {@code blockEntityTickers}
 * и вызывает {@code TickingBlockEntity.tick()} для каждого тикающего BE.
 * {@code @Redirect} пропускает ВЫЗОВ {@code tick()}, но НЕ трогает обход
 * списка/итератор — поэтому регистрация/снятие BE остаются нетронутыми, а
 * просто ни один BE (плавильник, раздатчик, воронка, ...) не продвигает своё
 * состояние весь стоп. На возобновлении они тикают как обычно.
 *
 * <p>Крышка {@code Level} общая (ServerLevel и ClientLevel наследуются),
 * поэтому условие двустороннее: сервер смотрит на {@link GlobalTimeStopManager},
 * клиент — на {@link ClientWorldStopState}. Оба синглтона безвредно читаются
 * с «чужой» стороны (на клиенте server-флаг всегда false и наоборот).
 */
@Mixin(Level.class)
public abstract class WorldStopLevelMixin {

    @Redirect(
            method = "tickBlockEntities",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V")
    )
    private void chronomancy$maybeFreezeBlockEntity(TickingBlockEntity blockEntity) {
        if (chronomancy$timeStoppedForBlockEntities()) {
            return;
        }
        ClockworkKeyAcceleration.tick((Level)(Object)this, blockEntity);
    }

    private boolean chronomancy$timeStoppedForBlockEntities() {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return ClientWorldStopState.isActive();
        }
        return GlobalTimeStopManager.isActive();
    }
}
