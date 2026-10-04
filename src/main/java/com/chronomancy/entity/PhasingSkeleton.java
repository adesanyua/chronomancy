package com.chronomancy.entity;

import com.chronomancy.temporal.worldstop.WorldStopExempt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.level.Level;

/** Фазирующий скелет: стреляет, а если игрок подошёл слишком близко — рывком уходит прочь. */
public class PhasingSkeleton extends Skeleton implements PhasingMob, WorldStopExempt {

    private final PhasingCore core = new PhasingCore(this, PhasingCore.Style.KEEP_AWAY);

    public PhasingSkeleton(EntityType<? extends Skeleton> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return AbstractSkeleton.createAttributes()
                .add(Attributes.MAX_HEALTH, 22.0D)
                .add(Attributes.ARMOR, 2.0D);
    }

    @Override
    public void markFromRift() {
        core.fromRift = true;
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
    protected boolean isSunBurnTick() {
        return false;
    }

    /** В порошковом снегу не превращается в зимогора — остаётся порождением разлома. */
    @Override
    protected void doFreezeConversion() {
        setFreezeConverting(false);
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
