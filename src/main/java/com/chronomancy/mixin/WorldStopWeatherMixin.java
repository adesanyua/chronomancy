package com.chronomancy.mixin;

import com.chronomancy.client.ClientWorldStopState;
import com.chronomancy.client.ChronoWeatherClock;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Фаза 6-фикс — дождь/снег не идёт на клиенте во время The World Stop.
 *
 * <p>Шторка осадков в 1.21.1 рисуется {@code LevelRenderer#renderSnowAndRain}
 * как {@code фаза = this.ticks + partialTicks}. {@code this.ticks} уже подменён
 * на разреженные {@link ChronoWeatherClock#weatherTicks()} (см.
 * {@link TemporalDilationWeatherMixin}), а те во время стопа заморожены
 * (часы не продвигаются). Остаётся лишь {@code partialTicks} — дробная часть
 * КАДРА, которая меняется каждый кадр и без обнуления давала бы мерцание/дрейф
 * капель на величину до 1 блока. Обнуляем её, пока стоп активен, — капли
 * замирают на месте.
 *
 * <p>Земляные всплески ({@code tickRain}) отдельной правки не требуют: их число
 * уже умножается на {@link ChronoWeatherClock#currentRate()} (= 0 во время
 * стопа), а ранее рождённые частицы дождя замораживает
 * {@link WorldStopParticleFreezeMixin} (не темпоральные → не тикают).
 */
@Mixin(LevelRenderer.class)
public abstract class WorldStopWeatherMixin {

    @ModifyVariable(
            method = "renderSnowAndRain",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private float chronomancy$freezeRainPartialTick(float partialTick) {
        return ClientWorldStopState.isActive() ? 0.0F : partialTick;
    }
}
