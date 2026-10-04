package com.chronomancy.temporal;

import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Небо не дёргается, пока день стоит (Temporal Anchor, The World Stop).
 *
 * <p>Сервер в это время держит время суток на месте, но клиент об этом не знает: он сам каждый тик
 * двигает солнце вперёд, а раз в секунду сервер присылает настоящее время — и небо рывком возвращается
 * назад. Отсюда мелкая дрожь солнца, луны и звёзд.
 *
 * <p>В ванили для этого уже есть способ — тот же, которым работает правило {@code doDaylightCycle}:
 * пакет времени с пометкой «день стоит», получив который клиент перестаёт двигать солнце сам. Обычная
 * ежесекундная синхронизация шлёт пакет без пометки (правило-то включено), поэтому сразу вслед за ней,
 * в том же тике, уходит наш — с пометкой. Когда день снова идёт, клиентам отправляется обычный пакет.
 */
public final class DayFreezeSync {
    private static final Map<ServerLevel, Boolean> FROZEN = new WeakHashMap<>();

    private DayFreezeSync() {
    }

    /** Раз в тик на каждое измерение (после тика мира): стоит ли в нём сейчас время суток. */
    public static void update(ServerLevel level, boolean frozen) {
        boolean was = FROZEN.containsKey(level);
        if (frozen) {
            FROZEN.put(level, Boolean.TRUE);
            // ванильная синхронизация времени идёт на тиках, кратных 20, ДО тика мира — отвечаем на неё
            if (!was || level.getServer().getTickCount() % 20 == 0) {
                send(level, false);
            }
        } else if (was) {
            FROZEN.remove(level);
            send(level, level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT));
        }
    }

    public static boolean isFrozen(ServerLevel level) {
        return FROZEN.containsKey(level);
    }

    /** Игрок вошёл в измерение, где день стоит: ваниль прислала ему обычное время — поправляем сразу. */
    public static void onPlayerJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getLevel() instanceof ServerLevel level
                && FROZEN.containsKey(level) && player.connection != null) {
            player.connection.send(new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(), false));
        }
    }

    private static void send(ServerLevel level, boolean daylightRuns) {
        ClientboundSetTimePacket packet = new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(), daylightRuns);
        for (ServerPlayer player : level.players()) {
            if (player.connection != null) {
                player.connection.send(packet);
            }
        }
    }

    public static void onStop(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        FROZEN.clear();
    }
}
