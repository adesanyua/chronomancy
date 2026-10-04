package com.chronomancy.item;

import com.chronomancy.registry.ChronoItemRegistry;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;

/**
 * The Geminated Ring's behaviour: while worn, the spell imprinted in its slot is offered in the ISS
 * spell bar (its container is a spell wheel), so a normal cast fires {@link SpellOnCastEvent}. We
 * react to that cast only when it matches a ring the player is actually wearing and doubles it:
 *
 * <ul>
 *   <li><b>Triple mana</b> — {@link SpellOnCastEvent#setManaCost(int)} is the supported hook; ISS
 *       deducts the value we leave on the event, so the single initiated cast pays three times.</li>
 *   <li><b>Casts twice</b> — the paid-for cast runs through the full ISS pipeline; the second strike
 *       is queued and re-applied {@link #ECHO_DELAY_TICKS} ticks (0.5s) later with a direct
 *       {@link AbstractSpell#onCast}. That call is the same technique {@code ChronoDoubleSpellEcho}
 *       uses: it neither posts the event again nor spends mana or resets the cooldown, so the extra
 *       strike is genuinely free (already covered by the 3x). If the ring is removed or the imprint
 *       changed before the echo fires, the echo is dropped.</li>
 * </ul>
 *
 * <p>Scope: only {@link CastType#INSTANT} spells are doubled — long/continuous spells run through ISS'
 * multi-tick casting state machine that a single headless {@code onCast} cannot drive; those simply cast
 * (and charge) normally.
 */
public final class GeminatedRingEvents {

    private static final int MANA_MULTIPLIER = 3;
    /** Gap between the paid cast and the ring's echo: 0.5s of server ticks. */
    private static final int ECHO_DELAY_TICKS = 10;

    /** Echoes awaiting their deadline, drained by {@link #onServerTick}. Server-thread only. */
    private record PendingEcho(UUID playerId, String spellId, int spellLevel, int fireTick) {}
    private static final Deque<PendingEcho> QUEUE = new ArrayDeque<>();

    private GeminatedRingEvents() {}

    public static void onSpellOnCast(SpellOnCastEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide) return;
        String spellId = event.getSpellId();
        if (spellId == null) return;

        // Only react when the player is wearing a Geminated Ring that stores exactly this spell.
        if (!storesSpell(player, spellId)) return;

        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        if (spell == null || spell == SpellRegistry.none()) return;
        if (spell.getCastType() != CastType.INSTANT) return;

        // ISS checks mana sufficiency at the *base* cost BEFORE this event fires, so simply
        // raising the cost here would let a player with only the base cost afford it cast twice
        // for (nearly) one cast's worth of mana. Gate the doubling on the player actually being
        // able to pay the tripled cost; if they cannot, the ring stays inert and the spell is a
        // normal single cast at its base cost.
        int tripledCost = event.getManaCost() * MANA_MULTIPLIER;
        if (MagicData.getPlayerMagicData(player).getMana() < tripledCost) return;

        event.setManaCost(tripledCost);
        QUEUE.addLast(new PendingEcho(player.getUUID(), spellId, event.getSpellLevel(),
                player.level().getServer().getTickCount() + ECHO_DELAY_TICKS));
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        int now = event.getServer().getTickCount();
        while (!QUEUE.isEmpty() && QUEUE.peekFirst().fireTick() <= now) {
            PendingEcho echo = QUEUE.pollFirst();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(echo.playerId());
            if (player == null || !player.isAlive()) continue; // logged out / died in the gap
            // The ring must still be worn with the same imprint, otherwise skip the free strike.
            if (!storesSpell(player, echo.spellId())) continue;
            AbstractSpell spell = SpellRegistry.getSpell(echo.spellId());
            if (spell == null || spell == SpellRegistry.none()) continue;
            spell.onCast(player.level(), echo.spellLevel(), player, CastSource.NONE,
                    MagicData.getPlayerMagicData(player));
        }
    }

    /** Drop any echo still mid-delay on shutdown (tick counters restart with the next world). */
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        QUEUE.clear();
    }

    /** True if the player wears a Geminated Ring whose slot stores the given spell. */
    private static boolean storesSpell(ServerPlayer player, String spellId) {
        Optional<SlotResult> slot = CuriosApi.getCuriosInventory(player)
                .flatMap(handler -> handler.findFirstCurio(ChronoItemRegistry.GEMINATED_RING.get()));
        if (slot.isEmpty()) return false;
        ItemStack ring = slot.get().stack();
        SpellData stored = GeminatedRingItem.storedSpell(ring);
        return stored != null && stored.getSpell() != null
                && stored.getSpell().getSpellId().equals(spellId);
    }
}
