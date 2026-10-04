package com.chronomancy.network;

import com.chronomancy.ChronomancyMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-only snapshots: each target/caster chain carries its complete bounded attachment list. */
public record TemporalNeedlePayload(CompoundTag data) implements CustomPacketPayload {
    public static final Type<TemporalNeedlePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "temporal_needle"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TemporalNeedlePayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.COMPOUND_TAG, TemporalNeedlePayload::data, TemporalNeedlePayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
