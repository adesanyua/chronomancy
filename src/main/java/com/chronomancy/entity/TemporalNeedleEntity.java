package com.chronomancy.entity;

import com.chronomancy.registry.ChronoEntityTypeRegistry;
import com.chronomancy.registry.ChronoParticleRegistry;
import com.chronomancy.temporal.needle.TemporalNeedleManager;
import io.redspace.ironsspellbooks.entity.spells.AbstractMagicProjectile;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;

/** Only the flying needle is an entity. An impact never calls hurt or applies immediate damage. */
public final class TemporalNeedleEntity extends AbstractMagicProjectile {
    private int spellLevel = 1;
    private boolean flightSoundStarted;
    private Vec3 previousTrailPosition;
    private double trailTravel;
    public TemporalNeedleEntity(EntityType<? extends TemporalNeedleEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }
    public TemporalNeedleEntity(Level level, LivingEntity caster, int spellLevel) {
        this(ChronoEntityTypeRegistry.TEMPORAL_NEEDLE.get(), level);
        setOwner(caster);
        this.spellLevel = spellLevel;
    }
    @Override public float getSpeed() { return 3.0f; }
    @Override public float getHitDetectionInflation() { return 0.04f; }
    @Override public void handleHitDetection() {
        // Only server hits may consume a needle. Client prediction can be ahead inside dilation.
        if (!level().isClientSide) super.handleHitDetection();
    }
    @Override public void travel() {
        if (level().isClientSide) {
            // ISS uses setPos rather than Entity.move, so reuse the shared client rate explicitly.
            double rate = com.chronomancy.temporal.TemporalDilationHandler.clientMoveScale(this);
            setPos(position().add(getDeltaMovement().scale(rate)));
        } else super.travel();
    }
    @Override public void tick() {
        super.tick();
        if (level().isClientSide && !flightSoundStarted && !isRemoved()) {
            flightSoundStarted = true;
            com.chronomancy.client.NeedleFlightSound.start(this);
        }
        if (!level().isClientSide && tickCount > 30) discard();
    }
    @Override protected void onHitEntity(EntityHitResult hit) {
        if (isRemoved()) return;
        if (!level().isClientSide && hit.getEntity() instanceof LivingEntity target && getOwner() instanceof LivingEntity caster) {
            TemporalNeedleManager.hit(caster, target, spellLevel, hit.getLocation(), getDeltaMovement().normalize(),
                    com.chronomancy.temporal.chronodouble.ChronoDoubleProjectiles.chainOwner(this, caster),
                    com.chronomancy.temporal.chronodouble.ChronoDoubleProjectiles.fraction(this));
        }
        discard();
    }
    @Override protected void onHitBlock(BlockHitResult hit) { discard(); }
    @Override public void impactParticles(double x, double y, double z) { /* Chain owns impact visuals. */ }
    @Override public Optional<Holder<SoundEvent>> getImpactSound() { return Optional.empty(); }
    @Override public void trailParticles() {
        if (!level().isClientSide) return;
        Vec3 current = position();
        if (previousTrailPosition != null) {
            double distance = current.distanceTo(previousTrailPosition);
            if (distance > .04 && distance < 5) {
                // тонкая золотая спираль вокруг траектории иглы
                trailTravel = com.chronomancy.util.ProjectileStreams.spiral(level()::addParticle,
                        ChronoParticleRegistry.TEMPORAL_WISP_GOLD.get(), previousTrailPosition, current, trailTravel,
                        0.15, 1.0, 10.0, 32);
            }
        }
        previousTrailPosition = current;
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("NeedleLevel", spellLevel);
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        spellLevel = Math.max(1, Math.min(5, tag.getInt("NeedleLevel")));
    }
}
