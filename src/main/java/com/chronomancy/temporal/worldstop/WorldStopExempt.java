package com.chronomancy.temporal.worldstop;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * Маркер сущностей, которые существуют ВНЕ остановленного времени The World Stop
 * (Chronomaly и порождающий их Time Rift).
 *
 * <p>Такие сущности:
 * <ul>
 *   <li>продолжают тикать на сервере ({@code WorldStopEvents#onEntityTickPre}) и на
 *       клиенте ({@code WorldStopClientLevelMixin}), их анимации не замораживаются
 *       рендер-миксинами;</li>
 *   <li>их снаряды тоже летят во время стопа;</li>
 *   <li>наносят и получают урон мгновенно — удары с их участием НЕ попадают в
 *       {@link TemporalDamageBuffer}.</li>
 * </ul>
 *
 * <p>Проверка — обычный {@code instanceof}, одинаково работающий на обеих сторонах,
 * поэтому никаких дополнительных пакетов синхронизации не требуется.
 */
public interface WorldStopExempt {

    /**
     * Сущность живёт вне остановленного времени: сама Chronomaly / Time Rift либо снаряд,
     * выпущенный ими (Backtrack Bolt, Temporal Needle Chronomaly).
     */
    static boolean is(Entity entity) {
        if (entity instanceof WorldStopExempt exempt) {
            return exempt.isOutsideTime();
        }
        if (entity instanceof com.chronomancy.entity.TimeDilationFieldEntity field) {
            return field.isOutsideTime();
        }
        return entity instanceof Projectile projectile && projectile.getOwner() instanceof WorldStopExempt owner
                && owner.isOutsideTime();
    }

    /** Может ли конкретный экземпляр жить вне времени (например, только двойники босса). */
    default boolean isOutsideTime() {
        return true;
    }

    /**
     * Удар применяется сразу, минуя буфер мирового стопа: жертва или атакующий
     * (в том числе прямая сущность, например снаряд) находятся вне времени.
     */
    static boolean bypassesDamageBuffer(DamageSource source, LivingEntity victim) {
        return is(victim) || is(source.getEntity()) || is(source.getDirectEntity());
    }
}
