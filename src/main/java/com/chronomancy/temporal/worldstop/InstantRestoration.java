package com.chronomancy.temporal.worldstop;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * Мгновенное восстановление в остановленном времени.
 *
 * <p>Биологическое время кастера The World Stop стоит: здоровье каждый тик возвращается к слепку
 * ({@link CasterPersonalTime}), а любой рост маны отменяется — так гасится пассивная регенерация.
 * Но зелья мгновенного действия — не «время», а вещество: ванильное Мгновенное лечение (бутылка,
 * взрывное, туманное, эликсиры Iron's Spells) и Мгновенная мана Iron's Spells должны работать и
 * посреди остановки, иначе в долгом испытании разлома кастеру нечем восстановиться.
 *
 * <p>Миксины на сами эффекты ({@code InstantHealWorldStopMixin}, {@code InstantManaWorldStopMixin})
 * отмечают здесь начало и конец их применения: прирост здоровья за это время переносится в слепок,
 * а рост маны пропускается.
 */
public final class InstantRestoration {
    /** Вложенность применяемых прямо сейчас мгновенных эффектов (только серверный поток). */
    private static int depth;
    private static float healthBefore;

    private InstantRestoration() {
    }

    private static boolean concerns(LivingEntity target) {
        return target instanceof ServerPlayer && GlobalTimeStopManager.isActive() && GlobalTimeStopManager.isCaster(target);
    }

    /** Мгновенный эффект начинает действовать на {@code target}. */
    public static void begin(LivingEntity target) {
        if (!concerns(target)) {
            return;
        }
        if (depth++ == 0) {
            healthBefore = target.getHealth();
        }
    }

    /** Мгновенный эффект отработал: что он вылечил — остаётся. */
    public static void end(LivingEntity target) {
        if (depth <= 0 || !concerns(target)) {
            return;
        }
        if (--depth == 0) {
            float gained = target.getHealth() - healthBefore;
            if (gained > 0.0F) {
                CasterPersonalTime.acceptHealing((ServerPlayer) target, gained);
            }
        }
    }

    /** Идёт применение мгновенного эффекта — рост маны кастера сейчас разрешён. */
    public static boolean applying() {
        return depth > 0;
    }

    /** Стоп закончился или сервер остановлен — незакрытых пар быть не должно, но состояние чистим. */
    static void reset() {
        depth = 0;
    }
}
