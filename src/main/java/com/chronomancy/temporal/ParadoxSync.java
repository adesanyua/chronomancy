package com.chronomancy.temporal;

import com.chronomancy.network.ParadoxSyncPayload;
import com.chronomancy.registry.ChronoMobEffectRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Сообщает клиентам, на ком висит Парадокс (см. {@link ParadoxSyncPayload}). Идёт по событиям самого
 * эффекта, поэтому работает для любого источника: взрыв парадокса, команда {@code /effect}, молоко.
 */
public final class ParadoxSync {
    /** Срок «бесконечного» эффекта для клиента: счётчик всё равно продлевается при каждом пакете. */
    private static final int ENDLESS = 20 * 60 * 60;

    /** Раз в сколько тиков клиентам напоминают, что эффект ещё висит (клиент верит пакету 100 тиков). */
    private static final int RESYNC_INTERVAL = 40;
    /** На ком эффект висит сейчас — им и рассылаются напоминания. */
    private static final java.util.Map<java.util.UUID, java.lang.ref.WeakReference<LivingEntity>> AFFLICTED =
            new java.util.HashMap<>();

    private ParadoxSync() {
    }

    private static void track(LivingEntity entity) {
        AFFLICTED.put(entity.getUUID(), new java.lang.ref.WeakReference<>(entity));
    }

    /**
     * Напоминание клиентам. Клиент не знает точного срока эффекта (жертва тикает то быстрее, то
     * медленнее, а в чужой остановке мира не тикает вовсе) и держит визуал, пока сервер подтверждает,
     * что эффект на месте. Заодно это страховка: если пакет о снятии до клиента не дошёл или эффект
     * исчез мимо событий, следующий обход сообщит об этом сам.
     */
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (AFFLICTED.isEmpty() || ChronoClock.now() % RESYNC_INTERVAL != 0) {
            return;
        }
        var it = AFFLICTED.values().iterator();
        while (it.hasNext()) {
            LivingEntity entity = it.next().get();
            if (entity == null || entity.isRemoved()) {
                it.remove();
                continue;
            }
            MobEffectInstance instance = entity.isAlive() ? entity.getEffect(ChronoMobEffectRegistry.PARADOX) : null;
            if (instance == null) {
                send(entity, 0);
                it.remove();
            } else {
                send(entity, ticksOf(instance));
            }
        }
    }

    /** Возродившийся игрок получает прежний id сущности — клиентам ещё раз: эффекта на нём нет. */
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AFFLICTED.remove(player.getUUID());
            send(player, 0);
        }
    }

    public static void onStop(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        AFFLICTED.clear();
    }

    private static int ticksOf(MobEffectInstance instance) {
        return instance == null ? 0 : instance.isInfiniteDuration() ? ENDLESS : Math.max(1, instance.getDuration());
    }

    private static void send(LivingEntity entity, int ticks) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity,
                new ParadoxSyncPayload(entity.getId(), ticks, ChronoClock.now()));
    }

    public static void onAdded(MobEffectEvent.Added event) {
        MobEffectInstance added = event.getEffectInstance();
        if (event.getEntity().level().isClientSide || added == null || !added.is(ChronoMobEffectRegistry.PARADOX)) {
            return;
        }
        if (!event.getEntity().isAlive()) {
            return; // на умершего визуал не вешаем: после возрождения он достался бы живому игроку
        }
        track(event.getEntity());
        // более долгий уже висящий эффект новым не укорачивается
        send(event.getEntity(), Math.max(ticksOf(added), ticksOf(event.getOldEffectInstance())));
    }

    public static void onRemove(MobEffectEvent.Remove event) {
        if (!event.getEntity().level().isClientSide && event.getEffect().is(ChronoMobEffectRegistry.PARADOX)) {
            AFFLICTED.remove(event.getEntity().getUUID());
            send(event.getEntity(), 0);
        }
    }

    public static void onExpired(MobEffectEvent.Expired event) {
        MobEffectInstance expired = event.getEffectInstance();
        if (!event.getEntity().level().isClientSide && expired != null && expired.is(ChronoMobEffectRegistry.PARADOX)) {
            AFFLICTED.remove(event.getEntity().getUUID());
            send(event.getEntity(), 0);
        }
    }

    /** Умершему эффект больше не рисуем (игрок после возрождения получает тот же id сущности). */
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!entity.level().isClientSide && (entity.hasEffect(ChronoMobEffectRegistry.PARADOX)
                || AFFLICTED.containsKey(entity.getUUID()))) {
            AFFLICTED.remove(entity.getUUID());
            send(entity, 0);
        }
    }

    /** Игрок только что увидел существо, на котором Парадокс уже висит. */
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getTarget() instanceof LivingEntity target) {
            MobEffectInstance instance = target.getEffect(ChronoMobEffectRegistry.PARADOX);
            if (instance != null) {
                track(target); // в том числе загруженных из мира уже с эффектом
                PacketDistributor.sendToPlayer(player,
                        new ParadoxSyncPayload(target.getId(), ticksOf(instance), ChronoClock.now()));
            }
        }
    }
}
