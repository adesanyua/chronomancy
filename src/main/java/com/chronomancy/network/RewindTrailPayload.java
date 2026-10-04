package com.chronomancy.network;

import com.chronomancy.ChronomancyMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

import java.util.List;

/**
 * Траектория Rewind: сервер выборочно сэмплирует старые позиции игрока
 * (каждые несколько тиков, максимум ~25 точек) и отправляет их как «нитку
 * afterimage». Клиент анимирует частицы ОТ текущей позиции К старой.
 *
 * <p>Никакой полный history-буфер клиенту не передаётся — только визуальные
 * точки. {@code entityId} — отладочная привязка к сущности-владельцу.
 */
public record RewindTrailPayload(int entityId, List<Vector3f> points) implements CustomPacketPayload {

    public static final Type<RewindTrailPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "rewind_trail"));

    public static final StreamCodec<ByteBuf, RewindTrailPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, RewindTrailPayload::entityId,
                    ByteBufCodecs.VECTOR3F.apply(ByteBufCodecs.list()), RewindTrailPayload::points,
                    RewindTrailPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
