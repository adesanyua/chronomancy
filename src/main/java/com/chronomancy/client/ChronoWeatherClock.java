package com.chronomancy.client;

import com.chronomancy.temporal.TemporalDilationHandler;
import com.chronomancy.temporal.TemporalRate;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * «Разреженное» время погоды для поля Time Dilation.
 *
 * <p>Дождь/снег в 1.21.1 — не сущности и не обычные частицы: шторка капель
 * рисуется {@code LevelRenderer.renderSnowAndRain} целиком по фазе от
 * {@code LevelRenderer.ticks}, а всплески о землю спавнит {@code tickRain}.
 * Единственный честный способ замедлить их — подменить источник времени:
 * каждый клиентский тик накапливаем {@code clock += rate}, где rate — темп
 * дилейшена в точке КАМЕРЫ (шторка рисуется только в ±10 блоках вокруг камеры,
 * а радиус поля ≤ 8, так что «камера под куполом» == «вся видимая шторка под
 * куполом»). Наружу поля часы синхронизируются с {@code ticks} — дрифта и
 * артефактов вне поля не бывает вообще.
 *
 * <p>Накопление (а не умножение {@code ticks * rate}) даёт НЕПРЕРЫВНОСТЬ: при
 * входе/выходе из купола меняется только скорость фазы, но не сама фаза —
 * капли не телепортируются, только «густеют».
 */
public final class ChronoWeatherClock {

    private static float clock;
    private static double rate = TemporalRate.NORMAL;

    private ChronoWeatherClock() {
    }

    /** Хук {@code ClientTickEvent.Post}: один раз за кадр-тик пересчитать часы. */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            rate = TemporalRate.NORMAL;
            return;
        }
        // The World Stop: время стоит целиком — замораживаем фазу шторки
        // (часы НЕ продвигаются и НЕ ресинхронизируются с ticks). Вкупе с
        // обнулением partialTicks в {@code WorldStopWeatherMixin} даёт статичные
        // капли вместо продолжающегося дождя.
        if (ClientWorldStopState.isActive()) {
            rate = 0.0;
            return;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        rate = TemporalDilationHandler.clientRateAt(mc.level, cam);
        if (TemporalRate.isEffectivelyNormal(rate)) {
            // Снаружи поля — точная синхронизация (самолечащийся дрифт).
            clock = mc.levelRenderer.getTicks();
        } else {
            clock += (float) rate;
        }
    }

    /** Разреженные «тики» для фазы шторки дождя/снега (замена LevelRenderer.ticks). */
    public static int weatherTicks() {
        return (int) clock;
    }

    /** Текущий темп у камеры (1.0 вне поля) — для частоты всплесков о землю. */
    public static double currentRate() {
        return rate;
    }
}
