package com.chronomancy.network;

import com.chronomancy.ChronomancyMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Идёт ли испытание Time Rift. Клиенту это нужно, чтобы не замораживать снаряды кастера:
 * во время испытания они летят и в остановленном времени.
 */
public record TimeRiftTrialPayload(boolean active) implements CustomPacketPayload {

    public static final Type<TimeRiftTrialPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "time_rift_trial"));

    public static final StreamCodec<ByteBuf, TimeRiftTrialPayload> STREAM_CODEC =
            ByteBufCodecs.BOOL.map(TimeRiftTrialPayload::new, TimeRiftTrialPayload::active);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
