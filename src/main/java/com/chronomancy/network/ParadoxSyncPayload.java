package com.chronomancy.network;

import com.chronomancy.ChronomancyMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Эффект Парадокса на сущности — для клиентского визуала. Ванильные эффекты чужих существ на клиент
 * не приходят, поэтому сервер сообщает сам: {@code ticks} — сколько эффект ещё продержится (0 — снят),
 * {@code clock} — серверные часы {@code ChronoClock} в момент отправки, чтобы клиент качал визуал в
 * такт настоящим качелям времени.
 */
public record ParadoxSyncPayload(int entityId, int ticks, long clock) implements CustomPacketPayload {

    public static final Type<ParadoxSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "paradox_sync"));

    public static final StreamCodec<ByteBuf, ParadoxSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ParadoxSyncPayload::entityId,
            ByteBufCodecs.VAR_INT, ParadoxSyncPayload::ticks,
            ByteBufCodecs.VAR_LONG, ParadoxSyncPayload::clock,
            ParadoxSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
