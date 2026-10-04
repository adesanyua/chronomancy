package com.chronomancy.entity;

import com.chronomancy.temporal.worldstop.WorldStopExempt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

/** Фазирующий зомби: обычный зомби, но рывком сквозь время оказывается рядом с игроком. */
public class PhasingZombie extends Zombie implements PhasingMob, WorldStopExempt {

    private final PhasingCore core = new PhasingCore(this, PhasingCore.Style.CLOSE_IN);

    public PhasingZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Zombie.createAttributes()
                .add(Attributes.MAX_HEALTH, 26.0D)
                .add(Attributes.ARMOR, 3.0D);
    }

    @Override
    public void markFromRift() {
        core.fromRift = true;
    }

    /**
     * Свои цели вместо зомбячьих: ванильная атака завязана на игровое время и во время остановки
     * мира переставала работать (см. {@link PhasingChaseGoal}). Деревни, жители и яйца черепах
     * порождению разлома не интересны — оно охотится на игроков.
     */
    @Override
    protected void addBehaviourGoals() {
        this.goalSelector.addGoal(2, new PhasingChaseGoal(this, 1.0D, true));
        this.goalSelector.addGoal(7, new net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.targetSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(
                this, net.minecraft.world.entity.player.Player.class, true));
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            core.clientTick();
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        core.serverTick();
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        return super.canAttack(target) && PhasingCore.huntable(target);
    }

    @Override
    public boolean isAlliedTo(Entity other) {
        return ChronomalMob.is(other) || super.isAlliedTo(other);
    }

    /** Порождение разлома не горит на солнце и не зовёт обычных зомби на подмогу. */
    @Override
    protected boolean isSunSensitive() {
        return false;
    }

    @Override
    protected void randomizeReinforcementsChance() {
    }

    @Override
    protected boolean convertsInWater() {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        core.save(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        core.load(tag);
    }
}
