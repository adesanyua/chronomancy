package com.chronomancy.temporal.history;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Универсальная серверная система краткосрочной истории состояния игроков.
 *
 * <p>Спроектирована НЕЗАВИСИМО от конкретного заклинания: кольцо снимков
 * {@link TemporalSnapshot} на игрока ведётся постоянно и бесплатно для
 * механик, которые только планируются (Temporal Echo, Time Anchor, ...).
 * RewindSpell — первый потребитель, ноhistory-буфер ему не принадлежит.
 *
 * <p>Хранение: фиксированное кольцевое буфер {@value #RING_CAPACITY} снимков
 * (по одному на серверный тик = 6 секунд). Буфер никогда не растёт:
 * перзапись идёт по кругу, стоимость ~25 КБ примитивов + 6 лёгких копий
 * ItemStack на игрока, константа независимо от аптайма.
 *
 * <p>Потоковая безопасность: все мутации — только с серверного потока
 * ({@link ServerTickEvent.Post} и обработчики событий).
 *
 * <p>После успешного Rewind история новее точки отката удаляется
 * ({@link #truncateAfter}) — повторный каст не может «прыгнуть» по уже
 * отменённой временной линии; кольцо продолжит заполняться свежими тиками.
 */
public final class TemporalHistoryManager {

    /** Ёмкость кольца в тиках (снимок каждый серверный тик). 120 = 6 секунд. */
    public static final int RING_CAPACITY = 120;

    private TemporalHistoryManager() {
    }

    private static final Map<UUID, Ring> HISTORIES = new HashMap<>();

    // =========================================================
    // ЗАПИСЬ
    // =========================================================

    /**
     * Запись истории каждый серверный тик. Работает независимо от того,
     * используется ли Rewind. Замечание: в NeoForge 21.1 события
     * PlayerTickEvent нет (проверено по javap), поэтому обходим игроков из
     * ServerTickEvent.Post — это даёт ровно один снимок на игровой тик.
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            ringFor(player.getUUID()).add(TemporalSnapshot.capture(player));
        }
    }

    private static Ring ringFor(UUID uuid) {
        return HISTORIES.computeIfAbsent(uuid, id -> new Ring());
    }

    // =========================================================
    // ЧТЕНИЕ
    // =========================================================

    /**
     * Древнейший снимок НЕ РАНЕЕ {@code targetGameTime} — то есть ближайшая
     * достижимая точка отката на глубине не меньше запрошенной.
     *
     * <p>После успешного Rewind {@link #truncateAfter} выбрасывает из кольца
     * все снимки новее точки отката — в истории остаётся «дыра» длиной почти
     * в всю глубину. Прежний поиск с допуском ±4 тика возвращал там null, и
     * каждый повторный каст в пределах глубины молча не срабатывал. Поиск
     * «древнейшего не раньше цели» лишен этого дефекта: в непрерывной истории
     * он дает тот же снимок (цель существует посекундно), а в дыре — первую
     * живую точку после неё (откат чуть мельче запрошенного). Снимки СТАРШЕ
     * цели не возвращаются намеренно: они могут лежать на уже отменённой
     * временной линии.
     *
     * <p>null — только если кольцо пусто/игрок без истории.
     */
    public static TemporalSnapshot findNearest(UUID uuid, long targetGameTime) {
        Ring ring = HISTORIES.get(uuid);
        if (ring == null) {
            return null;
        }
        TemporalSnapshot best = null;
        for (TemporalSnapshot snapshot : ring.slots) {
            if (snapshot == null || snapshot.gameTime() < targetGameTime) {
                continue;
            }
            if (best == null || snapshot.gameTime() < best.gameTime()) {
                best = snapshot;
            }
        }
        return best;
    }


    /**
     * Длина пути (в блоках), пройденного между {@code fromGameTime} и {@code toGameTime}: сумма
     * отрезков между соседними снимками по времени. Скачки длиннее {@code maxStep} (телепорты,
     * откаты) не считаются ходьбой и пропускаются.
     */
    public static double pathLength(UUID uuid, long fromGameTime, long toGameTime, double maxStep) {
        Ring ring = HISTORIES.get(uuid);
        if (ring == null) {
            return 0.0;
        }
        List<TemporalSnapshot> window = new ArrayList<>();
        for (TemporalSnapshot s : ring.slots) {
            if (s != null && s.gameTime() >= fromGameTime && s.gameTime() <= toGameTime) {
                window.add(s);
            }
        }
        window.sort((a, b) -> Long.compare(a.gameTime(), b.gameTime()));
        double total = 0.0;
        for (int i = 1; i < window.size(); i++) {
            double d = window.get(i).pos().distanceTo(window.get(i - 1).pos());
            if (d <= maxStep) {
                total += d;
            }
        }
        return total;
    }

    /**
     * Точки траектории между двумя моментами времени (включительно),
     * прореженные каждые {@code stepTicks} тиков, от НОВОЙ к СТАРОЙ —
     * ровно столько, сколько нужно для визуала. Весь буфер наружу не отдаётся.
     */
    public static List<TemporalSnapshot> trajectoryBetween(UUID uuid, long fromGameTime, long toGameTime, int stepTicks) {
        Ring ring = HISTORIES.get(uuid);
        List<TemporalSnapshot> result = new ArrayList<>();
        if (ring == null) {
            return result;
        }
        // Собираем нужное окно и сортируем по времени — снимков в окне мало.
        List<TemporalSnapshot> window = new ArrayList<>();
        for (TemporalSnapshot snapshot : ring.slots) {
            if (snapshot != null && snapshot.gameTime() >= fromGameTime && snapshot.gameTime() <= toGameTime) {
                window.add(snapshot);
            }
        }
        window.sort((a, b) -> Long.compare(b.gameTime(), a.gameTime())); // новое -> старое
        for (int i = 0; i < window.size(); i += Math.max(1, stepTicks)) {
            result.add(window.get(i));
        }
        return result;
    }

    // =========================================================
    // ОТКАТ / ОЧИСТКА
    // =========================================================

    /**
     * Удаляет из истории все снимки НОВОЕ {@code gameTime} — вызывается после
     * успешного Rewind, чтобы отменённая временная линия не оставалась доступной.
     */
    public static void truncateAfter(UUID uuid, long gameTime) {
        Ring ring = HISTORIES.get(uuid);
        if (ring == null) {
            return;
        }
        for (int i = 0; i < ring.slots.length; i++) {
            TemporalSnapshot snapshot = ring.slots[i];
            if (snapshot != null && snapshot.gameTime() > gameTime) {
                ring.slots[i] = null;
            }
        }
    }

    /** Полностью забыт историю игрока (смерть/выход). */
    public static void clear(UUID uuid) {
        HISTORIES.remove(uuid);
    }

    /** Смерть: история умершего стирается (v1 Rewind не воскрешает). */
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Player player) {
            RewindPlaybackManager.cancel(player.getUUID());
            clear(player.getUUID());
        }
    }

    /** Выход из мира: не держим историю отключившихся игроков. */
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        RewindPlaybackManager.cancel(event.getEntity().getUUID());
        clear(event.getEntity().getUUID());
    }

    // =========================================================
    // КОЛЬЦО
    // =========================================================

    /** Фиксированный кольцевой буфер: запись по head, перзапись самых старых. */
    private static final class Ring {
        final TemporalSnapshot[] slots = new TemporalSnapshot[RING_CAPACITY];
        int head;

        void add(TemporalSnapshot snapshot) {
            slots[head] = snapshot;
            head = (head + 1) % RING_CAPACITY;
        }
    }
}
