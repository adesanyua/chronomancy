package com.chronomancy.temporal;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Точечная отмена knockback для раненого «из прошлого» удара Backtrack.
 *
 * <p><b>Проблема:</b> vanilla {@code LivingEntity.hurt} при каждом уроне с
 * живым источником вызывает {@code knockback(0.4F, dx, dz)} (физический
 * импульс + «разворот» скорости). Для отложенного Backtrack-удара это
 * превращает механику в пинг-понг: цель откатили в точку A, через секунду
 * удар догоняет её и отшвыривает обратно в точку B.
 *
 * <p><b>Почему НЕ damage-type тег {@code #minecraft:no_knockback}</b> —
 * канонический vanilla-рычаг (проверка прямо в hurt), но ISB
 * {@code SpellDamageSource} жёстко берёт тип урона из ШКОЛЫ спелла
 * ({@code spell.getSchoolType().getDamageType()}). Тег на тип Chronomancy
 * отключил бы knockback ВСЕМ заклинаниям школы — запрещено дизайном.
 *
 * <p><b>Почему НЕ {@code DamageSources.ignoreNextKnockback}</b> (публичный
 * механизм ISB для NoKnockbackProjectile): он помечает сущность на целое
 * тик-окно и «съедает» первый попавшийся knockback цели — меч, ударивший в
 * том же тике, потерял бы отбрасывание. Наш случай синхронный: hurt()
 * гарантированно исполняется внутри обёртки {@link #runWithoutKnockback},
 * поэтому окно — ровно сама стек-рамка, гонки нет в принципе.
 *
 * <p><b>Механизм:</b> vanilla {@code knockback()} вызывается синхронно из
 * {@code hurt()} и прогоняет {@code LivingKnockBackEvent} (единственная
 * штатная точка отмены; сам event источника не несёт). Во время исполняющего
 * кода урона помечаем цель; слушатель отменяет knockback только помеченной
 * цели. Отмена происходит ДО {@code hasImpulse}/{@code setDeltaMovement} —
 * цель не получает ни импульса, ни directional hurt tilt
 * ({@code indicateDamage} вызывается с нулевым направлением), но красный
 * flash урона ({@code broadcastDamageEvent}) штатно сохраняется.
 *
 * <p>Работает и для release из буферов: World Stop буфер хранит живые
 * {@code DamageSource} (наш удар опознаётся по spell) и обёртывает hurt
 * здесь же; индивидуальный Stasis схлопывает урон в скаляр и выпускает
 * {@code hurt(magic())} без направления — такой «выброс времени» импульса
 * нести не должен в принципе, поэтому он тоже оборачивается целиком.
 *
 * <p>Потоковая безопасность: только серверный поток (все пути — hurt с
 * тика сервера). Вложенность поддерживается save/restore маркера.
 */
public final class BacktrackKnockbackSuppression {

    /** Цель, чей текущий (синхронный) hurt не должен отбрасывать. */
    @Nullable
    private static UUID suppressed = null;

    private BacktrackKnockbackSuppression() {
    }

    /**
     * Выполнить {@code damage} (обычно {@code target.hurt(...)} или
     * {@code DamageSources.applyDamage}), отменив knockback именно этого
     * урона. Вне стек-рамки не отменяется ничего.
     */
    public static void runWithoutKnockback(LivingEntity target, Runnable damage) {
        UUID previous = suppressed;
        suppressed = target.getUUID();
        try {
            damage.run();
        } finally {
            suppressed = previous;
        }
    }

    /** Единственный слушатель (LivingKnockBackEvent, NeoForge.EVENT_BUS). */
    public static void onKnockback(LivingKnockBackEvent event) {
        if (suppressed != null && suppressed.equals(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }
}
