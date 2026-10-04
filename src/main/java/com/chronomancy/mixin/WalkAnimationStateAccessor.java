package com.chronomancy.mixin;

import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к фазе шага: остаточные копии «замораживают» позу ног в момент своего появления. */
@Mixin(WalkAnimationState.class)
public interface WalkAnimationStateAccessor {
    @Accessor("speedOld") float chronomancy$getSpeedOld();
    @Accessor("speedOld") void chronomancy$setSpeedOld(float value);
    @Accessor("speed") float chronomancy$getSpeed();
    @Accessor("speed") void chronomancy$setSpeed(float value);
    @Accessor("position") float chronomancy$getPosition();
    @Accessor("position") void chronomancy$setPosition(float value);
}
