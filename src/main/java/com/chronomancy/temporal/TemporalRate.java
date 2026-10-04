package com.chronomancy.temporal;

/**
 * Общая абстракция «личного темпа времени» сущности.
 *
 * <p>Шкала temporal rate сквозная для всей школы Chronomancy:
 * <ul>
 *     <li>{@code 1.0} — нормальный ход времени (никакого эффекта);</li>
 *     <li>{@code 0.6} — базовый Time Dilation (замедление 40%);</li>
 *     <li>{@code 0.0} — полная остановка (Temporal Stasis / будущая Chronosphere).</li>
 * </ul>
 *
 * <p>Time Dilation НИКОГДА не доходит до {@code 0}: у неё есть жёсткий пол
 * {@link #MIN_DILATION_RATE}, чтобы замедление никогда не превращалось в стазис.
 * Стазис — это отдельный механизм (полная отмена тика), а не «очень низкий rate».
 */
public final class TemporalRate {

    private TemporalRate() {
    }

    /** Нормальный темп времени. */
    public static final double NORMAL = 1.0;

    /** Базовый темп Time Dilation до усиления (60%). */
    public static final double BASE_DILATION = 0.6;

    /**
     * Жёсткий нижний пол темпа для Time Dilation (максимальное замедление 85%).
     * Никогда не даёт rate = 0, то есть никогда не становится стазисом.
     */
    public static final double MIN_DILATION_RATE = 0.15;

    /** Максимальное замедление (доля остановки времени), соответствует {@link #MIN_DILATION_RATE}. */
    public static final double MAX_DILATION_SLOW = 1.0 - MIN_DILATION_RATE;

    /** Потолок ускорения (Accelerated Zone, парадокс): темп не выше +85% — зеркально полу замедления. */
    public static final double MAX_ACCELERATION_RATE = NORMAL + MAX_DILATION_SLOW;

    /**
     * Ограничивает произвольное значение темпа в допустимый диапазон
     * [{@link #MIN_DILATION_RATE}, {@link #NORMAL}] для замедляющих эффектов.
     */
    public static double clampDilation(double rate) {
        if (rate > NORMAL) {
            return NORMAL;
        }
        return Math.max(rate, MIN_DILATION_RATE);
    }

    /**
     * Проверяет, «заметен» ли темп (близок к нормальному) — тогда замедления нет.
     */
    public static boolean isEffectivelyNormal(double rate) {
        return rate >= NORMAL - 1.0E-4;
    }
}
