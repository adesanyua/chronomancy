package com.chronomancy.temporal;

import com.chronomancy.client.TemporalStasisClientState;
import com.chronomancy.entity.TimeDilationFieldEntity;
import com.chronomancy.registry.ChronoMobEffectRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Единая точка принятия решений для Time Dilation.
 *
 * <p>Асимметричная модель (осознанно, ради плавности):
 * <ul>
 *     <li><b>Сервер</b> — дробный пропуск тиков ({@link #shouldSkipTick}):
 *     {@code progress += rate}; доросло до 1 — тик разрешён, иначе пропускаем.
 *     Это и есть «личное время»: AI, атаки, кулдауны идут медленнее.</li>
 *     <li><b>Клиент</b> — НЕ пропускает тики (иначе анимация и интерполяция
 *     «замерзают» и дёргаются). Вместо этого темп применяется НЕПРЕРЫВНО через
 *     масштабирование перемещения в {@code Entity#move} (см.
 *     {@code TemporalDilationClientMoveMixin}).</li>
 * </ul>
 *
 * <p>{@link #resolveRate} даёт итоговый темп для обеих сторон: перекрытие полей =
 * {@code min}, стазис в приоритете, игроки и owner не замедляются.
 */
public final class TemporalDilationHandler {

    private TemporalDilationHandler() {
    }

    private static final Map<UUID, Float> SERVER_PROGRESS =
            Collections.synchronizedMap(new HashMap<>());

    /**
     * Сколько полей дилейшена сейчас активно. Нулевой счётчик — быстрый путь:
     * не делать {@code getEntitiesOfClass} на каждый тик каждой сущности.
     */
    private static final AtomicInteger ACTIVE_FIELD_COUNT = new AtomicInteger(0);

    /** Насколько.inflate'ить AABB поиска вокруг сущности, чтобы поймать большие поля. */
    private static final double SEARCH_INFLATE = 16.0;

    public static void onFieldAdded() {
        ACTIVE_FIELD_COUNT.incrementAndGet();
    }

    public static void onFieldRemoved() {
        if (ACTIVE_FIELD_COUNT.decrementAndGet() < 0) {
            ACTIVE_FIELD_COUNT.set(0);
        }
    }

    /**
     * Хук {@code EntityJoinLevelEvent}: держим живой счётчик полей для быстрого
     * пути в {@link #resolveRate}. Сторона берётся из самого уровня, поэтому один
     * и тот же обработчик корректно работает и на сервере, и на клиенте.
     */
    public static void onEntityJoined(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof TimeDilationFieldEntity) {
            onFieldAdded();
        }
    }

    /** Хук {@code EntityLeaveLevelEvent}: снимаем счётчик и чистим прогресс ушедшей сущности. */
    public static void onEntityLeft(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof TimeDilationFieldEntity) {
            onFieldRemoved();
        }
        reset(event.getEntity());
    }

    /**
     * Итоговый темп времени для сущности. Возвращает {@link TemporalRate#NORMAL},
     * если замедления быть не должно.
     */
    public static double resolveRate(Level level, Entity entity) {
        // Игроки никогда не замедляются собственным/чужим дилейшеном.
        // Замедляем только тех, у кого есть «личное время», подлежащее растяжению:
        // мобы и снаряды. Само поле, предметы и прочее — вне области действия.
        if (!(entity instanceof Mob) && !(entity instanceof Projectile) && !(entity instanceof Player)) {
            return TemporalRate.NORMAL;
        }
        // Приоритет у стазиса: полностью замороженная сущность тик не получает
        // вообще (отдельный механизм), дилейшен её не касается.
        if (isStasisFrozen(level, entity)) {
            return TemporalRate.NORMAL;
        }
        double best = fieldRate(level, entity);
        // Личный темп от эффектов на самой сущности (слои песка, парадокс). Только сервер: клиенту
        // эти состояния не рассылаются. Существа вне времени ему не подвластны.
        if (!level.isClientSide && !TimeMagicImmunity.isImmune(entity)) {
            best *= PersonalTimeRate.rate(entity);
        }
        if (Math.abs(best - TemporalRate.NORMAL) < 1.0E-4) {
            return TemporalRate.NORMAL;
        }
        best = Math.min(TemporalRate.MAX_ACCELERATION_RATE, Math.max(TemporalRate.MIN_DILATION_RATE, best));
        // Сопротивление магии времени ослабляет само замедление (у цели с 50% оно вдвое слабее).
        return TimeMagicResist.scaleRate(entity, best);
    }

    /**
     * Темп от областей вокруг сущности: самое сильное замедление среди полей, умноженное на самое
     * сильное ускорение среди зон (Accelerated Zone). Общий код сервера и клиента.
     */
    private static double fieldRate(Level level, Entity entity) {
        // Быстрый путь: нет полей — нечего искать.
        if (ACTIVE_FIELD_COUNT.get() == 0) {
            return TemporalRate.NORMAL;
        }
        double slow = TemporalRate.NORMAL;
        double fast = TemporalRate.NORMAL;
        for (TimeDilationFieldEntity field : level.getEntitiesOfClass(
                TimeDilationFieldEntity.class,
                entity.getBoundingBox().inflate(SEARCH_INFLATE))) {
            double rate = field.rateFor(entity);
            // rateFor возвращает NaN, если поле на сущность не действует.
            if (Double.isNaN(rate)) {
                continue;
            }
            if (rate < slow) {
                slow = rate; // min = самое сильное активное замедление
            } else if (rate > fast) {
                fast = rate; // max = самое сильное активное ускорение
            }
        }
        // Игрок под Borrowed Future замедляется полем вдвое слабее; ускорение зоны не меняется.
        return com.chronomancy.temporal.borrowed.BorrowedFutureWard.weakenSlow(entity, slow) * fast;
    }

    /**
     * Во сколько раз ускоряет сущность Accelerated Zone, в которой она стоит: 1.0 — зоны нет,
     * 1.4 — зона на +40%. Во столько же раз растёт получаемый ею урон. Сопротивление магии времени
     * ослабляет и то и другое.
     */
    public static double zoneAcceleration(Level level, Entity entity) {
        if (ACTIVE_FIELD_COUNT.get() == 0) {
            return TemporalRate.NORMAL;
        }
        double fast = TemporalRate.NORMAL;
        for (TimeDilationFieldEntity field : level.getEntitiesOfClass(
                TimeDilationFieldEntity.class,
                entity.getBoundingBox().inflate(SEARCH_INFLATE))) {
            if (!field.isAccelerating()) {
                continue;
            }
            double rate = field.rateFor(entity);
            if (!Double.isNaN(rate) && rate > fast) {
                fast = rate;
            }
        }
        return TimeMagicResist.scaleRate(entity, fast);
    }

    /** Урон по ускоренным зоной растёт на столько же процентов, на сколько они ускорены. */
    public static void onIncomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        net.minecraft.world.entity.LivingEntity target = event.getEntity();
        if (target.level().isClientSide || ACTIVE_FIELD_COUNT.get() == 0 || event.getAmount() <= 0.0F) {
            return;
        }
        if (zoneAmplificationSuppressed) {
            return;
        }
        double acceleration = zoneAcceleration(target.level(), target);
        if (acceleration > TemporalRate.NORMAL + 1.0E-4) {
            event.setAmount((float) (event.getAmount() * zoneDamageFactor(acceleration)));
        }
    }

    /** Во сколько раз зона с таким темпом усиливает урон по ускоренному ею (доля — в конфиге). */
    public static double zoneDamageFactor(double acceleration) {
        return 1.0D + Math.max(0.0D, acceleration - TemporalRate.NORMAL) * com.chronomancy.ChronoConfig.zoneDamageTakenShare();
    }

    /** Урон «на входе» в зону наносится без её собственного усиления — см. {@code TimeDilationFieldEntity}. */
    private static boolean zoneAmplificationSuppressed;

    public static void runWithoutZoneAmplification(Runnable action) {
        boolean before = zoneAmplificationSuppressed;
        zoneAmplificationSuppressed = true;
        try {
            action.run();
        } finally {
            zoneAmplificationSuppressed = before;
        }
    }

    /**
     * Насколько сущность замедлена во времени, 0..1 (0 — не замедлена, 0.7 — на 70%). Стазис сюда
     * не входит: это отдельное состояние.
     */
    public static double slowFraction(Level level, Entity entity) {
        double rate = resolveRate(level, entity);
        return rate >= TemporalRate.NORMAL ? 0.0D : 1.0D - rate;
    }

    /** Идёт дополнительный тик ускоренной сущности — второй раз его не ускоряем. */
    private static boolean accelerating;

    public static boolean isAccelerating() {
        return accelerating;
    }

    public static void setAccelerating(boolean value) {
        accelerating = value;
    }

    /**
     * Сколько ДОПОЛНИТЕЛЬНЫХ тиков положено ускоренной сущности в этом серверном тике. Дробная часть
     * темпа копится: при темпе 1.4 лишний тик выпадает в двух тиках из пяти.
     */
    public static int extraTicks(Entity entity, double rate) {
        if (rate <= TemporalRate.NORMAL + 1.0E-4) {
            return 0;
        }
        UUID id = entity.getUUID();
        float progress = SERVER_PROGRESS.getOrDefault(id, 0.0F) + (float) (rate - TemporalRate.NORMAL);
        int extra = (int) Math.floor(progress);
        SERVER_PROGRESS.put(id, progress - extra);
        return Math.min(extra, 3);
    }

    /**
     * Серверное дробное накопление личного времени. {@code true} — тик в этом шаге
     * нужно пропустить (сущность ещё не «накопила» целый тик). Используется ТОЛЬКО
     * серверным миксином; клиент замедляет время непрерывным масштабированием move().
     */
    public static boolean shouldSkipTick(Entity entity, double rate) {
        Map<UUID, Float> progressMap = SERVER_PROGRESS;
        UUID id = entity.getUUID();

        if (TemporalRate.isEffectivelyNormal(rate)) {
            // Вне поля / не замедляется — сбрасываем накопленный прогресс,
            // чтобы при следующем входе в поле не было «фанка» лишнего тика.
            progressMap.remove(id);
            return false;
        }

        float progress = progressMap.getOrDefault(id, 0.0F) + (float) rate;
        if (progress >= 1.0F) {
            progressMap.put(id, progress - 1.0F); // разрешаем тик, оставляем дробь
            return false;
        }
        progressMap.put(id, progress);
        return true; // пропускаем тик
    }

    /** Сброс накопленного прогресса сущности (например, при выходе из мира). */
    public static void reset(Entity entity) {
        SERVER_PROGRESS.remove(entity.getUUID());
    }

    /** Полная очистка (выход из мира), чтобы не копить мусор. */
    public static void clearAll() {
        SERVER_PROGRESS.clear();
    }

    /**
     * Клиентский множитель перемещения для непрерывного (плавного) замедления
     * через {@code Entity#move}. Возвращает {@code 1.0} (без изменений), если
     * масштабировать не нужно: это сервер, игрок, не Mob/Projectile, сущность в
     * стазисе либо вне поля. Единственный проверяемый тяжёлый случай — сущность
     * внутри активного поля на клиенте.
     */
    public static double clientMoveScale(Entity entity) {
        if (entity == null || entity.level() == null || !entity.level().isClientSide) {
            return 1.0;
        }
        Level level = entity.level();
        if (!(entity instanceof Mob) && !(entity instanceof Projectile) && !(entity instanceof Player)) {
            return 1.0;
        }
        if (ACTIVE_FIELD_COUNT.get() == 0) {
            return 1.0;
        }
        if (isStasisFrozen(level, entity)) {
            return 1.0;
        }
        return TimeMagicResist.scaleRate(entity, fieldRate(level, entity));
    }

    /** Клиентский предикат «сущность сейчас внутри поля дилейшена» для рендера (tint и т.п.). */
    public static boolean isClientDilated(Entity entity) {
        return clientMoveScale(entity) < TemporalRate.NORMAL - 1.0E-4;
    }

    /**
     * Клиентский темп времени в произвольной точке мира — для «не-сущностей»:
     * погода (дождь/снег) живёт в LevelRenderer, а не в Entity, и замедляется
     * отдельно (см. {@code ChronoWeatherClock}). Позиция сравнивается с
     * RENDER-центром полей — погода замедляется ровно там, где нарисован купол
     * (важно для плавно следующей Shift-сферы).
     */
    public static double clientRateAt(Level level, Vec3 pos) {
        if (level == null || !level.isClientSide || pos == null || ACTIVE_FIELD_COUNT.get() == 0) {
            return TemporalRate.NORMAL;
        }
        double best = TemporalRate.NORMAL;
        AABB box = new AABB(pos, pos).inflate(SEARCH_INFLATE);
        for (TimeDilationFieldEntity field : level.getEntitiesOfClass(
                TimeDilationFieldEntity.class, box)) {
            if (field.isAccelerating()) {
                continue; // погоду зона не трогает
            }
            double radius = field.getRadius();
            if (field.getRenderCenter().distanceToSqr(pos) <= radius * radius) {
                double rate = field.getTemporalRate();
                if (rate < best) {
                    best = rate;
                }
            }
        }
        return best;
    }

    private static boolean isStasisFrozen(Level level, Entity entity) {
        if (level.isClientSide) {
            return TemporalStasisClientState.isFrozen(entity.getId());
        }
        return entity instanceof net.minecraft.world.entity.LivingEntity living
                && living.hasEffect(ChronoMobEffectRegistry.TEMPORAL_STASIS);
    }
}
