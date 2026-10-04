package com.chronomancy.mixin;

import com.chronomancy.client.WorldStopPostProcess;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * THE WORLD STOP — единственная точка входа пост-обработки в рендер-пайплайн.
 *
 * <p>Вставляет {@link WorldStopPostProcess#onRender} в {@code GameRenderer#render}
 * сразу после {@code LevelRenderer#doEntityOutline()} — ровно туда, где ваниль
 * сама прогоняет экранные эффекты (creeper/spider/enderman): весь мир уже в
 * {@code minecraft:main}, HUD ещё не рисуется. Сам шейдер-стек — ванильный
 * {@code PostChain}, без Iris.
 *
 * <p>{@code doEntityOutline()} вызывается ровно один раз внутри ветки
 * {@code if (flag && renderLevel && level != null)}, поэтому {@code INVOKE ...
 * shift=AFTER} однозначен и не срабатывает в главном меню/на экране загрузки.
 */
@Mixin(GameRenderer.class)
public abstract class WorldStopPostEffectMixin {

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;doEntityOutline()V",
                    shift = At.Shift.AFTER
            )
    )
    private void chronomancy$worldStopPostProcess(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        // render(DeltaTracker, boolean) — обработчик @Inject обязан принимать ВСЕ
        // аргументы цели + CallbackInfo; ветка renderLevel==true гарантирована
        // местом инжекта (doEntityOutline вызывается только внутри неё).
        WorldStopPostProcess.onRender(deltaTracker);
    }
}
