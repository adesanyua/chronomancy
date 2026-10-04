package com.chronomancy.temporal;

import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Монотонные «внешние» серверные часы Chronomancy: +1 на каждый серверный тик.
 *
 * <p>В отличие от {@code ServerLevel#getGameTime()} НЕ останавливаются во время The World Stop,
 * поэтому по ним живут механики, которые должны работать внутри остановленного времени
 * (история позиций для Rewind/Backtrack, дедлайны отложенного урона Backtrack). Кто действует
 * в остановленном мире (кастер, Chronomaly) — тот и видит, как идут эти часы.
 */
public final class ChronoClock {

    private static long ticks;

    private ChronoClock() {
    }

    public static long now() {
        return ticks;
    }

    /** Регистрируется с приоритетом HIGHEST на {@link ServerTickEvent.Pre}. */
    public static void onServerTick(ServerTickEvent.Pre event) {
        ticks++;
    }
}
