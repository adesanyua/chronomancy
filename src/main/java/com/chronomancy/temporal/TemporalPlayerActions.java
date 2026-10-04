package com.chronomancy.temporal;

import com.chronomancy.registry.ChronoMobEffectRegistry;
import com.chronomancy.effect.TemporalStasisEvents;
import io.redspace.ironsspellbooks.api.events.SpellPreCastEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Packet-driven actions must be gated separately from entity ticks. */
public final class TemporalPlayerActions {
    private TemporalPlayerActions() {}

    private static boolean frozen(Player player) {
        return player instanceof ServerPlayer && player.hasEffect(ChronoMobEffectRegistry.TEMPORAL_STASIS);
    }

    public static void onSpellPreCast(SpellPreCastEvent event) {
        if (frozen(event.getEntity())) event.setCanceled(true);
    }
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (frozen(event.getEntity())) event.setCanceled(true);
    }
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (frozen(event.getEntity())) event.setCanceled(true);
    }
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (frozen(event.getEntity())) event.setCanceled(true);
    }
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (frozen(event.getEntity())) event.setCanceled(true);
    }
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (frozen(event.getEntity())) event.setCanceled(true);
    }
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getSource().getEntity() instanceof Player player && frozen(player)) event.setCanceled(true);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            TemporalStasisEvents.pinFrozenPlayer(player);
        }
    }
}
