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
 * «Шаг сквозь время» — визуал для клиентов:
 * <ul>
 *   <li>{@link #TIME_WALK} — Time Walk игрока: тело «тянется» вперёд по пути за {@code ticks} тиков
 *       (тот же язык, что у Rewind, только вперёд);</li>
 *   <li>{@link #BLINK} — телепорт хрономального моба (рывок/уклонение): короткая нить и силуэт
 *       в точке, откуда он исчез;</li>
 *   <li>{@link #PHASED} — сущность фазирована на {@code ticks} тиков: снаряды проходят сквозь неё.</li>
 * </ul>
 */
public record PhaseStepPayload(int entityId, int kind, int ticks, List<Vector3f> points) implements CustomPacketPayload {

    public static final int TIME_WALK = 0;
    public static final int BLINK = 1;
    public static final int PHASED = 2;

    public static final Type<PhaseStepPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "phase_step"));

    public static final StreamCodec<ByteBuf, PhaseStepPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PhaseStepPayload::entityId,
                    ByteBufCodecs.VAR_INT, PhaseStepPayload::kind,
                    ByteBufCodecs.VAR_INT, PhaseStepPayload::ticks,
                    ByteBufCodecs.VECTOR3F.apply(ByteBufCodecs.list()), PhaseStepPayload::points,
                    PhaseStepPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
