package com.chronomancy.entity;

import com.chronomancy.temporal.BacktrackKnockbackSuppression;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

/** Real bow/crossbow echo. No pickup, no knockback, and no loss of the second hit to vanilla immunity. */
public final class ChronoDoubleArrowEntity extends Arrow {
    private boolean spectral;
    private int spectralDuration = 200;
    public ChronoDoubleArrowEntity(EntityType<? extends ChronoDoubleArrowEntity> type, Level level) {
        super(type, level);
        pickup = Pickup.DISALLOWED;
    }
    @Override public void tick() {
        super.tick();
        if (!level().isClientSide && tickCount > 200) discard();
    }
    @Override protected void onHitEntity(EntityHitResult hit) {
        if (hit.getEntity() instanceof LivingEntity target && !level().isClientSide) {
            int immunity = target.invulnerableTime;
            target.invulnerableTime = 0;
            try {
                BacktrackKnockbackSuppression.runWithoutKnockback(target, () -> super.onHitEntity(hit));
            } finally {
                target.invulnerableTime = Math.max(immunity, target.invulnerableTime);
            }
        } else super.onHitEntity(hit);
    }
    @Override protected void doKnockback(LivingEntity target, DamageSource source) { }
    @Override protected void doPostHurtEffects(LivingEntity target) {
        super.doPostHurtEffects(target);
        if (spectral) target.addEffect(new MobEffectInstance(MobEffects.GLOWING, spectralDuration), getOwner());
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        pickup = Pickup.DISALLOWED;
        spectral = tag.getBoolean("ChronoSpectral");
        spectralDuration = tag.contains("Duration") ? tag.getInt("Duration") : 200;
    }
    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("ChronoSpectral", spectral);
        tag.putInt("Duration", spectralDuration);
    }
}
