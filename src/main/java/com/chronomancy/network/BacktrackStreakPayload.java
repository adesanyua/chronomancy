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
 * Backtrack: короткая «обратная нить» между позицией попадания B и исторической
 * точкой A. Тот же формат точек, что у {@link RewindTrailPayload}, но ДРУГОЙ
 * тайпинг — клиент прогоняет её за считанные тики (мгновенный откат позиции),
 * а не полной 1.25-секундной нитью Rewind.
 *
 * <p>Сервер отправляет это сразу после успешного позиционного отката, чтобы
 * визуально объяснить: «позиция ушла СЮДА (в прошлое) сейчас».
 */
public record BacktrackStreakPayload(int entityId, List<Vector3f> points) implements CustomPacketPayload {

    public static final Type<BacktrackStreakPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "backtrack_streak"));

    public static final StreamCodec<ByteBuf, BacktrackStreakPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, BacktrackStreakPayload::entityId,
                    ByteBufCodecs.VECTOR3F.apply(ByteBufCodecs.list()), BacktrackStreakPayload::points,
                    BacktrackStreakPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
