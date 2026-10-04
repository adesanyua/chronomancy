package com.chronomancy.client.entity;

import com.chronomancy.mixin.WalkAnimationStateAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Застывшая поза для остаточной копии: поворот тела/головы, наклон, фаза шага, взмах руки и
 * «возраст» сущности в момент, когда копия появилась.
 *
 * <p>Копия рисуется тем же рендерером, что и сама сущность, а он берёт позу из полей сущности.
 * Поэтому на время отрисовки копии поля подменяются снимком (и «старые» значения для
 * интерполяции — тоже, чтобы не было дрожи между кадрами), а потом восстанавливаются.
 */
public final class FrozenPose {
    private final float bodyYaw, headYaw, yRot, xRot, walkPos, walkSpeed, attack;
    private final int age;

    private FrozenPose(float bodyYaw, float headYaw, float yRot, float xRot, float walkPos, float walkSpeed,
                       float attack, int age) {
        this.bodyYaw = bodyYaw;
        this.headYaw = headYaw;
        this.yRot = yRot;
        this.xRot = xRot;
        this.walkPos = walkPos;
        this.walkSpeed = walkSpeed;
        this.attack = attack;
        this.age = age;
    }

    /** Снимок текущей позы (на последнем целом тике). */
    public static FrozenPose of(Entity entity) {
        if (entity instanceof LivingEntity living) {
            WalkAnimationStateAccessor walk = (WalkAnimationStateAccessor) living.walkAnimation;
            return new FrozenPose(living.yBodyRot, living.yHeadRot, living.getYRot(), living.getXRot(),
                    walk.chronomancy$getPosition(), walk.chronomancy$getSpeed(), living.attackAnim, living.tickCount);
        }
        return new FrozenPose(entity.getYRot(), entity.getYRot(), entity.getYRot(), entity.getXRot(), 0, 0, 0,
                entity.tickCount);
    }

    /** Рисует {@code draw} в застывшей позе и возвращает сущности её настоящую позу. */
    public void render(Entity entity, float partialTick, Runnable draw) {
        float yRot0 = entity.getYRot(), yRotO0 = entity.yRotO, xRot0 = entity.getXRot(), xRotO0 = entity.xRotO;
        int age0 = entity.tickCount;
        LivingEntity living = entity instanceof LivingEntity l ? l : null;
        float body0 = 0, bodyO0 = 0, head0 = 0, headO0 = 0, att0 = 0, attO0 = 0, wPos0 = 0, wSp0 = 0, wSpO0 = 0;
        WalkAnimationStateAccessor walk = null;
        if (living != null) {
            walk = (WalkAnimationStateAccessor) living.walkAnimation;
            body0 = living.yBodyRot; bodyO0 = living.yBodyRotO;
            head0 = living.yHeadRot; headO0 = living.yHeadRotO;
            att0 = living.attackAnim; attO0 = living.oAttackAnim;
            wPos0 = walk.chronomancy$getPosition(); wSp0 = walk.chronomancy$getSpeed(); wSpO0 = walk.chronomancy$getSpeedOld();
        }
        try {
            entity.setYRot(yRot);
            entity.yRotO = yRot;
            entity.setXRot(xRot);
            entity.xRotO = xRot;
            entity.tickCount = age;
            if (living != null) {
                living.yBodyRot = living.yBodyRotO = bodyYaw;
                living.yHeadRot = living.yHeadRotO = headYaw;
                living.attackAnim = living.oAttackAnim = attack;
                walk.chronomancy$setSpeed(walkSpeed);
                walk.chronomancy$setSpeedOld(walkSpeed);
                // position(partial) = position - speed * (1 - partial): компенсируем, чтобы фаза шага стояла.
                walk.chronomancy$setPosition(walkPos + walkSpeed * (1.0F - partialTick));
            }
            draw.run();
        } finally {
            entity.setYRot(yRot0);
            entity.yRotO = yRotO0;
            entity.setXRot(xRot0);
            entity.xRotO = xRotO0;
            entity.tickCount = age0;
            if (living != null) {
                living.yBodyRot = body0; living.yBodyRotO = bodyO0;
                living.yHeadRot = head0; living.yHeadRotO = headO0;
                living.attackAnim = att0; living.oAttackAnim = attO0;
                walk.chronomancy$setPosition(wPos0);
                walk.chronomancy$setSpeed(wSp0);
                walk.chronomancy$setSpeedOld(wSpO0);
            }
        }
    }
}
