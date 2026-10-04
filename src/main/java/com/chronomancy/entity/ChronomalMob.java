package com.chronomancy.entity;

import net.minecraft.world.entity.Entity;

/**
 * Хрономальные мобы — порождения разорванного времени: Chronomaly, Rift Maker и фазирующие
 * зомби/скелеты/криперы. Общее: иммунитет к магии времени, одна «команда», уклонение от снарядов
 * рывком сквозь время ({@code PhaseDodge}).
 */
public interface ChronomalMob {

    static boolean is(Entity entity) {
        return entity instanceof ChronomalMob || entity instanceof ChronomalyEntity || entity instanceof RiftMakerEntity;
    }
}
