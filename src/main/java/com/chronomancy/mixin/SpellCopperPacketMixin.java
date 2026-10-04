package com.chronomancy.mixin;

import com.chronomancy.client.SpellCopperClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Timeless Book: частицы заклинаний, которые присылает сервер (кольца ударов комет Starfall, туман,
 * вспышки попаданий), создаются при обработке пакета, а не в тике сущности. Здесь вокруг обработки
 * пакета открывается «медная область» (см. {@link SpellCopperClient#beginPacket}).
 *
 * <p>Обработчик сначала вызывается в сетевом потоке и перекидывает пакет в поток рендера —
 * область открываем только во втором, настоящем вызове.
 */
@Mixin(ClientPacketListener.class)
public abstract class SpellCopperPacketMixin {

    @Inject(method = "handleParticleEvent(Lnet/minecraft/network/protocol/game/ClientboundLevelParticlesPacket;)V",
            at = @At("HEAD"))
    private void chronomancy$copperPacketBegin(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) {
            SpellCopperClient.beginPacket(packet.getParticle(), packet.getX(), packet.getY(), packet.getZ());
        }
    }

    @Inject(method = "handleParticleEvent(Lnet/minecraft/network/protocol/game/ClientboundLevelParticlesPacket;)V",
            at = @At("RETURN"))
    private void chronomancy$copperPacketEnd(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) {
            SpellCopperClient.endPacket();
        }
    }
}
