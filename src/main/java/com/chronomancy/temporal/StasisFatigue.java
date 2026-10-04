package com.chronomancy.temporal;

import com.chronomancy.ChronoConfig;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * УСТАЛОСТЬ СТАЗИСА — защита от бесконечного контроля.
 *
 * <p>Каждый новый стазис на одной и той же цели короче предыдущего на долю из конфига (по умолчанию
 * 20%): 100%, 80%, 60%, 40%, 20%, дальше стазис на цель не ложится вовсе. Стазис от самого Rift Maker
 * утомляет вдвое слабее (10%). Счётчик сбрасывается
 * через минуту (конфиг) после последнего стазиса, который на цель лёг. Считается всё, что проходит
 * через {@code TemporalStasisEvents}: луч, пески времени, Cracked Dial, удар Rift Maker, двойники
 * босса, эффект из команды. The World Stop — не стазис и сюда не входит.
 *
 * <p>Время — по {@link ChronoClock}: минута идёт и внутри остановленного мира.
 */
public final class StasisFatigue {
    /** @param load накопленная усталость: сумма шагов всех стазисов, что легли на цель (1 — иммунитет) */
    private record State(double load, long last) {
    }

    /**
     * Шаг усталости для стазиса, который накладывается прямо сейчас; {@code NaN} — обычный, из
     * конфига. Rift Maker утомляет цель слабее ({@code stasis.riftMakerFatiguePerStasis}): его удар
     * стазиса остаётся опасным дольше.
     */
    private static double stepOverride = Double.NaN;

    /** Выполнить {@code action}, считая стазисы внутри него с шагом усталости {@code step}. */
    public static void runWithStep(double step, Runnable action) {
        double before = stepOverride;
        stepOverride = step;
        try {
            action.run();
        } finally {
            stepOverride = before;
        }
    }

    private static double step() {
        return Double.isNaN(stepOverride) ? ChronoConfig.stasisFatigue() : stepOverride;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private StasisFatigue() {
    }

    private static State live(Entity target) {
        State state = STATES.get(target.getUUID());
        if (state != null && ChronoClock.now() - state.last() > ChronoConfig.stasisFatigueResetSeconds() * 20L) {
            STATES.remove(target.getUUID());
            return null;
        }
        return state;
    }

    /** Какую долю длительности получит СЛЕДУЮЩИЙ стазис на этой цели (1 — полный, 0 — не ляжет). */
    public static double nextFactor(Entity target) {
        State state = live(target);
        return state == null ? 1.0D : Math.max(0.0D, 1.0D - state.load());
    }

    /**
     * На цель накладывается стазис длиной {@code ticks}: возвращает, сколько он продлится на самом
     * деле, и засчитывает его. Вызывать ровно один раз на каждое наложение.
     */
    public static long apply(Entity target, long ticks) {
        if (ChronoConfig.stasisFatigue() <= 0.0D || target.level().isClientSide) {
            return ticks;
        }
        State state = live(target);
        double load = state == null ? 0.0D : state.load();
        double factor = Math.max(0.0D, 1.0D - load);
        if (factor <= 1.0E-4) {
            return 0L; // не лёг — и минуту до сброса не продлевает
        }
        STATES.put(target.getUUID(), new State(load + Math.max(0.0D, step()), ChronoClock.now()));
        return Math.max(1L, Math.round(ticks * factor));
    }

    public static void onLeave(EntityLeaveLevelEvent event) {
        // игроков не забываем: перезаход не должен обнулять усталость посреди боя
        if (!event.getLevel().isClientSide() && !(event.getEntity() instanceof net.minecraft.world.entity.player.Player)) {
            STATES.remove(event.getEntity().getUUID());
        }
    }

    public static void onStop(ServerStoppingEvent event) {
        STATES.clear();
    }
}
