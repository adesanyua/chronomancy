package com.chronomancy.temporal;

import com.chronomancy.ChronomancyMod;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Последнее заклинание каждого игрока — его повторяют против него медные двойники:
 * <ul>
 *   <li>двойник Rift Maker — только хрономантию ({@link #last}): бой с боссом идёт в остановленном
 *       времени, и заклинания других школ у двойника застывали бы в воздухе;</li>
 *   <li>двойник заклинания Rift — заклинание любой школы ({@link #lastAnySchool}).</li>
 * </ul>
 * Те, что двойнику не положены ({@link #NOT_FOR_ECHOES}), не запоминаются вовсе.
 */
public final class LastSpellTracker {
    /** Заклинание и уровень, на котором игрок его применил. */
    public record Cast(AbstractSpell spell, int level) {
    }

    /**
     * Что двойник не повторяет: заклинания, завязанные на самого игрока (его историю, личное время,
     * двойника) или ломающие бой с боссом (остановка мира идёт прямо сейчас; зона ускорения рядом с
     * полем самого босса схлопывалась бы в парадокс).
     */
    private static final Set<String> NOT_FOR_ECHOES = Set.of(
            ChronomancyMod.MODID + ":the_world_stop",
            ChronomancyMod.MODID + ":chrono_double",
            ChronomancyMod.MODID + ":rift",
            ChronomancyMod.MODID + ":rewind",
            ChronomancyMod.MODID + ":borrowed_future",
            ChronomancyMod.MODID + ":time_walk",
            ChronomancyMod.MODID + ":accelerated_zone");

    /** Последняя хрономантия — для двойников босса. */
    private static final Map<UUID, Cast> LAST = new HashMap<>();
    /** Последнее заклинание любой школы — для двойника заклинания Rift. */
    private static final Map<UUID, Cast> LAST_ANY = new HashMap<>();

    private LastSpellTracker() {
    }

    public static void onSpellCast(SpellOnCastEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getSpellId() == null) {
            return;
        }
        // Неповторяемое не запоминаем вовсе: за двойником остаётся предыдущее годное заклинание
        // (бой с боссом начинается с остановки мира — она не должна стирать память).
        if (NOT_FOR_ECHOES.contains(event.getSpellId())) {
            return;
        }
        AbstractSpell spell = SpellRegistry.getSpell(event.getSpellId());
        if (spell == null || spell == SpellRegistry.none()) {
            return;
        }
        Cast cast = new Cast(spell, Math.max(1, event.getSpellLevel()));
        LAST_ANY.put(player.getUUID(), cast);
        // Для двойников босса — только хрономантия: бой идёт в остановленном времени, и заклинания
        // других школ у двойника просто застывают в воздухе. Чужое заклинание эту память не стирает.
        if (event.getSpellId().startsWith(ChronomancyMod.MODID + ":")) {
            LAST.put(player.getUUID(), cast);
        }
    }

    /** Последняя хрономантия игрока, если двойник может её повторить; иначе {@code null}. */
    @Nullable
    public static Cast last(Player player) {
        return usable(LAST.get(player.getUUID()));
    }

    /** Последнее заклинание игрока из любой школы, если двойник может его повторить; иначе {@code null}. */
    @Nullable
    public static Cast lastAnySchool(Player player) {
        return usable(LAST_ANY.get(player.getUUID()));
    }

    @Nullable
    private static Cast usable(@Nullable Cast cast) {
        if (cast == null || !cast.spell().isEnabled() || NOT_FOR_ECHOES.contains(cast.spell().getSpellId())) {
            return null;
        }
        return cast;
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST.remove(event.getEntity().getUUID());
        LAST_ANY.remove(event.getEntity().getUUID());
    }

    public static void onStop(ServerStoppingEvent event) {
        LAST.clear();
        LAST_ANY.clear();
    }
}
