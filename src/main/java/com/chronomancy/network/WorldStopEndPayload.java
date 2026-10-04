package com.chronomancy.network;

import com.chronomancy.ChronomancyMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Конец глобального World Stop. Пакет-маркер: клиенту достаточно сбросить
 * единственное глобальное состояние ({@code ClientWorldStopState.clear()}).
 */
public record WorldStopEndPayload() implements CustomPacketPayload {

    public static final Type<WorldStopEndPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "world_stop_end"));

    public static final StreamCodec<ByteBuf, WorldStopEndPayload> STREAM_CODEC =
            StreamCodec.unit(new WorldStopEndPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
