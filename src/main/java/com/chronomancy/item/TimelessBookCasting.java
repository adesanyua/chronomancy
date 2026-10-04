package com.chronomancy.item;

import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import io.redspace.ironsspellbooks.item.Scroll;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * TIMELESS BOOK — сжатие непрерывных заклинаний.
 *
 * <p>Непрерывное заклинание (Fire Breath, Cone of Cold, Electrocute…) у владельца книги проживает
 * своё время каста в {@value #SPEED} раз быстрее: 5 секунд дыхания выходят за одну. Число
 * импульсов, урон каждого и перезарядка остаются теми же — меняется только время, за которое всё
 * это происходит, и цена: каждый импульс сжатого каста стоит в {@value #MANA_FACTOR} раза больше маны.
 *
 * <p>Iron's Spells каждый тик продвигает каст на один шаг (раз в 10 шагов — импульс заклинания).
 * Здесь после него делается ещё {@code SPEED - 1} таких же шагов, по тем же правилам, что и в
 * {@code MagicManager.tick}. Импульсы идут раз в два тика, поэтому на время такого каста урон этого
 * заклинания проходит сквозь кадры неуязвимости цели (см. {@link #isBurst}).
 */
public final class TimelessBookCasting {
    /** Во сколько раз быстрее проживается непрерывный каст. */
    public static final int SPEED = 5;
    /** Во сколько раз дороже по мане каждый импульс сжатого каста. */
    public static final int MANA_FACTOR = 2;
    /** Заклинания-«удержания», которым сжатие только вредит (держат цель, пока длится каст). */
    private static final Set<String> EXCLUDED = Set.of("irons_spellbooks:telekinesis");
    /** Сколько тиков после последнего шага урон заклинания ещё проходит сквозь кадры неуязвимости. */
    private static final int BURST_GRACE = 3;

    private record Burst(AbstractSpell spell, int until) {
    }

    private static final Map<UUID, Burst> BURSTS = new HashMap<>();

    private TimelessBookCasting() {
    }

    /** Сжимается ли это заклинание книгой. */
    public static boolean accelerates(AbstractSpell spell) {
        return spell != null && spell.getCastType() == CastType.CONTINUOUS && !EXCLUDED.contains(spell.getSpellId());
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            accelerate(player, SPEED - 1);
        }
        if (!BURSTS.isEmpty()) {
            int now = event.getServer().getTickCount();
            BURSTS.values().removeIf(burst -> now > burst.until());
        }
    }

    /**
     * Цена сжатия: импульс заклинания, которое книга ускоряет, списывает двойную ману. Iron's Spells
     * списывает ровно то значение, что осталось в событии каста.
     */
    public static void onSpellCast(SpellOnCastEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !event.getCastSource().consumesMana()) {
            return;
        }
        if (accelerates(SpellRegistry.getSpell(event.getSpellId())) && ChronoCurioEvents.wearsTimelessBook(player)) {
            event.setManaCost(event.getManaCost() * MANA_FACTOR);
        }
    }

    public static void onStop(ServerStoppingEvent event) {
        BURSTS.clear();
    }

    /** Владелец книги сейчас «выпускает» это заклинание сжатым кастом — его урон не ждёт кадров неуязвимости. */
    public static boolean isBurst(LivingEntity caster, AbstractSpell spell) {
        Burst burst = BURSTS.get(caster.getUUID());
        return burst != null && burst.spell() == spell && caster.getServer() != null
                && caster.getServer().getTickCount() <= burst.until();
    }

    /** Дополнительные шаги каста для владельца книги, колдующего непрерывное заклинание. */
    public static void accelerate(ServerPlayer player, int steps) {
        MagicData data = MagicData.getPlayerMagicData(player);
        if (!data.isCasting()) {
            return;
        }
        AbstractSpell spell = SpellRegistry.getSpell(data.getCastingSpellId());
        if (!accelerates(spell) || !ChronoCurioEvents.wearsTimelessBook(player)) {
            return;
        }
        BURSTS.put(player.getUUID(), new Burst(spell, player.server.getTickCount() + BURST_GRACE));
        advance(player, steps);
    }

    /**
     * Продвигает непрерывный каст игрока на {@code steps} шагов — шаг в шаг как
     * {@code MagicManager.tick}: импульс заклинания раз в 10 шагов, последний импульс ставит
     * перезарядку и завершает каст (он же срабатывает раньше срока, если мана на исходе).
     */
    public static void advance(ServerPlayer player, int steps) {
        MagicData data = MagicData.getPlayerMagicData(player);
        if (!data.isCasting()) {
            return;
        }
        String spellId = data.getCastingSpellId();
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        if (spell.getCastType() != CastType.CONTINUOUS) {
            return;
        }
        Level level = player.level();
        for (int i = 0; i < steps && data.isCasting() && spellId.equals(data.getCastingSpellId()); i++) {
            int spellLevel = data.getCastingSpellLevel();
            CastSource source = data.getCastSource();
            int remaining = data.getCastDurationRemaining();
            if (remaining % MagicManager.CONTINUOUS_CAST_TICK_INTERVAL == 0) {
                boolean last = remaining <= 0
                        || (source.consumesMana() && data.getMana() - spell.getManaCost(spellLevel) * 2 < 0);
                spell.castSpell(level, spellLevel, player, source, last);
                if (last) {
                    if (source == CastSource.SCROLL) {
                        Scroll.attemptRemoveScrollAfterCast(player);
                    }
                    spell.onServerCastComplete(level, spellLevel, player, data, false);
                }
            }
            data.handleCastDuration();
            if (data.isCasting()) {
                spell.onServerCastTick(level, spellLevel, player, data);
            }
        }
    }
}
