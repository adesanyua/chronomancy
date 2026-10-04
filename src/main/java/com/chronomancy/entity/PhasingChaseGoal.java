package com.chronomancy.entity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Погоня и ближний бой фазирующих мобов разлома — замена ванильному {@code MeleeAttackGoal}.
 *
 * <p>Ванильная цель проверяет готовность по {@code level.getGameTime()}: не чаще раза в 20 тиков
 * игрового времени. Во время The World Stop игровое время стоит, поэтому, однажды остановившись
 * (дошёл до конца пути, потерял путь), она больше никогда не запускалась — зомби подходил к игроку
 * и замирал, крипер переставал преследовать. Эта цель считает всё своими тиками и от игрового
 * времени не зависит (как цели Chronomaly и медных двойников).
 */
final class PhasingChaseGoal extends Goal {
    private final PathfinderMob mob;
    private final double speed;
    /** Бьёт ли моб сам (зомби) или только сближается (крипер взрывается своей целью). */
    private final boolean attacks;
    private int repath;
    private int attackCooldown;

    PhasingChaseGoal(PathfinderMob mob, double speed, boolean attacks) {
        this.mob = mob;
        this.speed = speed;
        this.attacks = attacks;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean valid(LivingEntity target) {
        return target != null && target.isAlive() && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target);
    }

    @Override
    public boolean canUse() {
        return valid(mob.getTarget());
    }

    @Override
    public boolean canContinueToUse() {
        return valid(mob.getTarget());
    }

    @Override
    public void start() {
        mob.setAggressive(true);
        repath = 0;
        attackCooldown = 0;
    }

    @Override
    public void stop() {
        if (!valid(mob.getTarget())) {
            mob.setTarget(null);
        }
        mob.setAggressive(false);
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) {
            return;
        }
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (--repath <= 0) {
            // вдали путь пересчитывается реже — как у ванильной цели
            double distSqr = mob.distanceToSqr(target);
            repath = 4 + mob.getRandom().nextInt(7) + (distSqr > 1024.0D ? 10 : distSqr > 256.0D ? 5 : 0);
            if (!mob.getNavigation().moveTo(target, speed)) {
                repath += 15;
            }
        }
        if (attackCooldown > 0) {
            attackCooldown--;
        }
        if (attacks && attackCooldown <= 0 && mob.isWithinMeleeAttackRange(target)
                && mob.getSensing().hasLineOfSight(target)) {
            attackCooldown = 20;
            mob.swing(InteractionHand.MAIN_HAND);
            mob.doHurtTarget(target);
        }
    }
}
