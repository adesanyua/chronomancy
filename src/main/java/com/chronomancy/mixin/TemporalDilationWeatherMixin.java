package com.chronomancy.mixin;

import com.chronomancy.client.ChronoWeatherClock;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Замедление погоды внутри поля Time Dilation (клиент).
 *
 * <p>Шторка дождя/снега и всплески о землю в 1.21.1 полностью завязаны на
 * {@code LevelRenderer.ticks}. Подменяем чтение этого счётчика на разреженные
 * часы {@link ChronoWeatherClock}: пока камера под куполом, фаза капель идёт
 * медленнее; вне поля часы равны {@code ticks} — ванильное поведение один в один.
 *
 * <p>Ординалы проверены по декомпилированным исходникам 1.21.1:
 * <ul>
 *   <li>{@code renderSnowAndRain}: {@code this.ticks} читается ровно 3 раза —
 *       L288 {@code f1} (дрейф снежинок), L331 {@code i3} (фаза полос дождя),
 *       L377 {@code f8} (фаза снежинок);</li>
 *   <li>{@code tickRain}: одно чтение — сид спавна всплесков (L435); первый
 *       int-локал {@code i} — число капель за тик (L439).</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public class TemporalDilationWeatherMixin {

    // === шторка дождя/снега: все три чтения ticks -> разреженные часы ===

    @Redirect(method = "renderSnowAndRain",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;ticks:I",
                    ordinal = 0))
    private int chronomancy$dilatedTicksSnowDrift(LevelRenderer renderer) {
        return ChronoWeatherClock.weatherTicks();
    }

    @Redirect(method = "renderSnowAndRain",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;ticks:I",
                    ordinal = 1))
    private int chronomancy$dilatedTicksRainPhase(LevelRenderer renderer) {
        return ChronoWeatherClock.weatherTicks();
    }

    @Redirect(method = "renderSnowAndRain",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;ticks:I",
                    ordinal = 2))
    private int chronomancy$dilatedTicksSnowPhase(LevelRenderer renderer) {
        return ChronoWeatherClock.weatherTicks();
    }

    // === всплески о землю: редеют и «оживают» медленнее под куполом ===

    @Redirect(method = "tickRain",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;ticks:I"))
    private int chronomancy$dilatedSplashSeed(LevelRenderer renderer) {
        return ChronoWeatherClock.weatherTicks();
    }

    /** Число капель-всплесков за тик: под куполом — пропорционально темпу поля. */
    @ModifyVariable(method = "tickRain", at = @At("STORE"), ordinal = 0)
    private int chronomancy$slowSplashCount(int count) {
        return (int) (count * ChronoWeatherClock.currentRate());
    }
}
