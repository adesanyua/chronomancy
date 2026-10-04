package com.chronomancy.entity;

import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.temporal.PhaseDodge;
import com.chronomancy.temporal.worldstop.GlobalTimeStopManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;

/**
 * Общая «начинка» фазирующих мобов (зомби, скелет, крипер): рывки сквозь время к игроку или от
 * него, бой только с кастером во время остановки времени, распад вне боя, если пришли из разлома.
 */
final class PhasingCore {

    /** Как ведёт себя рывок: к цели (зомби, крипер) или прочь от неё (скелет). */
    enum Style { CLOSE_IN, KEEP_AWAY }

    private static final int DECAY_TICKS = 1200;

    private final Monster mob;
    private final Style style;
    private int blinkCooldown = 60;
    private int idleTicks;
    boolean fromRift;

    PhasingCore(Monster mob, Style style) {
        this.mob = mob;
        this.style = style;
    }

    /** Во время стопа в бою участвует только кастер: остальные игроки заморожены. */
    static boolean huntable(LivingEntity candidate) {
        return !GlobalTimeStopManager.isActive() || GlobalTimeStopManager.isCaster(candidate);
    }

    void serverTick() {
        if (blinkCooldown > 0) {
            blinkCooldown--;
        }
        LivingEntity target = mob.getTarget();
        if (target != null && !huntable(target)) {
            mob.setTarget(null);
            target = null;
        }
        if (target != null && target.isAlive() && target.level() == mob.level() && blinkCooldown <= 0) {
            tryBlink(target);
        }
        if (fromRift && target == null && !GlobalTimeStopManager.isActive() && !mob.isPersistenceRequired()) {
            if (++idleTicks >= DECAY_TICKS) {
                decay();
            }
        } else {
            idleTicks = 0;
        }
    }

    private void tryBlink(LivingEntity target) {
        double dist = mob.distanceTo(target);
        var random = mob.getRandom();
        Vec3 dest = null;
        if (style == Style.CLOSE_IN) {
            if (dist > 6.0 && dist < 32.0) {
                // за спину или сбоку от цели
                float yaw = target.getYRot() + 180.0F + (random.nextFloat() - 0.5F) * 140.0F;
                double r = 1.6 + random.nextDouble() * 1.2;
                dest = target.position().add(-Mth.sin(yaw * Mth.DEG_TO_RAD) * r, 0, Mth.cos(yaw * Mth.DEG_TO_RAD) * r);
            }
        } else {
            if (dist < 5.0) {
                Vec3 away = mob.position().subtract(target.position()).multiply(1, 0, 1);
                if (away.lengthSqr() < 1.0e-4) {
                    away = new Vec3(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5);
                }
                away = away.normalize().yRot((random.nextFloat() - 0.5F) * 1.2F);
                dest = target.position().add(away.scale(9.0 + random.nextDouble() * 4.0));
            } else if (dist > 26.0) {
                Vec3 toward = target.position().subtract(mob.position()).normalize();
                dest = mob.position().add(toward.scale(dist - 12.0));
            }
        }
        if (dest == null) {
            blinkCooldown = 10; // проверим чуть позже
            return;
        }
        if (PhaseDodge.blink(mob, dest)) {
            mob.lookAt(target, 360.0F, 360.0F);
            blinkCooldown = (style == Style.CLOSE_IN ? 90 : 70) + random.nextInt(60);
        } else {
            blinkCooldown = 20;
        }
    }

    /** Без противника вне остановленного времени порождение разлома распадается. */
    private void decay() {
        if (mob.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ChronoParticleRegistry.TEMPORAL_SPARK.get(),
                    mob.getX(), mob.getY() + mob.getBbHeight() * 0.5, mob.getZ(), 12, 0.3, 0.5, 0.3, 0.02);
        }
        mob.discard();
    }

    /** Клиент: лёгкая пыль фазы вокруг тела, как у Chronomaly. */
    void clientTick() {
        if (mob.tickCount % 4 == 0) {
            var random = mob.getRandom();
            double w = mob.getBbWidth();
            mob.level().addParticle(ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                    mob.getX() + (random.nextDouble() - 0.5) * w, mob.getY() + random.nextDouble() * mob.getBbHeight(),
                    mob.getZ() + (random.nextDouble() - 0.5) * w, 0, 0.01, 0);
        }
    }

    void save(CompoundTag tag) {
        tag.putBoolean("ChronoFromRift", fromRift);
    }

    void load(CompoundTag tag) {
        fromRift = tag.getBoolean("ChronoFromRift");
    }
}
