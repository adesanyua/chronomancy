package com.chronomancy.temporal;

import com.chronomancy.entity.ChronomalMob;
import com.chronomancy.network.ChronoNetwork;
import com.chronomancy.network.PhaseStepPayload;
import com.chronomancy.registry.ChronoMobEffectRegistry;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.registry.ChronoSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Шаг сквозь время:
 * <ul>
 *   <li>сущность с эффектом «Фазирование» пропускает снаряды сквозь себя и не получает урон от них
 *       (Time Walk игрока, уклонение мобов);</li>
 *   <li>хрономальный моб, в которого вот-вот попадёт снаряд, с шансом {@value #DODGE_CHANCE}
 *       рывком уходит в сторону и фазируется на {@value #DODGE_PHASE_TICKS} тиков;</li>
 *   <li>{@link #blink} — общий телепорт хрономальных мобов (рывки к игроку / от игрока).</li>
 * </ul>
 */
public final class PhaseDodge {

    public static final float DODGE_CHANCE = 0.30F;
    public static final int DODGE_PHASE_TICKS = 60;

    /** Снаряды, по которым шанс уже бросался (каждый снаряд — один бросок на цель). */
    private static final Set<UUID> ROLLED = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
            return size() > 512;
        }
    });

    private PhaseDodge() {
    }

    public static boolean isPhased(Entity entity) {
        return entity instanceof LivingEntity living && living.hasEffect(ChronoMobEffectRegistry.TEMPORAL_PHASE);
    }

    /** Фазирует сущность на {@code ticks} тиков (эффект + визуал у клиентов). */
    public static void phase(LivingEntity entity, int ticks) {
        entity.addEffect(new MobEffectInstance(ChronoMobEffectRegistry.TEMPORAL_PHASE, ticks, 0, false, false, true));
        ChronoNetwork.broadcastPhaseStep(entity, PhaseStepPayload.PHASED, ticks, List.of());
    }

    // =========================================================
    // СОБЫТИЯ
    // =========================================================

    /** Снаряд вот-вот попадёт в сущность. */
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (!(event.getRayTraceResult() instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof LivingEntity target)
                || target.level().isClientSide) {
            return;
        }
        Projectile projectile = event.getProjectile();
        if (isPhased(target)) {
            event.setCanceled(true); // сквозь фазу — летит дальше
            return;
        }
        if (!ChronomalMob.is(target) || !target.isAlive()
                || (projectile.getOwner() != null && ChronomalMob.is(projectile.getOwner()))) {
            return;
        }
        UUID key = new UUID(projectile.getUUID().getMostSignificantBits() ^ target.getUUID().getLeastSignificantBits(),
                projectile.getUUID().getLeastSignificantBits());
        if (!ROLLED.add(key) || target.getRandom().nextFloat() >= DODGE_CHANCE) {
            return;
        }
        Vec3 dir = projectile.getDeltaMovement();
        if (dodge(target, dir)) {
            event.setCanceled(true);
        }
    }

    /** Урон от снарядов по фазированной сущности не проходит (в т.ч. «хитскан» снаряды). */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!isPhased(event.getEntity())) {
            return;
        }
        if (event.getSource().is(DamageTypeTags.IS_PROJECTILE)
                || event.getSource().getDirectEntity() instanceof Projectile) {
            event.setCanceled(true);
        }
    }

    // =========================================================
    // УКЛОНЕНИЕ / РЫВОК
    // =========================================================

    /** Рывок в сторону от линии снаряда + фазирование. */
    private static boolean dodge(LivingEntity mob, Vec3 incoming) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        Vec3 flat = new Vec3(incoming.x, 0, incoming.z);
        if (flat.lengthSqr() < 1.0e-4) {
            flat = new Vec3(1, 0, 0);
        }
        flat = flat.normalize();
        Vec3 side = new Vec3(-flat.z, 0, flat.x);
        RandomSource random = mob.getRandom();
        double sign = random.nextBoolean() ? 1.0 : -1.0;
        for (int attempt = 0; attempt < 8; attempt++) {
            double dist = 3.0 + random.nextDouble() * 3.0;
            double s = attempt % 2 == 0 ? sign : -sign;
            Vec3 dest = mob.position().add(side.scale(s * dist)).add(flat.scale((random.nextDouble() - 0.5) * 2.0));
            if (blink(mob, dest)) {
                phase(mob, DODGE_PHASE_TICKS);
                return true;
            }
        }
        // некуда уйти — хотя бы пропускаем этот снаряд сквозь себя
        phase(mob, DODGE_PHASE_TICKS);
        return true;
    }

    /**
     * Телепорт сквозь время в точку рядом с {@code dest} (с опорой под ногами и свободным местом).
     *
     * @return {@code false}, если подходящего места нет
     */
    public static boolean blink(LivingEntity entity, Vec3 dest) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        Vec3 spot = findStandSpot(level, entity, dest);
        if (spot == null) {
            return false;
        }
        Vec3 from = entity.position();
        if (entity instanceof com.chronomancy.entity.ChronomalyEntity chronomaly) {
            chronomaly.signalBlink();
        }
        entity.teleportTo(spot.x, spot.y, spot.z);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.resetFallDistance();
        entity.hurtMarked = true;
        if (entity instanceof Mob mob) {
            mob.getNavigation().stop();
        }
        double h = entity.getBbHeight() * 0.5;
        level.sendParticles(ChronoParticleRegistry.TEMPORAL_CRACK.get(), from.x, from.y + h, from.z, 6, 0.25, 0.4, 0.25, 0.03);
        level.sendParticles(ChronoParticleRegistry.TEMPORAL_MOTE.get(), spot.x, spot.y + h, spot.z, 10, 0.3, 0.5, 0.3, 0.0);
        level.playSound(null, from.x, from.y + h, from.z, ChronoSounds.TEMPORAL_CRACK.get(), SoundSource.HOSTILE,
                0.7F, 1.5F + entity.getRandom().nextFloat() * 0.3F);
        ChronoNetwork.broadcastPhaseStep(entity, PhaseStepPayload.BLINK, 5, List.of(from, spot));
        return true;
    }

    /** Ближайшая к {@code target} точка, где сущность помещается и стоит на твёрдом. */
    @Nullable
    public static Vec3 findStandSpot(ServerLevel level, Entity entity, Vec3 target) {
        int[] dys = {0, -1, 1, -2, 2, -3, 3};
        double[][] offsets = {{0, 0}, {0.8, 0}, {-0.8, 0}, {0, 0.8}, {0, -0.8}, {1.2, 1.2}, {-1.2, -1.2}};
        for (double[] o : offsets) {
            for (int dy : dys) {
                Vec3 p = new Vec3(Mth.floor(target.x + o[0]) + 0.5, Mth.floor(target.y) + dy, Mth.floor(target.z + o[1]) + 0.5);
                BlockPos feet = BlockPos.containing(p);
                if (!level.isLoaded(feet)) {
                    continue;
                }
                AABB box = entity.getDimensions(entity.getPose()).makeBoundingBox(p);
                if (!level.noCollision(entity, box) || level.containsAnyLiquid(box)) {
                    continue;
                }
                BlockState below = level.getBlockState(feet.below());
                if (below.getCollisionShape(level, feet.below()).isEmpty() || below.getFluidState().isSource()) {
                    continue;
                }
                if (below.is(net.minecraft.world.level.block.Blocks.LAVA) || below.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)) {
                    continue;
                }
                return p;
            }
        }
        return null;
    }
}
