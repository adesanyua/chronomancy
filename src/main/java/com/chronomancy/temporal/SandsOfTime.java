package com.chronomancy.temporal;

import com.chronomancy.effect.TemporalStasisEvents;
import com.chronomancy.registry.ChronoMobEffectRegistry;
import com.chronomancy.registry.ChronoParticleRegistry;
import io.redspace.ironsspellbooks.damage.DamageSources;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ПЕСКИ ВРЕМЕНИ — общая механика конуса песка (заклинание Sands of Time и приём Rift Maker).
 *
 * <p>Конус бьёт импульсами, два импульса в секунду обычного каста. На каждой полной секунде под
 * струёй цель получает ещё один «слой песка»:
 * <ul>
 *   <li>её личное время замедляется на {@value #SLOW_PERCENT_PER_STACK}% за слой;</li>
 *   <li>урон самого заклинания по ней падает на {@value #SLOW_PERCENT_PER_STACK}% за слой
 *       (10 → 8 → 6 → 4 → 2 в секунду при начальном уроне 10);</li>
 *   <li>пять слоёв — вся длительность каста — и цель застывает в стазисе на
 *       {@value #STASIS_TICKS} тиков, после чего счёт начинается заново.</li>
 * </ul>
 * Слои осыпаются, если струя не касалась цели {@value #LINGER_TICKS} тиков. Счёт ведётся по
 * серверным часам ({@link ChronoClock}), а не по времени самой цели: замедленный моб тикает реже,
 * и срок, считанный его тиками, растягивался бы вместе с ним.
 */
public final class SandsOfTime {
    /** Импульсов конуса на один слой песка (импульс — раз в полсекунды обычного каста). */
    public static final int PULSES_PER_STACK = 2;
    /** Столько слоёв — и цель в стазисе. */
    public static final int MAX_STACKS = 5;
    public static final int SLOW_PERCENT_PER_STACK = 20;
    /** Стазис в конце полного каста по умолчанию; действующее значение — {@link #stasisTicks()} (конфиг). */
    public static final int STASIS_TICKS = 40;

    public static int stasisTicks() {
        return com.chronomancy.ChronoConfig.sandsStasisTicks();
    }
    /** Сколько тиков слои держатся без новой порции песка. */
    public static final int LINGER_TICKS = 30;
    /** Урон по застывшему, после которого стазис разбивается. */
    private static final float STASIS_DAMAGE_CAP = 20.0F;

    private record State(int pulses, long lastHit) {
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private SandsOfTime() {
    }

    /** Слоёв песка на цели сейчас (0 — нет). */
    public static int stacks(Entity entity) {
        if (STATES.isEmpty()) {
            return 0;
        }
        State state = STATES.get(entity.getUUID());
        if (state == null) {
            return 0;
        }
        if (ChronoClock.now() - state.lastHit() > LINGER_TICKS) {
            STATES.remove(entity.getUUID());
            return 0;
        }
        return Math.min(MAX_STACKS - 1, state.pulses() / PULSES_PER_STACK);
    }

    /** Личный темп цели под песком: 1.0 — нет слоёв, 0.2 — четыре слоя. */
    public static double rate(Entity entity) {
        int stacks = stacks(entity);
        return stacks <= 0 ? TemporalRate.NORMAL : 1.0D - stacks * SLOW_PERCENT_PER_STACK / 100.0D;
    }

    /**
     * Один импульс конуса по цели.
     *
     * @param damagePerSecond начальный урон в секунду (до ослабления слоями)
     * @return нанесённый этим импульсом урон (до брони и сопротивлений)
     */
    public static float pulse(LivingEntity caster, LivingEntity target, float damagePerSecond, DamageSource source) {
        if (target == caster || !target.isAlive() || target.level().isClientSide) {
            return 0.0F;
        }
        boolean timeless = TimeMagicImmunity.isImmune(target);
        boolean frozen = target.hasEffect(ChronoMobEffectRegistry.TEMPORAL_STASIS);
        long now = ChronoClock.now();
        State state = STATES.get(target.getUUID());
        if (state != null && now - state.lastHit() > LINGER_TICKS) {
            state = null;
        }
        int pulses = state == null ? 0 : state.pulses();
        int stacksBefore = timeless ? 0 : Math.min(MAX_STACKS, pulses / PULSES_PER_STACK);
        float damage = damagePerSecond / PULSES_PER_STACK
                * Math.max(0.0F, 1.0F - stacksBefore * SLOW_PERCENT_PER_STACK / 100.0F);
        if (damage > 0.0F) {
            // Кадры неуязвимости считает сама цель, своими тиками: у замедленной они тянутся дольше, и
            // следующий импульс в них бы упёрся. Частоту ударов здесь уже задаёт ритм конуса.
            int immunity = target.invulnerableTime;
            target.invulnerableTime = 0;
            try {
                DamageSources.applyDamage(target, damage, source);
            } finally {
                target.invulnerableTime = Math.max(immunity, target.invulnerableTime);
            }
        }
        if (timeless || frozen || !target.isAlive()) {
            return damage; // вне времени или уже застыл: слои не копятся
        }
        pulses++;
        if (pulses >= MAX_STACKS * PULSES_PER_STACK) {
            // Полный каст: цель застывает, а песок на ней остаётся — все четыре слоя. Пока она стоит,
            // он не осыпается: срок слоёв отсчитывается от конца стазиса. Если стазис не лёг (цель
            // устала от стазисов), слои просто держатся дальше.
            int kept = (MAX_STACKS - 1) * PULSES_PER_STACK;
            long stasis = freeze(target);
            if (stasis > 0L) {
                com.chronomancy.advancement.ChronoAdvancements.grant(source.getEntity(),
                        com.chronomancy.advancement.ChronoAdvancements.STASIS);
            }
            STATES.put(target.getUUID(), new State(kept, now + stasis));
            mark(target, MAX_STACKS - 1, (int) stasis + LINGER_TICKS);
            return damage;
        }
        STATES.put(target.getUUID(), new State(pulses, now));
        mark(target, pulses / PULSES_PER_STACK, LINGER_TICKS);
        return damage;
    }

    /**
     * Значок слоёв на цели и рассылка всем, кто её видит: клиент красит цель в медь тем сильнее, чем
     * слоёв больше. {@code ticks} — сколько слои ещё продержатся без новой порции песка.
     */
    private static void mark(LivingEntity target, int stacks, int ticks) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(target,
                new com.chronomancy.network.SandStacksPayload(target.getId(), stacks, ticks));
        if (stacks > 0) {
            // только значок и подсказка: сам темп считается по STATES
            target.addEffect(new MobEffectInstance(ChronoMobEffectRegistry.SANDS_OF_TIME, ticks + 10,
                    stacks - 1, false, false, true));
        }
    }

    /**
     * Цель со слоями песка попала в стазис (от песков или от чего угодно): пока она стоит, песок не
     * осыпается — срок слоёв начнёт идти, когда стазис кончится. Зовёт {@code TemporalStasisEvents}.
     */
    public static void onStasis(LivingEntity target, long stasisTicks) {
        if (STATES.isEmpty() || target.level().isClientSide) {
            return;
        }
        State state = STATES.get(target.getUUID());
        long now = ChronoClock.now();
        if (state == null || now - state.lastHit() > LINGER_TICKS) {
            return;
        }
        STATES.put(target.getUUID(), new State(state.pulses(), now + Math.max(0L, stasisTicks)));
        mark(target, Math.min(MAX_STACKS - 1, state.pulses() / PULSES_PER_STACK), (int) stasisTicks + LINGER_TICKS);
    }

    /** @return сколько тиков цель простоит на самом деле (0 — стазис не лёг: цель устала от стазисов). */
    private static long freeze(LivingEntity target) {
        int ticks = stasisTicks();
        long actual = 0L;
        if (ticks > 0 && target.addEffect(new MobEffectInstance(ChronoMobEffectRegistry.TEMPORAL_STASIS, ticks, 0))) {
            actual = TemporalStasisEvents.initializeStasis(target, 1, STASIS_DAMAGE_CAP, ticks);
        }
        if (actual <= 0L) {
            return 0L;
        }
        if (target.level() instanceof ServerLevel level) {
            level.sendParticles(ChronoParticleRegistry.TEMPORAL_SAND_GRAIN.get(), target.getX(),
                    target.getY() + target.getBbHeight() * 0.5D, target.getZ(), 30,
                    target.getBbWidth() * 0.5D, target.getBbHeight() * 0.4D, target.getBbWidth() * 0.5D, 0.02D);
        }
        return actual;
    }

    public static void clear(Entity entity) {
        STATES.remove(entity.getUUID());
    }

    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            STATES.remove(event.getEntity().getUUID());
        }
    }

    public static void onStop(ServerStoppingEvent event) {
        STATES.clear();
    }
}
