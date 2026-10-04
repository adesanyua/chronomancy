package com.chronomancy.network;

import com.chronomancy.ChronomancyMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Глобальное состояние World Stop для ВСЕХ клиентов одним пакетом
 * (никаких per-entity пакетов — стоп глобальный, клиенту достаточно
 * «активен ли стоп + entity id кастера»).
 */
public record WorldStopStartPayload(int casterEntityId, int durationTicks) implements CustomPacketPayload {

    public static final Type<WorldStopStartPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "world_stop_start"));

    public static final StreamCodec<ByteBuf, WorldStopStartPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, WorldStopStartPayload::casterEntityId,
                    ByteBufCodecs.VAR_INT, WorldStopStartPayload::durationTicks,
                    WorldStopStartPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
