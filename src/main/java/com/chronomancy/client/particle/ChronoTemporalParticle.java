package com.chronomancy.client.particle;

/**
 * Маркер «темпоральных» частиц Chronomancy (маны стазиса/перемотки).
 *
 * <p>Нужен для {@code WorldStopParticleFreezeMixin}: во время The World Stop все
 * обычные частицы замерзают, а темпоральные обязаны продолжать жить и мерцать
 * (золотой «обратный отсчёт» вокруг замороженного моба — часть фидбека стопа).
 */
public interface ChronoTemporalParticle {
}
