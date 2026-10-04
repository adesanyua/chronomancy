package com.chronomancy.temporal;

import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.sound.ChronoBacktrackSounds;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import io.redspace.ironsspellbooks.damage.DamageSources;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Отложенный урон Backtrack (и будущих temporal-deferred эффектов) — ВНЕШНИЙ
 * планировщик на серверном времени.
 *
 * <p><b>Почему отдельный планировщик, а не {@code target.tickCount}:</b> цель
 * может стоять в Temporal Stasis, замедляться в Time Dilation или быть
 * замороженной World Stop — её личное время идёт иначе. Дедлайн Backtrack
 * считается по {@link com.chronomancy.temporal.ChronoClock} — внешним часам, которые идут и при World Stop (один и тот же
 * счётчик во всех измерениях), поэтому «1 секунда мирового времени» остаётся
 * ровно секундой независимо от того, что происходит с целью.
 *
 * <p><b>Каждое попадание — отдельный event.</b> Backtrack — спам-спелл с низким
 * кулдауном; три хита подряд дают три независимых дедлайна (t+1.0, t+1.4,
 * t+1.8), НИКОГДА не сливаются в один таймер.
 *
 * <p><b>Урон идёт через штатный поток.</b> Никакого прямого {@code hurt()} мимо
 * событий: применяем {@link DamageSources#applyDamage} с корректным
 * {@link io.redspace.ironsspellbooks.damage.SpellDamageSource} школы. Это
 * гарантирует, что если в момент дедлайна цель в Stasis/World Stop, урон
 * подхватится существующим temporal damage buffer'ом и будет выпущен на resume —
 * точно так же, как любой другой spell damage.
 *
 * <p><b>Защита от утечек.</b> Запись удаляется при: исполнении; смерти/снятии
 * цели; выгрузке кастера (нет причинного entity — нечего атрибутировать);
 * остановке сервера ({@link #clearAll}).
 *
 * <p>Потоковая безопасность: все мутации только с серверного потока.
 */
public final class BacktrackDamageQueue {

    /** Задержка в СЕРВЕРНЫХ тиках (=1 секунда мирового времени). */
    public static final int DAMAGE_DELAY_TICKS = 20;

    private BacktrackDamageQueue() {
    }

    /** Один отложенный удар: цель, кастер, дедлайн по gameTime и итоговый урон. */
    private static final class Pending {
        final UUID targetId;
        final UUID casterId;
        final long deadlineGameTime;
        final float damage;
        final AbstractSpell spell;
        final boolean echo;

        Pending(UUID targetId, UUID casterId, long deadlineGameTime, float damage, AbstractSpell spell, boolean echo) {
            this.targetId = targetId;
            this.casterId = casterId;
            this.deadlineGameTime = deadlineGameTime;
            this.damage = damage;
            this.spell = spell;
            this.echo = echo;
        }
    }

    private static final List<Pending> PENDING = new ArrayList<>();

    /**
     * Запланировать удар через {@value #DAMAGE_DELAY_TICKS} серверных тиков.
     * Вызывается из {@code BacktrackBoltEntity} в момент попадания (сервер).
     *
     * @param level  уровень попадания (отсчёт — {@code ChronoClock.now()})
     * @param damage уже посчитанное итоговое значение (уровень × Spell Power)
     */
    public static void schedule(ServerLevel level, LivingEntity target, LivingEntity caster,
                                float damage, AbstractSpell spell) {
        schedule(level, target, caster, damage, spell, false);
    }

    public static void schedule(ServerLevel level, LivingEntity target, LivingEntity caster,
                                float damage, AbstractSpell spell, boolean echo) {
        long now = com.chronomancy.temporal.ChronoClock.now();
        PENDING.add(new Pending(
                target.getUUID(), caster.getUUID(),
                now + DAMAGE_DELAY_TICKS, damage, spell, echo));
    }

    /** Каждый серверный тик: выпускаем всё, у чего наступил дедлайн. */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        // Единые мировые часы: gameTime идентичен во всех ServerLevel.
        long now = com.chronomancy.temporal.ChronoClock.now();

        List<Pending> due = new ArrayList<>();
        PENDING.removeIf(pending -> {
            if (now < pending.deadlineGameTime) return false;
            due.add(pending);
            return true;
        });
        // Damage can synchronously kill a target and remove other queued entries.
        for (Pending pending : due) {
            LivingEntity target = findLiving(server, pending.targetId);
            LivingEntity caster = findLiving(server, pending.casterId);
            if (target == null || !target.isAlive() || caster == null) {
                // цель мертва/выгружена или кастер исчез — нечего атрибутировать,
                // тихо забываем (memory-leak guard).
                continue;
            }

            applyThroughNormalFlow((ServerLevel) target.level(), target, caster, pending);
        }
    }

    /**
     * Наносим урон строго через боевой поток Iron's Spells: SpellDamageSource →
     * applyDamage → hurt → LivingIncomingDamageEvent. Если цель сейчас в
     * Stasis/World Stop, её входящий урон буферизуется существующей системой и
     * выйдет на resume — мы ничего не обходим.
     *
     * <p>NO KNOCKBACK: Backtrack — временная рана, догнавшая цель из прошлого,
     * а не новый физический импульс; vanilla knockback устроил бы «пинг-понг»
     * между позиционным откатом и отбрасыванием. Урон оборачивается в
     * {@link BacktrackKnockbackSuppression}: отменяется ровно LivingKnockBackEvent
     * этого hurt (HP, red flash, i-frames, сопротивления — штатно).
     * Выпущенный позже из буфера Stasis/World Stop удар оборачивается там же.
     */
    private static void applyThroughNormalFlow(ServerLevel level, LivingEntity target,
                                               LivingEntity caster, Pending pending) {
        int immunity = target.invulnerableTime;
        if (pending.echo) target.invulnerableTime = 0;
        try {
            BacktrackKnockbackSuppression.runWithoutKnockback(target, () ->
                    DamageSources.applyDamage(target, pending.damage,
                            pending.spell.getDamageSource(caster, caster)));
        } finally {
            if (pending.echo) target.invulnerableTime = Math.max(immunity, target.invulnerableTime);
        }

        // Визуал/звук «догнавшего» повреждения: Temporal Crack + короткий золотой
        // flash на цели. Отделяет момент teleport (позиция) от момента damage (время).
        double x = target.getX();
        double y = target.getY() + target.getBbHeight() * 0.5D;
        double z = target.getZ();
        MagicManager.spawnParticles(level, ChronoParticleRegistry.TEMPORAL_SPARK.get(),
                x, y, z, 8, 0.18, 0.32, 0.18, 0.06D, false);
        ChronoBacktrackSounds.playDelayedDamageCrack(level, target);
    }

    /**
     * Смерть любой сущности: мгновенно снимаем все её ожидающие удары (и как
     * цель, и как кастер) — мёртвому/ушедшему урон не нужен.
     */
    public static void onDeath(LivingDeathEvent event) {
        if (PENDING.isEmpty()) {
            return;
        }
        UUID dead = event.getEntity().getUUID();
        PENDING.removeIf(p -> p.targetId.equals(dead) || p.casterId.equals(dead));
    }

    /** Остановка сервера / выход из мира: полный сброс. */
    public static void onServerStopping(ServerStoppedEvent event) {
        clearAll();
    }

    public static void clearAll() {
        PENDING.clear();
    }

    /** Только для диагностики/тестов. */
    public static int pendingCount() {
        return PENDING.size();
    }

    @Nullable
    private static LivingEntity findLiving(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }
}
