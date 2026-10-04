package com.chronomancy.network;

import com.chronomancy.ChronomancyMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Синхронизация состояния «сущность в Temporal Stasis» на клиент.
 *
 * <p>Mob-эффекты НЕ синхронизируются клиентам, которые просто наблюдают сущность
 * (vanilla отправляет {@code ClientboundUpdateMobEffectPacket} только всадникам и
 * самому игроку). Поэтому клиент не может определить стазис через
 * {@code hasEffect}. Этот payload явно сообщает клиенту, какой entity-id сейчас
 * заморожен, чтобы клиентские render/tick-миксины могли сработать.
 */
public record StasisSyncPayload(int entityId, boolean frozen) implements CustomPacketPayload {

    public static final Type<StasisSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "stasis_sync"));

    public static final StreamCodec<ByteBuf, StasisSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, StasisSyncPayload::entityId,
                    ByteBufCodecs.BOOL, StasisSyncPayload::frozen,
                    StasisSyncPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
