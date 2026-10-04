package com.chronomancy.mixin;

import com.chronomancy.client.TemporalVolumePostProcess;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TIME DILATION FIELD — точка входа volume post-process в рендер-пайплайн.
 *
 * <p>Тот же слот, что у World Stop ({@code GameRenderer#render} сразу после
 * {@code LevelRenderer#doEntityOutline()}): весь мир + рука уже в
 * {@code minecraft:main}, HUD не рисовался, depth-текстура {@code main} валидна.
 * Отдельный миксин (не «в один hack» с World Stop) — эффекты архитектурно
 * разделены; приоритет World Stop обеспечивается проверкой внутри
 * {@link TemporalVolumePostProcess#onRender}.
 *
 * <p>Обработчик {@code @Inject} обязан принимать ВСЕ аргументы цели
 * ({@code DeltaTracker}, {@code boolean renderLevel}) + {@code CallbackInfo}.
 */
@Mixin(GameRenderer.class)
public abstract class TimeDilationPostEffectMixin {

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;doEntityOutline()V",
                    shift = At.Shift.AFTER
            )
    )
    private void chronomancy$timeDilationPostProcess(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        TemporalVolumePostProcess.onRender(deltaTracker);
        // зона ускорения и вспышка парадокса — отдельная цепочка поверх купола поля
        com.chronomancy.client.AccelZonePostProcess.onRender(deltaTracker);
    }
}
