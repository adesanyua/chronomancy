package com.chronomancy.effect;

import com.chronomancy.network.ChronoNetwork;
import com.chronomancy.registry.ChronoMobEffectRegistry;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Серверные хуки, которые синхронизируют состояние стазиса на клиент.
 *
 * <p>Специально вынесены в отдельный класс и работают через события
 * {@link MobEffectEvent} / {@link PlayerEvent.StartTracking}, чтобы НЕ трогать
 * рабочую механику {@code TemporalStasisEvents} / {@code TemporalStasisBeamSpell}.
 */
public final class TemporalStasisSyncEvents {

    private TemporalStasisSyncEvents() {
    }

    /** Старт стазиса: эффект добавлен (в т.ч. фолбэк от /effect). */
    public static void onEffectAdded(MobEffectEvent.Added event) {
        MobEffectInstance instance = event.getEffectInstance();

        if (instance != null && isStasis(instance.getEffect())) {
            ChronoNetwork.sendStasisSync(event.getEntity(), true);
            if (event.getEntity() instanceof ServerPlayer player) {
                ChronoNetwork.sendStasisSyncToPlayer(player, player.getId(), true);
            }
        }
    }

    /** Конец стазиса: эффект снят (разрыв по Damage Capacity и т.п.). */
    public static void onEffectRemove(MobEffectEvent.Remove event) {
        if (isStasis(event.getEffect())) {
            ChronoNetwork.sendStasisSync(event.getEntity(), false);
            if (event.getEntity() instanceof ServerPlayer player) {
                ChronoNetwork.sendStasisSyncToPlayer(player, player.getId(), false);
            }
        }
    }

    /**
     * Игрок только начал трекать сущность, которая уже в стазисе
     * (подошёл ближе / зашёл в чанк) — догоняем состояние точечно.
     */
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer player
                && event.getTarget() instanceof LivingEntity living
                && living.hasEffect(ChronoMobEffectRegistry.TEMPORAL_STASIS)) {
            ChronoNetwork.sendStasisSyncToPlayer(player, living.getId(), true);
        }
    }

    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && player.hasEffect(ChronoMobEffectRegistry.TEMPORAL_STASIS)) {
            ChronoNetwork.sendStasisSyncToPlayer(player, player.getId(), true);
        }
    }

    private static boolean isStasis(Holder<MobEffect> holder) {
        return holder != null
                && holder.value() == ChronoMobEffectRegistry.TEMPORAL_STASIS.value();
    }
}
