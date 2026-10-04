package com.chronomancy.network;

import com.chronomancy.ChronomancyMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Сущность стала (или перестала быть) медной копией заклинания Rift — клиент рисует её медной. */
public record RiftEchoPayload(int entityId, boolean echo) implements CustomPacketPayload {

    public static final Type<RiftEchoPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "rift_echo"));

    public static final StreamCodec<ByteBuf, RiftEchoPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RiftEchoPayload::entityId,
            ByteBufCodecs.BOOL, RiftEchoPayload::echo,
            RiftEchoPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
