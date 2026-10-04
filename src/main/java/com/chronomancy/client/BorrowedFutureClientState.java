package com.chronomancy.client;

import com.chronomancy.network.BorrowedFuturePayload;
import com.chronomancy.temporal.borrowed.BorrowedFuturePhase;
import com.chronomancy.client.particle.ChronoParticles;
import io.redspace.ironsspellbooks.player.ClientMagicData;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import java.util.HashMap;
import java.util.Map;

public final class BorrowedFutureClientState {
    private static final Map<Integer, BorrowedFuturePayload> STATES = new HashMap<>();
    private static double personalTickProgress;
    private static int localSteps = 1;

    public static int consumptionSteps(net.minecraft.world.entity.player.Player player) {
        return player == Minecraft.getInstance().player ? localSteps : 1;
    }
    private static final Map<Integer, Integer> TRANSITION_AGE = new HashMap<>();
    private static final Map<Integer, BorrowedFuturePhase> PREVIOUS_PHASE = new HashMap<>();
    private BorrowedFutureClientState() {}

    public static void apply(BorrowedFuturePayload payload) {
        if (phase(payload.entityId()) == BorrowedFuturePhase.EMPOWERED
                && payload.phase() == BorrowedFuturePhase.DEBT.ordinal()) ChronoParticles.spawnBorrowedSnap(payload.entityId());
        PREVIOUS_PHASE.put(payload.entityId(), phase(payload.entityId()));
        TRANSITION_AGE.put(payload.entityId(), 0);
        if (payload.phase() == BorrowedFuturePhase.NORMAL.ordinal()) STATES.remove(payload.entityId());
        else STATES.put(payload.entityId(), payload);
        personalTickProgress = 0;
        localSteps = 1;
    }

    public static BorrowedFuturePhase phase(int id) {
        BorrowedFuturePayload payload = STATES.get(id);
        return payload == null ? BorrowedFuturePhase.NORMAL : BorrowedFuturePhase.values()[payload.phase()];
    }

    public static double rate(int id) {
        BorrowedFuturePayload payload = STATES.get(id);
        return payload == null ? 1.0 : payload.rateMilli() / 1000.0;
    }

    public static java.util.Set<Integer> activeIds() { return java.util.Set.copyOf(STATES.keySet()); }

    public static int remainingTicks(int id) {
        BorrowedFuturePayload payload = STATES.get(id);
        return payload == null ? 0 : Math.max(0, payload.remaining() - TRANSITION_AGE.getOrDefault(id, 0));
    }

    public static float debtTintStrength(int id) {
        int age = TRANSITION_AGE.getOrDefault(id, 20);
        if (phase(id) == BorrowedFuturePhase.DEBT) return Math.min(1f, age / 8f);
        if (PREVIOUS_PHASE.get(id) == BorrowedFuturePhase.DEBT) return Math.max(0f, 1f - age / 8f);
        return 0;
    }

    public static void onPlayerTick(PlayerTickEvent.Pre event) {
        if (event.getEntity() != Minecraft.getInstance().player) return;
        double rate = TemporalPlayerClientRate.get(event.getEntity());
        if (Math.abs(rate - 1.0) < 1.0e-4) { personalTickProgress = 0; localSteps = 1; return; }
        personalTickProgress += rate;
        int personalTicks = (int) Math.floor(personalTickProgress);
        personalTickProgress -= personalTicks;
        localSteps = personalTicks;
        int delta = personalTicks - 1;
        if (delta == 0) return;
        // как на сервере: перезарядка самого Borrowed Future идёт по обычным часам
        ClientMagicData.getCooldowns().getSpellCooldowns().forEach((spellId, cooldown) -> {
            if (!com.chronomancy.spell.BorrowedFutureSpell.SPELL_ID.equals(spellId)) cooldown.decrementBy(delta);
        });
        ClientMagicData.getCooldowns().getSpellCooldowns().entrySet().removeIf(e -> e.getValue().getCooldownRemaining() <= 0);
    }

    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        TRANSITION_AGE.replaceAll((id, age) -> age + 1);
        TRANSITION_AGE.keySet().removeIf(id -> !STATES.containsKey(id)
                && TRANSITION_AGE.getOrDefault(id, 0) > 8);
        PREVIOUS_PHASE.keySet().retainAll(TRANSITION_AGE.keySet());
    }

    public static void onBreakSpeed(net.neoforged.neoforge.event.entity.player.PlayerEvent.BreakSpeed event) {
        if (!event.getEntity().level().isClientSide) return;
        double rate = TemporalPlayerClientRate.get(event.getEntity());
        if (rate != 1.0) event.setNewSpeed((float) (event.getNewSpeed() * rate));
    }

    public static void clear() { STATES.clear(); TRANSITION_AGE.clear(); PREVIOUS_PHASE.clear(); personalTickProgress = 0; localSteps = 1; }
}
