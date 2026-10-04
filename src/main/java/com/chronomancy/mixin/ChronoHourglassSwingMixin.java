package com.chronomancy.mixin;

import com.chronomancy.item.ChronoCurioEvents;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Air swings count as attacks for the Hourglass's uninterrupted-rest requirement. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ChronoHourglassSwingMixin {
    @Inject(method="handleAnimate",at=@At("TAIL"))
    private void chronomancy$interruptRest(ServerboundSwingPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl)(Object)this;
        ChronoCurioEvents.onSwing(self.player);
    }
}
