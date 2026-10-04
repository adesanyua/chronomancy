package com.chronomancy.temporal.history;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * ЛЁГКОПОЗЫВЕЧНАЯ история позиций LivingEntity для Backtrack (и будущих
 * positional-заклинаний) — позиционное дополнение к {@link TemporalHistoryManager}.
 *
 * <p>Почему отдельный класс, а не рост player-ринга: снимок игрока
 * ({@link TemporalSnapshot}) тяжёлый — HP/еда/equipment-копии. Для мобов такой
 * ценой каждый тик каждой сущности мира платить нельзя, а Backtrack нужны
 * РОВНО позиция, поворот и gameTime.
 *
 * <p><b>Запись — вокруг тех, кто знает Backtrack:</b> если у игрока Backtrack есть в книге
 * заклинаний, свитке или зачарованном оружии, сервер постоянно пишет историю всех мобов в радиусе
 * {@value #WATCH_RADIUS} блоков от него ({@link #scanWatchers}). Поэтому уже ПЕРВОЕ попадание
 * откатывает цель на полную глубину. Моб, вышедший из радиуса, ещё {@value #WATCH_GRACE_TICKS}
 * тиков пишется, потом кольцо удаляется. Попадание Backtrack по любой цели ({@link #begin})
 * тоже включает запись — на случай каста без предмета (мобы-кастеры и т.п.).
 * Игроков записывать НЕ нужно: у них постоянный ring в TemporalHistoryManager,
 * его читает сам спелл.
 *
 * <p>Стоимость: record из 5 примитивов, кольцо {@value #RING_CAPACITY} тиков на
 * активную цель; жёсткий потолок {@link #MAX_RINGS} целей (переполнение — самая
 * протухшая ring вылетает). Мусор вычищается на death/remove/выходе из мира и
 * по TTL; серверные выгрузки — {@link #clearAll}.
 *
 * <p>Потоковая безопасность: все мутации только с серверного потока.
 */
public final class EntityPositionHistory {

    /**
     * Ёмкость кольца на сущность. Должна покрывать максимальную глубину
     * отката Backtrack ({@code BacktrackSpell.MAX_DEPTH_SECONDS} = 4.0 c = 80
     * тиков) с запасом ⇒ 90 тиков (4.5 c). Глубина растёт с уровнем/спелл-
     * пауэром, поэтому кольцо намеренно шире базовых 1.25 с.
     */
    public static final int RING_CAPACITY = 90;

    /** Потолок одновременно записываемых сущностей (защита от пампинга спавна). */
    private static final int MAX_RINGS = 1024;

    /** Записи старше этого возраста для Backtrack бесполезны (ищется максимум глубина). */
    public static final long MAX_AGE_TICKS = 100L;

    /** Ring без обновлений столько тиков считается мёртвой (цель выгрузилась без события). */
    private static final long STALE_TICKS = 200L;

    /** Радиус вокруг игрока с Backtrack, в котором пишется история мобов. */
    public static final double WATCH_RADIUS = 24.0;
    /** Сколько тиков кольцо живёт после того, как моб перестал быть «под наблюдением». */
    public static final long WATCH_GRACE_TICKS = 200L;
    /** Как часто ищем мобов вокруг игроков (тики). */
    private static final int SCAN_INTERVAL = 10;
    /** Как часто перепроверяем, есть ли у игрока Backtrack (тики). */
    private static final int KNOWS_RECHECK = 40;
    private static final Map<UUID, long[]> KNOWS = new HashMap<>();

    /** Один семпл: только то, что просил backtrack — позиция, поворот, gameTime. */
    public record Sample(long gameTime, double x, double y, double z, float yaw, float pitch) {
    }

    private static final class Ring {
        final UUID uuid;
        final Sample[] slots = new Sample[RING_CAPACITY];
        int head;
        long lastWriteGameTime;
        long watchedUntil;

        Ring(UUID uuid) {
            this.uuid = uuid;
        }

        void add(Sample sample) {
            slots[head] = sample;
            head = (head + 1) % RING_CAPACITY;
            lastWriteGameTime = sample.gameTime();
        }
    }

    private static final Map<UUID, Ring> RINGS = new HashMap<>();

    private EntityPositionHistory() {
    }

    // =========================================================
    // ЗАПИСЬ
    // =========================================================

    /** Включить запись для цели (идемпотентно). Вызывается при первом попадании Bolt. */
    public static void activate(LivingEntity target) {
        if (target instanceof Player || !target.isAlive()) {
            return; // игроки — в TemporalHistoryManager; мёртвым кольцо не нужно
        }
        if (RINGS.size() >= MAX_RINGS && !RINGS.containsKey(target.getUUID())) {
            evictOldest(); // потолок: жертвуем самой давнишней записью
        }
        Ring ring = RINGS.computeIfAbsent(target.getUUID(), id -> new Ring(target.getUUID()));
        ring.watchedUntil = Math.max(ring.watchedUntil,
                com.chronomancy.temporal.ChronoClock.now() + WATCH_GRACE_TICKS);
    }

    /** Игроки, у которых есть Backtrack, «включают» запись всех мобов вокруг себя. */
    private static void scanWatchers(MinecraftServer server, long now) {
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator() || !knowsBacktrack(player, now)) {
                continue;
            }
            for (net.minecraft.world.entity.Mob mob : player.serverLevel().getEntitiesOfClass(
                    net.minecraft.world.entity.Mob.class, player.getBoundingBox().inflate(WATCH_RADIUS),
                    m -> m.isAlive() && !com.chronomancy.temporal.TimeMagicImmunity.isImmune(m))) {
                activate(mob);
            }
        }
    }

    /** Backtrack в экипированной книге, свитке или зачарованном предмете инвентаря (кэш на 2 с). */
    private static boolean knowsBacktrack(net.minecraft.server.level.ServerPlayer player, long now) {
        long[] cached = KNOWS.get(player.getUUID());
        if (cached != null && now - cached[0] < KNOWS_RECHECK) {
            return cached[1] != 0;
        }
        boolean knows = hasBacktrack(io.redspace.ironsspellbooks.api.util.Utils.getPlayerSpellbookStack(player));
        var inventory = player.getInventory();
        for (int i = 0; !knows && i < inventory.getContainerSize(); i++) {
            knows = hasBacktrack(inventory.getItem(i));
        }
        KNOWS.put(player.getUUID(), new long[]{now, knows ? 1 : 0});
        return knows;
    }

    private static boolean hasBacktrack(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()
                || !io.redspace.ironsspellbooks.api.spells.ISpellContainer.isSpellContainer(stack)) {
            return false;
        }
        var container = io.redspace.ironsspellbooks.api.spells.ISpellContainer.get(stack);
        if (container == null) {
            return false;
        }
        for (var slot : container.getAllSpells()) {
            if (slot != null && slot.getSpell() == com.chronomancy.registry.ChronoSpellRegistry.BACKTRACK_SPELL) {
                return true;
            }
        }
        return false;
    }

    /** Один снимок на серверный тик для всех АКТИВНЫХ колец (иначе — ноль стоимости). */
    public static void onServerTick(ServerTickEvent.Post event) {
        long now = com.chronomancy.temporal.ChronoClock.now();
        if (now % SCAN_INTERVAL == 0) {
            scanWatchers(event.getServer(), now);
            KNOWS.keySet().removeIf(id -> event.getServer().getPlayerList().getPlayer(id) == null);
        }
        if (RINGS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Ring>> it = RINGS.entrySet().iterator();
        while (it.hasNext()) {
            Ring ring = it.next().getValue();
            if (now > ring.watchedUntil || (ring.lastWriteGameTime > 0 && now - ring.lastWriteGameTime > STALE_TICKS)) {
                it.remove(); // цель пропала без Events.remove — чистим по TTL
                continue;
            }
            Entity entity = findEntity(event.getServer(), ring);
            if (entity == null || entity.isRemoved() || !entity.isAlive()) {
                it.remove();
                continue;
            }
            ring.add(sampleOf(entity));
        }
    }

    /** Публичный вариант {@link #activate} + немедленный первый семпл (момент попадания). */
    public static void begin(LivingEntity target) {
        activate(target);
        Ring ring = RINGS.get(target.getUUID());
        if (ring != null && ring.lastWriteGameTime != com.chronomancy.temporal.ChronoClock.now()) {
            ring.add(sampleOf(target));
        }
    }

    private static Sample sampleOf(Entity entity) {
        return new Sample(
                com.chronomancy.temporal.ChronoClock.now(),
                entity.getX(), entity.getY(), entity.getZ(),
                entity.getYRot(), entity.getXRot());
    }

    private static Entity findEntity(MinecraftServer server, Ring ring) {
        // Кольцо не хранит Level-ссылку (переживание смены измерения на «той же
        // стороне» не нужен — Backtrack межизмеренческий откат запрещён); ищем
        // по загруженным уровням, активные цели всегда в одном из них.
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(ring.uuid);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private static void evictOldest() {
        UUID oldest = null;
        long best = Long.MAX_VALUE;
        for (Map.Entry<UUID, Ring> entry : RINGS.entrySet()) {
            if (entry.getValue().lastWriteGameTime < best) {
                best = entry.getValue().lastWriteGameTime;
                oldest = entry.getKey();
            }
        }
        if (oldest != null) {
            RINGS.remove(oldest);
        }
    }

    // =========================================================
    // ЧТЕНИЕ
    // =========================================================

    /**
     * Древнейший снимок НЕ РАНЕЕ {@code targetGameTime} — та же семантика, что
     * у {@link TemporalHistoryManager#findNearest}: в дыре возвращает первую
     * живую точку после цели, снимки старше цели не отдаёт никогда.
     *
     * @param level только для согласованности типов вызова; позиция читается as-is
     */
    public static Sample findNearest(Level level, UUID uuid, long targetGameTime) {
        Ring ring = RINGS.get(uuid);
        if (ring == null) {
            return null;
        }
        Sample best = null;
        for (Sample sample : ring.slots) {
            if (sample == null || sample.gameTime() < targetGameTime) {
                continue;
            }
            if (best == null || sample.gameTime() < best.gameTime()) {
                best = sample;
            }
        }
        return best;
    }

    // =========================================================
    // ОЧИСТКА
    // =========================================================

    /**
     * Длина пути (в блоках), пройденного между {@code fromGameTime} и {@code toGameTime}: сумма
     * отрезков между соседними семплами по времени. Скачки длиннее {@code maxStep} (телепорты,
     * откаты) не считаются ходьбой и пропускаются.
     */
    public static double pathLength(UUID uuid, long fromGameTime, long toGameTime, double maxStep) {
        Ring ring = RINGS.get(uuid);
        if (ring == null) {
            return 0.0;
        }
        java.util.List<Sample> window = new java.util.ArrayList<>();
        for (Sample s : ring.slots) {
            if (s != null && s.gameTime() >= fromGameTime && s.gameTime() <= toGameTime) {
                window.add(s);
            }
        }
        window.sort((a, b) -> Long.compare(a.gameTime(), b.gameTime()));
        double total = 0.0;
        for (int i = 1; i < window.size(); i++) {
            Sample a = window.get(i - 1), b = window.get(i);
            double dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d <= maxStep) {
                total += d;
            }
        }
        return total;
    }

    /** Смерть/деспавн/выход сущности: кольцо мгновенно забывается. */
    public static void clear(UUID uuid) {
        RINGS.remove(uuid);
    }

    /**
     * Выход сущности из уровня (деспавн, смена измерения, выгрузка чанка) —
     * кольцо больше некому обновлять, удаляем сразу, не дожидаясь TTL.
     * Межизмеренческий откат запрещён, так что «перенос» кольца не нужен.
     */
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof LivingEntity living && !(living instanceof Player)) {
            RINGS.remove(living.getUUID());
        }
    }

    /** Остановка сервера / выход из мира. */
    public static void clearAll() {
        RINGS.clear();
        KNOWS.clear();
    }

    /** Только для диагностики/тестов. */
    public static int trackedCount() {
        return RINGS.size();
    }
}
