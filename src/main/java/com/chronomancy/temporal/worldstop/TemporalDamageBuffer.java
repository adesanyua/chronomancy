package com.chronomancy.temporal.worldstop;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.spell.BacktrackSpell;
import com.chronomancy.temporal.BacktrackKnockbackSuppression;
import io.redspace.ironsspellbooks.damage.SpellDamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Фаза 3 — буфер урона The World Stop.
 *
 * <p>Пока время остановлено, ни одно попадание не применяется мгновенно:
 * каждое {@link LivingIncomingDamageEvent} перехватывается,cancelled и
 * складывается в порядок к жертве. HP мира не меняется весь стоп; на
 * возобновлении весь накопленный урон «выстреливает» разом.
 *
 * <p>ОТЛИЧИЕ от индивидуального стазиса: здесь НЕ схлопываем урон в одно
 * число — каждый удар хранится отдельно со своим реальным {@link DamageSource}
 * (тип урона ISS, атакующий, прямая сущность). Иначе потерялись бы
 * послевзрывы/эффекты разных типов. Поэтому буфер ДЕРЖИТ живые ссылки на
 * DamageSource в памяти (не NBT): стоп длится 6 секунд, все пути выхода
 * (таймер/смерть/логаут/смена измерения/остановка сервера) вызывают
 * {@link #releaseAll} в пределах этой сессии — сериализация не нужна.
 *
 * <p>Один владелец жертвы: цель, уже стоящая в индивидуальном стазисе, сюда
 * НЕ буферизуется (решение о пропуске принимает вызывающий код — см.
 * {@code WorldStopEvents#onIncomingDamage}), чтобы не было двух систем
 * накопления на одной сущности.
 */
public final class TemporalDamageBuffer {

    private TemporalDamageBuffer() {
    }

    /** Один перехваченный удар: реальный источник + величина + порядок. */
    private record BufferedHit(DamageSource source, float amount) {
    }

    // victim UUID → упорядоченный список ударов. Порядок = порядок добавления.
    private static final Map<UUID, List<BufferedHit>> BUFFER = new HashMap<>();
    private static boolean replayingDamage;

    /** Attack-time equipment bonuses must not be applied again to stored hits. */
    public static boolean isReplayingDamage() { return replayingDamage; }

    private static void replayHit(LivingEntity victim, DamageSource source, float amount) {
        boolean previous = replayingDamage;
        replayingDamage = true;
        try {
            victim.hurt(source, amount);
        } finally {
            replayingDamage = previous;
        }
    }

    /**
     * Буферизует удар и просит вызывающий код отменить событие. Вызывается
     * только с сервера, только когда стоп активен и жертва не в индивидуальном
     * стазисе.
     */
    public static void capture(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        BUFFER.computeIfAbsent(victim.getUUID(), k -> new ArrayList<>())
                .add(new BufferedHit(event.getSource(), event.getAmount()));
    }

    /**
     * Выброс всего накопленного урона ровно один раз. Порядок критичен:
     * <ol>
     *   <li>снапшот + очистка карты ДО применения — повторный hurt уже с
     *       {@code active == false} не забферится снова (защита от рекурсии);</li>
     *   <li>на каждый удар сбрасываем {@code invulnerableTime} — иначе
     *       vanilla-окно неуязвимости съело бы быстрые повторные удары;</li>
     *   <li>откатываем knockback у всех ударов, кроме последнего для жертвы
     *       (документированное поведение: «knockback подавлен, кроме финального»).</li>
     * </ol>
     */
    public static void releaseAll(MinecraftServer server) {
        if (BUFFER.isEmpty()) {
            return;
        }
        Map<UUID, List<BufferedHit>> snapshot = new HashMap<>(BUFFER);
        BUFFER.clear();

        int releasedHits = 0;
        for (Map.Entry<UUID, List<BufferedHit>> entry : snapshot.entrySet()) {
            LivingEntity victim = findLiving(server, entry.getKey());
            if (victim == null) {
                // Жертва выгружена/исчезла за 6 секунд — удары теряются
                // (приемлемо: окно короткое, все пути выхода синхронны).
                continue;
            }
            List<BufferedHit> hits = entry.getValue();
            for (int i = 0; i < hits.size(); i++) {
                if (victim.isRemoved()) {
                    break;
                }
                BufferedHit hit = hits.get(i);
                boolean last = i == hits.size() - 1;
                // Backtrack-удар не отбрасывает НИКОГДА, даже будучи последним
                // в буфере: это временная рана из прошлого, а не импульс.
                boolean temporalWound = isTemporalWoundSource(hit.source());
                Vec3 knockbackBefore = victim.getDeltaMovement();
                victim.invulnerableTime = 0;
                if (temporalWound) {
                    BacktrackKnockbackSuppression.runWithoutKnockback(
                            victim, () -> replayHit(victim, hit.source(), hit.amount()));
                } else {
                    replayHit(victim, hit.source(), hit.amount());
                }
                if (!last || temporalWound) {
                    victim.setDeltaMovement(knockbackBefore);
                }
                releasedHits++;
            }
        }
        if (releasedHits > 0) {
            ChronomancyMod.LOGGER.info("[WorldStop] released {} buffered hit(s) across {} victim(s)",
                    releasedHits, snapshot.size());
        }
    }

    /** Backtrack и схлопывание игл остаются без импульса даже после выпуска буфера. */
    private static boolean isTemporalWoundSource(DamageSource source) {
        return source.getDirectEntity() instanceof com.chronomancy.entity.ChronoDoubleArrowEntity
                || source instanceof SpellDamageSource spellSource
                && (spellSource.spell() instanceof BacktrackSpell
                || spellSource.spell() instanceof com.chronomancy.spell.TimePiercingNeedleSpell
                || spellSource.spell() instanceof com.chronomancy.spell.ChronoDoubleSpell);
    }

    private static LivingEntity findLiving(MinecraftServer server, UUID id) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null) {
            return player;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    /** Отладочный счётчик перехваченных ударов (используется тестами Phase 3). */
    public static int bufferedHitCount() {
        int total = 0;
        for (List<BufferedHit> hits : BUFFER.values()) {
            total += hits.size();
        }
        return total;
    }
}
