package com.chronomancy.network;

import com.chronomancy.ChronomancyMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Слои песка Sands of Time на сущности — клиент по ним красит цель в медь (чем больше слоёв, тем
 * сильнее). {@code ttl} — сколько тиков слои ещё держатся без нового импульса: клиент гасит медь сам,
 * отдельного пакета «песок осыпался» нет.
 */
public record SandStacksPayload(int entityId, int stacks, int ttl) implements CustomPacketPayload {

    public static final Type<SandStacksPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "sand_stacks"));

    public static final StreamCodec<ByteBuf, SandStacksPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SandStacksPayload::entityId,
            ByteBufCodecs.VAR_INT, SandStacksPayload::stacks,
            ByteBufCodecs.VAR_INT, SandStacksPayload::ttl,
            SandStacksPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
