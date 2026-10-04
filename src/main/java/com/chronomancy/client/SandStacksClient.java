package com.chronomancy.client;

import com.chronomancy.network.SandStacksPayload;
import com.chronomancy.temporal.SandsOfTime;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Клиентская сторона слоёв песка Sands of Time: цель под струёй медленно «заносит» медью — с каждым
 * слоем замедления она всё более медная, на четырёх слоях медная целиком (дальше — стазис).
 * Сила меди меняется плавно; саму окраску делает {@link CopperEchoRendering#wrap(net.minecraft.client.renderer.MultiBufferSource, float)}.
 */
public final class SandStacksClient {
    /** За сколько тиков медь набирает один слой и за сколько осыпается. */
    private static final float RISE_PER_TICK = 1.0F / (SandsOfTime.MAX_STACKS - 1) / 8.0F;
    private static final float FALL_PER_TICK = 0.08F;

    private static final class State {
        int stacks;
        int ttl;
        float shown;
    }

    private static final Int2ObjectMap<State> STATES = new Int2ObjectOpenHashMap<>();

    private SandStacksClient() {
    }

    public static void apply(SandStacksPayload payload) {
        State state = STATES.get(payload.entityId());
        if (state == null) {
            if (payload.stacks() <= 0) {
                return;
            }
            state = new State();
            STATES.put(payload.entityId(), state);
        }
        state.stacks = Math.max(0, payload.stacks());
        state.ttl = Math.max(0, payload.ttl());
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        if (STATES.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            STATES.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        var it = STATES.int2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            State state = entry.getValue();
            if (state.ttl > 0) {
                state.ttl--;
            } else {
                state.stacks = 0;
            }
            float target = Math.min(1.0F, state.stacks / (float) (SandsOfTime.MAX_STACKS - 1));
            if (state.shown < target) {
                state.shown = Math.min(target, state.shown + RISE_PER_TICK);
            } else if (state.shown > target) {
                state.shown = Math.max(target, state.shown - FALL_PER_TICK);
            }
            if ((state.stacks == 0 && state.shown <= 0.0F) || mc.level.getEntity(entry.getIntKey()) == null) {
                it.remove();
            }
        }
    }

    /** Насколько сущность сейчас медная от песка: 0 — нет, 1 — целиком. */
    public static float strength(Entity entity) {
        if (entity == null || STATES.isEmpty()) {
            return 0.0F;
        }
        State state = STATES.get(entity.getId());
        return state == null ? 0.0F : state.shown;
    }

    public static void clear() {
        STATES.clear();
    }
}
