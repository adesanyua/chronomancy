package com.chronomancy.temporal.worldstop;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodData;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Биологическое время кастера The World Stop (Фаза 4).
 *
 * <p>Кастер может ДЕЙСТВОВАТЬ в остановленном мире (его сущность тикается —
 * он исключение из мирового freeze), но его личные timed-процессы заморожены:
 * реген здоровья, голод/насыщение/истощение,air supply, fire ticks и
 * длительности всех MobEffect. Разделение «action time» / «biological time»
 * сделано snapshot/re-pin на внешних серверных тиках — НИКАКОЙ отмены
 * Player.tick и НИКАКИХ правок tickCount (запрещённые приёмы).
 *
 * <p>Механика pin: {@link #begin} один раз снимает слепок, а каждый следующий
 * тик {@link #pinCaster} возвращает значения к слепку. Поскольку тик кастера
 * уже отработал (ServerTickEvent.Post идёт ПОСЛЕ тика мира), каждая
 * происшедшая за тик деградация (−1 к длительности эффекта, −1 воздуха, реген
 * +HP) немедленно отменяется → часы кастера стоят. 60-секундный бафф через
 * все 120 тиков стопа остаётся ровно 60-секундным (TEST 5).
 *
 * <p>Эффекты, полученные ВО ВРЕМЯ стопа, в слепке не числятся и тикают штатно
 * (крайний случай, допустимо). Пассивная регенерация маны замораживается
 * отдельно — отменой {@code ChangeManaEvent} на рост (см. WorldStopEvents),
 * т.к. mana живёт в ISS MagicData, а не в сущности.
 */
final class CasterPersonalTime {

    private CasterPersonalTime() {
    }

    /** Слепок биологического состояния кастера на момент старта стопа. */
    private static final class Snapshot {
        private float health;
        private int foodLevel;
        private float saturation;
        private float exhaustion;
        private int fireTicks;
        private int airSupply;
        // Holder эффекта → законсервированный экземпляр (сохраняет duration,
        // amplifier, ambient/visible/showIcon флаги). Порядок не важен.
        private final Map<Holder<MobEffect>, MobEffectInstance> effects = new LinkedHashMap<>();
    }

    private static ServerPlayer caster;
    private static Snapshot snapshot;

    /** Вызывается из {@code GlobalTimeStopManager.tryStart} (серверный поток). */
    static void begin(ServerPlayer caster) {
        CasterPersonalTime.caster = caster;
        Snapshot snap = new Snapshot();
        snap.health = caster.getHealth();
        FoodData food = caster.getFoodData();
        snap.foodLevel = food.getFoodLevel();
        snap.saturation = food.getSaturationLevel();
        snap.exhaustion = food.getExhaustionLevel();
        snap.fireTicks = caster.getRemainingFireTicks();
        snap.airSupply = caster.getAirSupply();
        for (MobEffectInstance instance : caster.getActiveEffects()) {
            // Индикатор игл ведёт TemporalNeedleManager (снимает при схлопывании) — не пиним.
            if (instance.getEffect().is(com.chronomancy.registry.ChronoMobEffectRegistry.TEMPORAL_NEEDLES)) {
                continue;
            }
            snap.effects.put(instance.getEffect(), new MobEffectInstance(instance));
        }
        CasterPersonalTime.snapshot = snap;
    }

    /**
     * Каждый серверный тик активного стопа: вернуть витальные значения к
     * слепку. Безопасно, если слепка нет (стоп не инициирован/уже снят).
     */
    static void pinCaster() {
        if (caster == null || snapshot == null || caster.isRemoved()) {
            return;
        }
        FoodData food = caster.getFoodData();
        if (com.chronomancy.entity.TimeIslandEntity.anyCovers(caster)) {
            // Островок времени: здесь личное время идёт. Что за этот тик изменилось со здоровьем и
            // сытостью (обычная регенерация и её цена), то и остаётся — слепок догоняет кастера.
            snapshot.health = caster.getHealth();
            snapshot.foodLevel = food.getFoodLevel();
            snapshot.saturation = food.getSaturationLevel();
            snapshot.exhaustion = food.getExhaustionLevel();
        } else {
            caster.setHealth(snapshot.health);
            food.setFoodLevel(snapshot.foodLevel);
            food.setSaturation(snapshot.saturation);
            food.setExhaustion(snapshot.exhaustion);
        }

        caster.setRemainingFireTicks(snapshot.fireTicks);
        caster.setAirSupply(snapshot.airSupply);

        // Ре-pin длительностей: тик кастера уже урезал длящийся эффект на 1 —
        // возвращаем к слепку. Бесконечные и уже корректные пропускаем,
        // чтобы не дёргать forceAddEffect зря каждый тик.
        for (Map.Entry<Holder<MobEffect>, MobEffectInstance> e : snapshot.effects.entrySet()) {
            MobEffectInstance pinned = e.getValue();
            if (pinned.isInfiniteDuration()) {
                continue;
            }
            MobEffectInstance live = caster.getEffect(e.getKey());
            if (live == null || live.getDuration() < pinned.getDuration()) {
                caster.forceAddEffect(new MobEffectInstance(pinned), null);
            }
        }
    }

    /**
     * Урон, прошедший мимо буфера (удар Chronomaly), реален: переносим текущее здоровье
     * в слепок. Лечение по-прежнему откатывается пином.
     */
    static void acceptHealth(ServerPlayer target) {
        if (caster == null || snapshot == null || caster != target) {
            return;
        }
        snapshot.health = Math.min(snapshot.health, target.getHealth());
    }

    /**
     * Мгновенное лечение (зелье) в остановленном времени — настоящее: прибавка переносится в слепок.
     * См. {@link InstantRestoration}.
     */
    static void acceptHealing(ServerPlayer target, float gained) {
        if (caster == null || snapshot == null || caster != target || gained <= 0.0F) {
            return;
        }
        snapshot.health = Math.min(target.getMaxHealth(), snapshot.health + gained);
    }

    /**
     * Конец стопа: снять пин. Значения остаются на уровне слепка и дальше
     * идут штатно (бафф, замороженный на 60 с, возобновляет отсчёт с 60 с).
     */
    static void releaseCaster() {
        caster = null;
        snapshot = null;
        InstantRestoration.reset();
    }
}
