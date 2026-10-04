package com.chronomancy.temporal.worldstop;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.network.ChronoNetwork;
import com.chronomancy.sound.ChronoStasisSounds;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import io.redspace.ironsspellbooks.capabilities.magic.PlayerCooldowns;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * THE WORLD STOP — глобальный серверный синглтон абсолютной остановки времени.
 *
 * <p>СОВЕРШЕННО ОТДЕЛЁН от {@code TemporalStasisEvents} (индивидуальный стазис
 * одной цели). Здесь только мировое состояние:
 * <ul>
 *   <li>{@link #isActive()} — читается server-обработчиками (entity tick freeze,
 *       damage capture, projectile suspension, mixin-остановка мира);</li>
 *   <li>таймер — ВНЕШНИЕ серверные тики через {@link ServerTickEvent}
 *       ({@value #STOP_DURATION_TICKS} = 6 секунд). Ни {@code entity.tickCount},
 *       ни {@code ServerLevel#getGameTime()} (он заморожен), ни длительность
 *       MobEffect в качестве часов НЕ используются;</li>
 *   <li>исключение — только кастер. Его биологическое время заморожено
 *       отдельно (см. {@code CasterPersonalTime}), здесь он лишь «exempt entity».</li>
 * </ul>
 *
 * <p>Потоковая модель: все мутации — строго с серверного потока. Клиент
 * ничего не знает кроме одного глобального состояния (WorldStop payloads →
 * {@code ClientWorldStopState}); пер-entity пакетов не существует by design.
 *
 * <p>Жизненный цикл: {@link #tryStart} атомарно (однопоточно) поднимает флаг;
 * {@link #end} идемпотентен и вызывается из таймера, смерти/выхода/смены
 * измерения кастера и остановки сервера. {@link #end} сбрасывает флаг ПЕРВЫМ
 * делом — после этого все перехваты (freeze/capture) мгновенно отключаются,
 * и выброс накопленного урона не может забфериться повторно.
 */
public final class GlobalTimeStopManager {

    /** Базовая длительность = 6 секунд (120 внешних серверных тиков) при 100% spell power.
     *  Реальная длительность скейлится от spell power (см. {@code TheWorldStopSpell#computeDurationTicks})
     *  и приходит в {@link #tryStart} отдельным параметром. */
    public static final int STOP_DURATION_TICKS = 120;

    /** Порог допустимого дрейфа замороженного игрока до возвратной якоризации. */
    private static final double ANCHOR_TOLERANCE_SQR = 1.0e-4D;

    private GlobalTimeStopManager() {
    }

    // =========================================================
    // СОСТОЯНИЕ (только серверный поток)
    // =========================================================

    private static boolean active;
    private static UUID casterUUID;
    private static int remainingTicks;
    private static MinecraftServer server;

    /** Кулдаун вешается ПОСЛЕ возобновления времени (§20), а не на касте. */
    private static AbstractSpell pendingCooldownSpell;
    private static CastSource pendingCooldownSource;

    /** Якоря позиций замороженных игроков — страховка от packet-дрейфа (§D). */
    private static final Map<UUID, Vec3> frozenAnchors = new HashMap<>();

    public static boolean isActive() {
        return active;
    }

    public static UUID getCasterUUID() {
        return casterUUID;
    }

    public static int getRemainingTicks() {
        return remainingTicks;
    }

    /** Кастер текущего стопа. */
    public static boolean isCaster(Entity entity) {
        return entity != null && casterUUID != null && entity.getUUID().equals(casterUUID);
    }

    /**
     * Исключение из мирового freeze: кастер и сущности, живущие вне остановленного
     * времени ({@link WorldStopExempt}: Chronomaly, Time Rift).
     */
    public static boolean isExempt(Entity entity) {
        return isCaster(entity) || WorldStopExempt.is(entity) || isTrialCasterProjectile(entity)
                || isCasterSands(entity);
    }

    /**
     * Струя Sands of Time кастера: это не выпущенный снаряд, а поток из его рук — пока кастер
     * действует, песок сыплется (иначе непрерывный каст в остановленном времени был бы пустым).
     * Струи тех, кто вне времени (двойники босса), и так живут — по {@link WorldStopExempt}.
     */
    public static boolean isCasterSands(Entity entity) {
        return entity instanceof com.chronomancy.entity.SandsOfTimeProjectile cone && isCaster(cone.getOwner());
    }

    /**
     * Пока идёт испытание Time Rift, снаряды кастера летят (иначе в бою против Chronomaly
     * работали бы только мгновенные заклинания).
     */
    public static boolean isTrialCasterProjectile(Entity entity) {
        return TimeRiftManager.isTrialActive()
                && entity instanceof net.minecraft.world.entity.projectile.Projectile projectile
                && isCaster(projectile.getOwner());
    }

    // =========================================================
    // СТАРТ
    // =========================================================

    /**
     * Атомарный синглтон-старт. Повторный вызов во время активного стопа
     * невозможен: второй каст отклоняется ещё на {@code SpellPreCastEvent}
     * (до списания маны), но и здесь двойная линия обороны.
     */
    public static boolean tryStart(ServerPlayer caster, AbstractSpell spell, CastSource castSource, int durationTicks) {
        if (active) {
            return false;
        }

        active = true;
        casterUUID = caster.getUUID();
        remainingTicks = Math.max(1, durationTicks);
        server = caster.server;
        pendingCooldownSpell = spell;
        pendingCooldownSource = castSource;

        // Фаза 4: слепок биологического времени кастера ДО того, как мир
        // остановлен и он начал действовать в замедленном для себя времени.
        CasterPersonalTime.begin(caster);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(casterUUID)) {
                continue;
            }
            // Прерываем чужие незавершённые касты: удержание RMB больше не
            // продолжится (клиенты заморожены), мана не списана — castSpell
            // выполняется только на завершении каста.
            MagicData.getPlayerMagicData(player).resetCastingState();
            frozenAnchors.put(player.getUUID(), player.position());
        }

        ChronoNetwork.broadcastWorldStopStart(caster.getId(), remainingTicks);
        ChronoStasisSounds.playWorldStopStart(caster.level(), caster);

        // Time Rift: ровно один бросок шанса на успешный старт (не на тик).
        TimeRiftManager.onWorldStopStarted(caster);

        ChronomancyMod.LOGGER.info("[WorldStop] TIME STOP STARTED by {} ({} external server ticks)",
                caster.getName().getString(), remainingTicks);
        return true;
    }

    // =========================================================
    // ТАЙМЕР + ЯКОРЯ (внешние серверные тики)
    // =========================================================

    /** Вызывается из {@code WorldStopEvents} на ServerTickEvent.Post. */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!active) {
            return;
        }

        enforceAnchors();

        // Фаза 4: пиновка биологического времени кастера (снапшот-реверт).
        CasterPersonalTime.pinCaster();

        // Испытание Time Rift держит время остановленным, пока не повержены все Chronomaly
        // (или кастер не погиб) — таймер стопа на паузе.
        if (TimeRiftManager.tickTrial(server)) {
            return;
        }

        remainingTicks--;
        if (remainingTicks <= 0) {
            end("expired");
        }
    }

    /**
     * Позиции замороженных игроков каноничны на момент старта: их клиенты
     * не генерируют movement packets (aiStep/tick отменён), но сервер
     * обрабатывает входящие пакеты вне entity-tick — возврат по якорю
     * закрывает и packet-дрейф, и сторонние толчки.
     */
    private static void enforceAnchors() {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(casterUUID)) {
                continue;
            }
            Vec3 anchor = frozenAnchors.get(player.getUUID());
            if (anchor == null) {
                // Вошёл во время стопа — якорим текущую позицию, с этого
                // момента игрок тоже заморожен.
                frozenAnchors.put(player.getUUID(), player.position());
                continue;
            }
            if (player.position().distanceToSqr(anchor) > ANCHOR_TOLERANCE_SQR) {
                player.setPos(anchor.x, anchor.y, anchor.z);
            }
        }
    }

    // =========================================================
    // ФИНАЛ (идемпотентен; достижим из ЛЮБОГО lifecycle-пути)
    // =========================================================

    /**
     * Единственная точка выхода. Порядок критичен:
     * <ol>
     *   <li>снять {@code active} — все freeze/capture-правила гаснут;</li>
     *   <li>разморозить снаряды (velocity restore, см. Phase 3);</li>
     *   <li>выбросить накопленный урон ровно один раз (см. Phase 3);</li>
     *   <li>отпустить пин кулдауна кастера — вешаем полный кулдаун ТОЛЬКО
     *       сейчас (§20: 120 s начинаются ПОСЛЕ возобновления времени);</li>
     *   <li>погасить клиентское состояние глобальным пакетом.</li>
     * </ol>
     */
    public static void end(String reason) {
        if (!active) {
            return;
        }
        active = false;

        UUID casterId = casterUUID;
        AbstractSpell spell = pendingCooldownSpell;
        CastSource source = pendingCooldownSource;
        pendingCooldownSpell = null;
        pendingCooldownSource = null;
        casterUUID = null;
        remainingTicks = 0;
        frozenAnchors.clear();

        if (server != null) {
            // Порядок: active уже снят (перехваты freeze/capture отключены).
            // 1) разморозить снаряды (вернуть сохранённую скорость);
            // 2) выбросить накопленный урон ровно один раз (hurt уже не
            //    буферизуется, т.к. active == false).
            ProjectilesSuspension.resumeAll(server);
            TemporalDamageBuffer.releaseAll(server);
            // Разлом закрывается при любом окончании стопа; уже вышедшие Chronomaly остаются.
            TimeRiftManager.onWorldStopEnded(server);

            ServerPlayer caster = server.getPlayerList().getPlayer(casterId);

            // Фаза 4: снятие пина биологического времени (эффекты/реген уже
            // идут штатно, active == false).
            CasterPersonalTime.releaseCaster();

            if (caster != null) {
                ChronoStasisSounds.playWorldStopEnd(caster.level(), caster);
                armResumeCooldown(caster, spell, source);
            }
        }
        server = null;

        ChronoNetwork.broadcastWorldStopEnd();

        ChronomancyMod.LOGGER.info("[WorldStop] TIME STOP ENDED ({})", reason);
    }

    /**
     * §20: полный кулдаун (effective 120 s с учётом атрибутов) вешается
     * ТОЛЬКО при возобновлении времени. Два вызывающих пути:
     * <ul>
     *   <li>{@link #end} — обычный финал по таймеру/край-кейсам;</li>
     *   <li>logout-хук ДО end — игрока уже нет в playerList, иначе кулдаун
     *       потерялся бы вместе с сессией.</li>
     * </ul>
     * addCooldown по ключу идемпотентен (map.put) — двойной вызов безопасен.
     */
    static void armResumeCooldown(ServerPlayer caster, AbstractSpell spell, CastSource source) {
        if (spell == null || caster == null) {
            return;
        }
        int effective = Math.max(1,
                MagicManager.getEffectiveSpellCooldown(spell, caster,
                        source == null ? CastSource.NONE : source));
        PlayerCooldowns cooldowns = MagicData.getPlayerMagicData(caster).getPlayerCooldowns();
        cooldowns.addCooldown(spell.getSpellId(), effective, effective);
        cooldowns.syncToPlayer(caster);
    }

    /**
     * Испытание Time Rift пройдено (или сработала страховка): время возобновится через
     * секунду — короткая пауза, чтобы игрок успел увидеть, что разломы схлопываются.
     */
    static void resumeSoon() {
        if (active) {
            remainingTicks = Math.min(Math.max(1, remainingTicks), 20);
        }
    }

    /**
     * Удар Chronomaly по кастеру применяется мгновенно (минует буфер) — фиксируем новое
     * здоровье в слепке биологического времени, иначе пин вернул бы его обратно.
     */
    public static void acceptCasterHealth(ServerPlayer caster) {
        if (active && isCaster(caster)) {
            CasterPersonalTime.acceptHealth(caster);
        }
    }

    /** Вызывается из logout-хука, пока поля стопа ещё живы. */
    static void armCooldownForLoggingOutCaster(ServerPlayer caster) {
        armResumeCooldown(caster, pendingCooldownSpell, pendingCooldownSource);
    }
}
