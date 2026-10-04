package com.chronomancy.temporal.borrowed;

import com.chronomancy.spell.BorrowedFutureSpell;
import com.chronomancy.network.BorrowedFuturePayload;
import com.chronomancy.registry.ChronoSounds;
import io.redspace.ironsspellbooks.api.events.SpellPreCastEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server authority for the two phases. The clock advances even when entity/world ticks are skipped. */
public final class BorrowedFutureManager {
    private static final String KEY = "chronomancy_borrowed_future";
    private static final Map<UUID, State> ACTIVE = new HashMap<>();
    private static long clock;

    private BorrowedFutureManager() {}

    private static final class State {
        BorrowedFuturePhase phase;
        int duration;
        long deadline;
        double empoweredRate;
        double debtRate;

        double rate() { return phase == BorrowedFuturePhase.EMPOWERED ? empoweredRate : debtRate; }
    }

    public static boolean isActive(ServerPlayer player) { return ACTIVE.containsKey(player.getUUID()); }

    public static BorrowedFuturePhase phase(ServerPlayer player) {
        State state = ACTIVE.get(player.getUUID());
        return state == null ? BorrowedFuturePhase.NORMAL : state.phase;
    }

    public static double rate(ServerPlayer player) {
        State state = ACTIVE.get(player.getUUID());
        return state == null ? 1.0 : state.rate();
    }

    public static void start(ServerPlayer player, BorrowedFutureStats stats) {
        if (isActive(player)) return;
        State state = new State();
        state.phase = BorrowedFuturePhase.EMPOWERED;
        state.duration = stats.durationTicks();
        state.deadline = clock + state.duration;
        state.empoweredRate = stats.empoweredRate();
        state.debtRate = stats.debtRate();
        ACTIVE.put(player.getUUID(), state);
        save(player, state);
        sync(player, state);
        player.level().playSound(null, player.blockPosition(), ChronoSounds.BORROWED_FUTURE_BORROW.get(), SoundSource.PLAYERS, 0.8f, 1.1f);
    }

    /**
     * Пока идёт ускорение (не долг), получаемый урон выше — см. {@link BorrowedFutureStats#damageTakenBonus}.
     * То же для часовщика, разогнавшегося этим заклинанием.
     */
    public static void onIncomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (event.getEntity().level().isClientSide || event.getAmount() <= 0.0F) return;
        double rate = 0.0;
        if (event.getEntity() instanceof ServerPlayer player) {
            State state = ACTIVE.get(player.getUUID());
            if (state != null && state.phase == BorrowedFuturePhase.EMPOWERED) rate = state.empoweredRate;
        } else if (event.getEntity() instanceof com.chronomancy.entity.ClocksmithEntity smith) {
            rate = smith.borrowedFutureRate();
        }
        if (rate > 0.0) {
            event.setAmount((float) (event.getAmount() * (1.0 + BorrowedFutureStats.damageTakenBonus(rate))));
        }
    }

    public static void onPreCast(SpellPreCastEvent event) {
        if (BorrowedFutureSpell.SPELL_ID.equals(event.getSpellId())
                && event.getEntity() instanceof ServerPlayer player && isActive(player)) {
            event.setCanceled(true);
        }
    }

    public static void onServerTick(ServerTickEvent.Pre event) {
        clock++;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            State state = ACTIVE.get(player.getUUID());
            if (state == null) continue;
            if (clock >= state.deadline) {
                if (state.phase == BorrowedFuturePhase.EMPOWERED) {
                    state.phase = BorrowedFuturePhase.DEBT;
                    state.deadline = clock + state.duration;
                    save(player, state);
                    sync(player, state);
                    player.level().playSound(null, player.blockPosition(), ChronoSounds.BORROWED_FUTURE_DEBT.get(), SoundSource.PLAYERS, 0.9f, 0.8f);
                } else {
                    ACTIVE.remove(player.getUUID());
                    player.getPersistentData().remove(KEY);
                    sync(player, null);
                    player.level().playSound(null, player.blockPosition(), ChronoSounds.BORROWED_FUTURE_RELEASE.get(), SoundSource.PLAYERS, 0.5f, 1.2f);
                    continue;
                }
            }
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            State state = ACTIVE.remove(player.getUUID());
            if (state != null) save(player, state);
        }
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        CompoundTag tag = player.getPersistentData().getCompound(KEY);
        if (!tag.contains("phase")) return;
        State state = new State();
        state.phase = BorrowedFuturePhase.valueOf(tag.getString("phase"));
        state.duration = tag.getInt("duration");
        state.empoweredRate = tag.getDouble("empowered");
        state.debtRate = tag.getDouble("debt");
        state.deadline = clock + Math.max(1, tag.getInt("remaining"));
        ACTIVE.put(player.getUUID(), state);
        sync(player, state);
    }

    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer observer)
                || !(event.getTarget() instanceof ServerPlayer target)) return;
        State state = ACTIVE.get(target.getUUID());
        if (state != null) PacketDistributor.sendToPlayer(observer,
                new BorrowedFuturePayload(target.getId(), state.phase.ordinal(),
                        (int) Math.max(0, state.deadline - clock), (int) Math.round(state.rate() * 1000)));
    }

    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player, ACTIVE.get(player.getUUID()));
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            State state = ACTIVE.get(player.getUUID());
            if (state != null) save(player, state);
        }
        ACTIVE.clear();
        clock = 0;
    }

    public static void onClone(PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof ServerPlayer oldPlayer && event.getEntity() instanceof ServerPlayer newPlayer) {
            State state = ACTIVE.remove(oldPlayer.getUUID());
            if (state != null) {
                // Death cannot cancel repayment; any unused empowered time becomes debt.
                if (state.phase == BorrowedFuturePhase.EMPOWERED) {
                    state.phase = BorrowedFuturePhase.DEBT;
                    state.deadline = clock + state.duration + Math.max(0, state.deadline - clock);
                }
                ACTIVE.put(newPlayer.getUUID(), state);
                save(newPlayer, state);
                sync(newPlayer, state);
            }
        }
    }

    private static void save(ServerPlayer player, State state) {
        CompoundTag tag = new CompoundTag();
        tag.putString("phase", state.phase.name());
        tag.putInt("duration", state.duration);
        tag.putInt("remaining", (int) Math.max(1, state.deadline - clock));
        tag.putDouble("empowered", state.empoweredRate);
        tag.putDouble("debt", state.debtRate);
        player.getPersistentData().put(KEY, tag);
    }

    private static void sync(ServerPlayer player, State state) {
        int phase = state == null ? BorrowedFuturePhase.NORMAL.ordinal() : state.phase.ordinal();
        int remaining = state == null ? 0 : (int) Math.max(0, state.deadline - clock);
        int rate = state == null ? 1000 : (int) Math.round(state.rate() * 1000);
        BorrowedFuturePayload payload = new BorrowedFuturePayload(player.getId(), phase, remaining, rate);
        PacketDistributor.sendToPlayer(player, payload);
        PacketDistributor.sendToPlayersTrackingEntity(player, payload);
    }
}
