package com.chronomancy.temporal;

import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.capabilities.magic.PlayerCooldowns;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shared integer personal clock; never runs a second full Player.tick. */
public final class TemporalPlayerClock {
    private static final Map<UUID, Double> PROGRESS = new HashMap<>();
    private static final Map<UUID, Integer> STEPS = new HashMap<>();
    private TemporalPlayerClock() {}

    public static int steps(ServerPlayer player) {
        return STEPS.getOrDefault(player.getUUID(), 1);
    }

    public static void onServerTick(ServerTickEvent.Pre event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            advance(player);
        }
    }

    /** Один серверный тик личных часов игрока: сколько личных тиков он прожил и что стало с перезарядками. */
    public static void advance(ServerPlayer player) {
        double rate = TemporalPlayerRate.server(player);
        UUID id = player.getUUID();
        if (Math.abs(rate - 1.0) < 1.0e-4) {
            PROGRESS.remove(id);
            STEPS.put(id, 1);
            return;
        }
        double progress = PROGRESS.getOrDefault(id, 0.0) + rate;
        int personalTicks = (int) Math.floor(progress);
        PROGRESS.put(id, progress - personalTicks);
        STEPS.put(id, personalTicks);
        int delta = personalTicks - 1;
        if (delta == 0) return;
        PlayerCooldowns cooldowns = MagicData.getPlayerMagicData(player).getPlayerCooldowns();
        // Borrowed Future собственную перезарядку не трогает: она идёт по обычным часам
        cooldowns.getSpellCooldowns().forEach((spellId, cooldown) -> {
            if (!com.chronomancy.spell.BorrowedFutureSpell.SPELL_ID.equals(spellId)) cooldown.decrementBy(delta);
        });
        cooldowns.getSpellCooldowns().entrySet().removeIf(e -> e.getValue().getCooldownRemaining() <= 0);
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        PROGRESS.remove(id);
        STEPS.remove(id);
        PersonalTimeMana.onLogout(id);
    }
    public static void onServerStopping(ServerStoppingEvent event) {
        PROGRESS.clear();
        STEPS.clear();
        PersonalTimeMana.clear();
    }
}
