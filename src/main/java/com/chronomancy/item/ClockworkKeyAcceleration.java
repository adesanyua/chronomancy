package com.chronomancy.item;

import com.chronomancy.registry.ChronoItemRegistry;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.*;

/** Extras are injected after a block entity's regular tick; applies to vanilla and modded tickers. */
public final class ClockworkKeyAcceleration {
    private ClockworkKeyAcceleration() {}
    private record Source(BlockPos pos, double multiplier) {}
    private static final Map<ServerLevel, List<Source>> ACTIVE = new WeakHashMap<>();

    public static void onLevelPre(LevelTickEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        List<Source> sources = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (ChronoItemRegistry.CLOCKWORK_KEY.get().isEquippedBy(player)) {
                // Base +100% speed; +1% speed per 1% generic ISS spell power.
                // Spell power is 1.0 at baseline, so baseline key = 3x total speed.
                double power = Math.max(0, player.getAttributeValue(AttributeRegistry.SPELL_POWER));
                sources.add(new Source(player.blockPosition(), 2 + power));
            }
        }
        if (sources.isEmpty()) ACTIVE.remove(level);
        else ACTIVE.put(level, sources);
    }

    public static void tick(Level level, TickingBlockEntity ticker) {
        ticker.tick();
        if (!(level instanceof ServerLevel serverLevel) || !level.tickRateManager().runsNormally()) return;
        List<Source> sources = ACTIVE.get(serverLevel);
        if (sources == null) return;
        BlockPos pos = ticker.getPos();
        double speed = 1;
        for (Source source : sources) {
            if (source.pos().distSqr(pos) <= 16) speed = Math.max(speed, source.multiplier());
        }
        if (speed <= 1) return;
        // A strict upper bound prevents malformed attributes from freezing the server.
        double extra = Math.min(8, speed - 1);
        int count = (int)extra;
        double fraction = extra-count;
        // Distribute fractional ticks deterministically; no mutable per-machine counters.
        long tick = serverLevel.getGameTime();
        // Step 127 gives near-alternating half ticks instead of 512 boosted ticks in a row.
        int slot = (int)((tick * 127 + pos.asLong()) & 255);
        if (slot < fraction * 256) count++;
        for (int i=0; i<count && !ticker.isRemoved(); i++) ticker.tick();
    }
    public static void onStop(net.neoforged.neoforge.event.server.ServerStoppingEvent event) { ACTIVE.clear(); }
}
