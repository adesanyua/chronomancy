package com.chronomancy.temporal.chronodouble;

import com.chronomancy.entity.ChronoDoubleEntity;
import com.chronomancy.registry.ChronoEntityTypeRegistry;
import com.chronomancy.registry.ChronoSpellRegistry;
import com.chronomancy.sound.ChronoDoubleSounds;
import com.chronomancy.spell.ChronoDoubleSpell;
import com.chronomancy.temporal.BacktrackKnockbackSuppression;
import io.redspace.ironsspellbooks.damage.DamageSources;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Owns lifecycle and melee echoes. Ranged echoes now travel as actual projectiles, not automatic hit bonuses. */
public final class ChronoDoubleManager {
    private static final Map<UUID, ChronoDoubleEntity> ACTIVE = new HashMap<>();
    private static final Map<UUID, Long> EXPIRY = new HashMap<>();
    private static boolean applying;
    private static long clock;
    private record Pending(ServerLevel world, UUID victim, float damage, UUID owner, UUID echo, long due) {}
    private static final List<Pending> QUEUE = new ArrayList<>();
    private ChronoDoubleManager() {}

    public static ChronoDoubleEntity active(ServerPlayer owner) {
        ChronoDoubleEntity echo = ACTIVE.get(owner.getUUID());
        return echo != null && !echo.isRemoved() && owner.isAlive() && !owner.isRemoved()
                && owner.level() == echo.level() ? echo : null;
    }
    public static void spawn(ServerPlayer owner, int level) {
        ServerLevel world = owner.serverLevel();
        removeFor(owner.getUUID());
        boolean left = world.random.nextBoolean();
        ChronoDoubleEntity echo = ChronoEntityTypeRegistry.CHRONO_DOUBLE.get().create(world);
        if (echo == null) return;
        echo.configure(owner, left, level);
        echo.setPos(ChronoDoubleGeometry.anchor(owner.position(), owner.getYRot(), left));
        echo.setAnchorRotation(owner.getYRot(), owner.getXRot());
        if (!world.addFreshEntity(echo)) return;
        ACTIVE.put(owner.getUUID(), echo);
        EXPIRY.put(owner.getUUID(), clock + ChronoDoubleSpell.DURATION_TICKS);
        ChronoDoubleSounds.playSummon(world, owner);
    }
    public static void onServerTick(ServerTickEvent.Post event) {
        clock++;
        List<UUID> remove = new ArrayList<>();
        for (var entry : ACTIVE.entrySet()) {
            var echo = entry.getValue();
            ServerPlayer owner = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (owner == null || active(owner) == null || clock >= EXPIRY.getOrDefault(entry.getKey(), 0L)) {
                remove.add(entry.getKey());
                continue;
            }
            echo.setPos(ChronoDoubleGeometry.anchor(owner.position(), owner.getYRot(), echo.isLeftSide()));
            echo.setAnchorRotation(owner.getYRot(), owner.getXRot());
        }
        remove.forEach(ChronoDoubleManager::removeFor);
        ChronoDoubleProjectiles.tick();
        // Applying damage can fire death/leave listeners; never apply while iterating mutable queues.
        List<Pending> due = new ArrayList<>();
        QUEUE.removeIf(p -> { if (clock < p.due) return false; due.add(p); return true; });
        due.forEach(ChronoDoubleManager::applyEcho);
    }
    public static void onOwnerIncomingDamage(LivingIncomingDamageEvent event) {
        if (applying || event.isCanceled() || event.getEntity().level().isClientSide) return;
        if (!event.getSource().is(DamageTypes.PLAYER_ATTACK)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer owner) || event.getSource().getDirectEntity() != owner) return;
        LivingEntity victim = event.getEntity();
        var echo = active(owner);
        if (echo == null || victim == owner || victim.level() != echo.level()) return;
        // A melee echo may not reach targets across the room.
        double range = owner.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE) + 1.5;
        if (victim.position().distanceToSqr(echo.position()) > range * range) return;
        float amount = event.getAmount() * ChronoDoubleSpell.damageFraction(echo.getSpellLevel(), owner);
        if (amount > 0) QUEUE.add(new Pending(owner.serverLevel(), victim.getUUID(), amount,
                owner.getUUID(), echo.getUUID(), clock + 2));
    }
    private static void applyEcho(Pending p) {
        ServerPlayer owner = p.world.getServer().getPlayerList().getPlayer(p.owner);
        if (owner == null) return;
        var echo = active(owner);
        if (echo == null || !echo.getUUID().equals(p.echo) || owner.level() != p.world) return;
        if (!(p.world.getEntity(p.victim) instanceof LivingEntity victim) || victim.isRemoved() || !victim.isAlive()) return;
        applying = true;
        int immunity = victim.invulnerableTime;
        victim.invulnerableTime = 0;
        try {
            BacktrackKnockbackSuppression.runWithoutKnockback(victim, () -> DamageSources.applyDamage(victim, p.damage,
                    ChronoSpellRegistry.CHRONO_DOUBLE_SPELL.getDamageSource(owner, owner)));
            ChronoDoubleSounds.playEcho(p.world, victim);
        } finally {
            victim.invulnerableTime = Math.max(immunity, victim.invulnerableTime);
            applying = false;
        }
    }
    private static void removeFor(UUID owner) {
        ChronoDoubleEntity echo = ACTIVE.remove(owner);
        EXPIRY.remove(owner);
        QUEUE.removeIf(p -> p.owner.equals(owner));
        ChronoDoubleProjectiles.removeFor(owner);
        ChronoDoubleSpellEcho.releaseFor(owner);
        if (echo != null && !echo.isRemoved()) echo.discard();
    }
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) removeFor(player.getUUID());
        UUID victim = event.getEntity().getUUID();
        QUEUE.removeIf(p -> p.victim.equals(victim));
    }
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) removeFor(player.getUUID());
    }
    public static void onUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        List<UUID> owners = ACTIVE.entrySet().stream().filter(e -> e.getValue().level() == level).map(Map.Entry::getKey).toList();
        owners.forEach(ChronoDoubleManager::removeFor);
        QUEUE.removeIf(p -> p.world == level);
        ChronoDoubleProjectiles.unload(level);
    }
    public static void onStop(ServerStoppedEvent event) {
        ACTIVE.clear(); EXPIRY.clear(); QUEUE.clear(); clock = 0; applying = false;
        ChronoDoubleProjectiles.clear();
        ChronoDoubleSpellEcho.clear();
    }
}
