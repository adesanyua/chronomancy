package com.chronomancy.entity;

/**
 * Моб, умеющий колдовать заклинания хрономантии, у которых «игроцкая» механика завязана на игрока
 * (история состояния, личное время, перемотка тела). Заклинание в {@code onCast} отдаёт таким
 * кастерам управление сюда — моб исполняет свою версию.
 */
public interface ChronoMobCaster {

    /** Rewind: вернуться на несколько секунд назад — позиция и здоровье. */
    void mobRewind(int spellLevel);

    /** Borrowed Future: ускориться сейчас, расплатиться замедлением потом. */
    void mobBorrowedFuture(int spellLevel);

    /** Time Walk: шаг сквозь время (к цели или от неё) и короткая фаза от снарядов. */
    void mobTimeWalk(int spellLevel);
}
