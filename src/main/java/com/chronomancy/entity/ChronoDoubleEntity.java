package com.chronomancy.entity;

import com.chronomancy.registry.ChronoParticleRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.UUID;

/**
 * ChronoDouble anchor — the world-space stand-in for a caster's mirrored echo.
 *
 * <p>Like {@link RewindAfterimageEntity} this is a plain, non-combat {@link Entity}: no AI, no
 * attributes, not pickable, not saved. Its whole job is to (a) persist for the spell duration and
 * (b) expose a synced anchor position/rotation plus the {@code owner} UUID, so every tracking
 * client can find the real player and re-render their live, animated model there in copper (see
 * {@link com.chronomancy.client.ChronoDoubleRenderer}). The server manager keeps the anchor pinned
 * to the owner's mirrored side each tick; vanilla entity-tracking smooths it to clients.
 */
public class ChronoDoubleEntity extends Entity {

    /** Lifetime mirrors {@code ChronoDoubleSpell.DURATION_TICKS}; kept here for the discard guard. */
    private static final int LIFETIME_TICKS = 400;

    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(ChronoDoubleEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    /** True when the double was summoned to the owner's left; drives the mirrored anchor side. */
    private static final EntityDataAccessor<Boolean> DATA_LEFT_SIDE =
            SynchedEntityData.defineId(ChronoDoubleEntity.class, EntityDataSerializers.BOOLEAN);

    /** Snapshot of the spell level, frozen at cast so the echo damage stays constant. */
    private int spellLevel = 1;

    public ChronoDoubleEntity(EntityType<? extends ChronoDoubleEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_OWNER, Optional.empty());
        builder.define(DATA_LEFT_SIDE, false);
    }

    /** Bind the double to its caster and choose the summon side. Call BEFORE {@code addFreshEntity}. */
    public void configure(ServerPlayer owner, boolean leftSide, int spellLevel) {
        this.entityData.set(DATA_OWNER, Optional.of(owner.getUUID()));
        this.entityData.set(DATA_LEFT_SIDE, leftSide);
        this.spellLevel = spellLevel;
    }

    public UUID getOwnerUUID() {
        return this.entityData.get(DATA_OWNER).orElse(null);
    }

    public boolean isLeftSide() {
        return this.entityData.get(DATA_LEFT_SIDE);
    }

    public int getSpellLevel() {
        return this.spellLevel;
    }

    /** Public hook so the manager (outside the entity hierarchy) can pin the anchor's rotation. */
    public void setAnchorRotation(float yRot, float xRot) {
        this.setRot(yRot, xRot);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            tickEchoParticles();
        } else if (this.tickCount >= LIFETIME_TICKS) {
            this.discard();
        }
    }

    /** Constant copper-temporal energy rising off the mirror: motes every tick, an occasional spark. */
    private void tickEchoParticles() {
        for (int i = 0; i < 3; i++) {
            double ox = (this.random.nextDouble() - 0.5) * 0.7;
            double oz = (this.random.nextDouble() - 0.5) * 0.7;
            double oy = this.random.nextDouble() * 1.7;
            var type = this.random.nextInt(4) == 0
                    ? ChronoParticleRegistry.TEMPORAL_SPARK.get()
                    : ChronoParticleRegistry.TEMPORAL_MOTE.get();
            this.level().addParticle(type,
                    this.getX() + ox, this.getY() + oy, this.getZ() + oz,
                    ox * 0.012, 0.02 + this.random.nextDouble() * 0.02, oz * 0.012);
        }
    }

    /** Never targetable/pickable: the double is an echo, not a combat entity. */
    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // Short-lived and owner-bound; never persisted (matches RewindAfterimageEntity).
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
