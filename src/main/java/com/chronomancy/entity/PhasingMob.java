package com.chronomancy.entity;

/** Фазирующий моб разлома (зомби/скелет/крипер). */
public interface PhasingMob extends ChronomalMob {
    /** Вызывается разломом ДО {@code addFreshEntity}: моб участвует в испытании и распадается вне боя. */
    void markFromRift();
}
