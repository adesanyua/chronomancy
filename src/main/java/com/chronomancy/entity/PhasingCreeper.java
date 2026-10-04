package com.chronomancy.entity;

import com.chronomancy.temporal.worldstop.WorldStopExempt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;

/** Фазирующий крипер: рывком сквозь время возникает у игрока за спиной. */
public class PhasingCreeper extends Creeper implements PhasingMob, WorldStopExempt {

    private final PhasingCore core = new PhasingCore(this, PhasingCore.Style.CLOSE_IN);

    public PhasingCreeper(EntityType<? extends Creeper> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Creeper.createAttributes()
                .add(Attributes.MAX_HEALTH, 24.0D);
    }

    @Override
    public void markFromRift() {
        core.fromRift = true;
    }

    /**
     * Цели крипера, но погоня — своя: ванильная {@code MeleeAttackGoal} завязана на игровое время и
     * во время остановки мира замирала (см. {@link PhasingChaseGoal}). Кошек порождение разлома не боится.
     */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.FloatGoal(this));
        this.goalSelector.addGoal(2, new net.minecraft.world.entity.ai.goal.SwellGoal(this));
        this.goalSelector.addGoal(4, new PhasingChaseGoal(this, 1.0D, false));
        this.goalSelector.addGoal(5, new net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(6, new net.minecraft.world.entity.ai.goal.LookAtPlayerGoal(
                this, net.minecraft.world.entity.player.Player.class, 8.0F));
        this.goalSelector.addGoal(6, new net.minecraft.world.entity.ai.goal.RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(
                this, net.minecraft.world.entity.player.Player.class, true));
        this.targetSelector.addGoal(2, new net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal(this));
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
