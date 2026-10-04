package com.chronomancy.temporal;

import com.chronomancy.registry.ChronoSchools;
import io.redspace.ironsspellbooks.damage.DamageSources;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Сопротивление магии времени.
 *
 * <p>Урон заклинаний школы Iron's Spells режет сам — по атрибуту
 * {@code chronomancy_magic_resist} (и общему {@code spell_resist}). Здесь та же формула применяется к
 * эффектам школы, которые уроном не являются: длительности стазиса, силе замедления в поле и
 * глубине отката Backtrack. The World Stop сопротивление не ослабляет — глобальная остановка
 * времени действует на всех одинаково.
 */
public final class TimeMagicResist {

    private TimeMagicResist() {
    }

    /**
     * Какая доля магии времени доходит до цели: 1 — сопротивления нет, 0.5 — эффекты вдвое слабее.
     * Считается так же, как множитель урона заклинаний школы у Iron's Spells.
     */
    public static double factor(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return 1.0D;
        }
        try {
            return Mth.clamp(DamageSources.getResist(living, ChronoSchools.CHRONOMANCY.get()), 0.0D, 2.0D);
        } catch (RuntimeException e) {
            return 1.0D; // у сущности нет атрибутов школы
        }
    }

    /** Длительность/глубина эффекта с учётом сопротивления цели (не меньше одного тика). */
    public static long scaleTicks(Entity entity, long ticks) {
        return Math.max(1L, Math.round(ticks * factor(entity)));
    }

    /** Темп времени в поле замедления с учётом сопротивления: ослабляется само замедление, а не темп. */
    public static double scaleRate(Entity entity, double rate) {
        if (Math.abs(rate - 1.0D) < 1.0E-6) {
            return rate;
        }
        if (rate > 1.0D) {
            // ускорение (зона, парадокс) сопротивление гасит так же, как замедление
            return Math.max(1.0D, 1.0D + (rate - 1.0D) * factor(entity));
        }
        return Math.min(1.0D, 1.0D - (1.0D - rate) * factor(entity));
    }
}
