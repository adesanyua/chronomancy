package com.chronomancy.temporal;

import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import io.redspace.ironsspellbooks.network.SyncManaPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Replays ISS's own mana regeneration formula at personal-time intervals (normally every 10 ticks). */
public final class PersonalTimeMana {
    private static final Map<UUID, Integer> PROGRESS = new HashMap<>();
    private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> false);
    private PersonalTimeMana() {}

    public static boolean isReplaying() { return REPLAYING.get(); }

    public static boolean controls(ServerPlayer player) {
        return Math.abs(TemporalPlayerRate.server(player) - 1.0) > 1.0e-4;
    }

    public static void onMagicTick(MagicManager manager, Level level) {
        for (var entity : level.players()) {
            if (!(entity instanceof ServerPlayer player)) continue;
            UUID id = player.getUUID();
            if (!controls(player)) {
                PROGRESS.remove(id);
                continue; // ISS continues to own ordinary regeneration.
            }
            int progress = PROGRESS.getOrDefault(id, 0) + TemporalPlayerClock.steps(player);
            int pulses = progress / MagicManager.MANA_REGEN_TICKS;
            PROGRESS.put(id, progress % MagicManager.MANA_REGEN_TICKS);
            if (pulses == 0) continue;
            MagicData data = MagicData.getPlayerMagicData(player);
            boolean changed = false;
            REPLAYING.set(true);
            try {
                for (int i = 0; i < pulses; i++) {
                    if (manager.regenPlayerMana(player, data)) changed = true;
                }
            } finally {
                REPLAYING.remove();
            }
            if (changed) PacketDistributor.sendToPlayer(player, new SyncManaPacket(data));
        }
    }

    public static void onLogout(UUID id) { PROGRESS.remove(id); }
    public static void clear() { PROGRESS.clear(); }
}
