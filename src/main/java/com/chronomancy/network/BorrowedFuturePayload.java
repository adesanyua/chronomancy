package com.chronomancy.network;

import com.chronomancy.ChronomancyMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record BorrowedFuturePayload(int entityId, int phase, int remaining, int rateMilli)
        implements CustomPacketPayload {
    public static final Type<BorrowedFuturePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "borrowed_future_sync"));
    public static final StreamCodec<ByteBuf, BorrowedFuturePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BorrowedFuturePayload::entityId,
            ByteBufCodecs.VAR_INT, BorrowedFuturePayload::phase,
            ByteBufCodecs.VAR_INT, BorrowedFuturePayload::remaining,
            ByteBufCodecs.VAR_INT, BorrowedFuturePayload::rateMilli,
            BorrowedFuturePayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
