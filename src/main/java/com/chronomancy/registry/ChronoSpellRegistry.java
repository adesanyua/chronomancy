package com.chronomancy.registry;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.spell.BacktrackSpell;
import com.chronomancy.spell.BorrowedFutureSpell;
import com.chronomancy.spell.RewindSpell;
import com.chronomancy.spell.TemporalStasisBeamSpell;
import com.chronomancy.spell.TheWorldStopSpell;
import com.chronomancy.spell.TimeDilationFieldSpell;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ChronoSpellRegistry {
    public static final DeferredRegister<AbstractSpell> SPELLS = DeferredRegister.create(
            SpellRegistry.SPELL_REGISTRY_KEY,
            ChronomancyMod.MODID
    );

    public static final TemporalStasisBeamSpell TEMPORAL_STASIS_BEAM_SPELL = new TemporalStasisBeamSpell();

    public static final TimeDilationFieldSpell TIME_DILATION_FIELD_SPELL = new TimeDilationFieldSpell();

    public static final RewindSpell REWIND_SPELL = new RewindSpell();

    /** Low-cooldown DPS спелл школы: болт → позиционный откат → отложенный урон. */
    public static final BacktrackSpell BACKTRACK_SPELL = new BacktrackSpell();

    public static final TheWorldStopSpell THE_WORLD_STOP_SPELL = new TheWorldStopSpell();
    public static final BorrowedFutureSpell BORROWED_FUTURE_SPELL = new BorrowedFutureSpell();

    public static final com.chronomancy.spell.TimePiercingNeedleSpell TIME_PIERCING_NEEDLE_SPELL = new com.chronomancy.spell.TimePiercingNeedleSpell();

    /** Зеркальное медное эхо кастера: ловит melee/урон-спеллы игрока повторным reduced-ударом. */
    public static final com.chronomancy.spell.ChronoDoubleSpell CHRONO_DOUBLE_SPELL = new com.chronomancy.spell.ChronoDoubleSpell();

    /** Уникальное заклинание легендарного Rift Creator: медная копия цели нападает на оригинал. */
    public static final com.chronomancy.spell.RiftSpell RIFT_SPELL = new com.chronomancy.spell.RiftSpell();

    /** Шаг сквозь время вперёд с фазированием от снарядов. */
    public static final com.chronomancy.spell.TimeWalkSpell TIME_WALK_SPELL = new com.chronomancy.spell.TimeWalkSpell();

    /** Конус песка: с каждой секундой цель всё медленнее, в конце каста — стазис. */
    public static final com.chronomancy.spell.SandsOfTimeSpell SANDS_OF_TIME_SPELL = new com.chronomancy.spell.SandsOfTimeSpell();

    /** Противоположность поля замедления: ускоряющий куб; вместе с полем даёт временной парадокс. */
    public static final com.chronomancy.spell.AcceleratedZoneSpell ACCELERATED_ZONE_SPELL = new com.chronomancy.spell.AcceleratedZoneSpell();

    static {
        SPELLS.register(ACCELERATED_ZONE_SPELL.getSpellName(), () -> ACCELERATED_ZONE_SPELL);
        SPELLS.register(SANDS_OF_TIME_SPELL.getSpellName(), () -> SANDS_OF_TIME_SPELL);
        SPELLS.register(TIME_WALK_SPELL.getSpellName(), () -> TIME_WALK_SPELL);
        SPELLS.register(RIFT_SPELL.getSpellName(), () -> RIFT_SPELL);
        SPELLS.register(TIME_PIERCING_NEEDLE_SPELL.getSpellName(), () -> TIME_PIERCING_NEEDLE_SPELL);
        SPELLS.register(TEMPORAL_STASIS_BEAM_SPELL.getSpellName(), () -> TEMPORAL_STASIS_BEAM_SPELL);
        SPELLS.register(TIME_DILATION_FIELD_SPELL.getSpellName(), () -> TIME_DILATION_FIELD_SPELL);
        SPELLS.register(REWIND_SPELL.getSpellName(), () -> REWIND_SPELL);
        SPELLS.register(BACKTRACK_SPELL.getSpellName(), () -> BACKTRACK_SPELL);
        SPELLS.register(THE_WORLD_STOP_SPELL.getSpellName(), () -> THE_WORLD_STOP_SPELL);
        SPELLS.register(BORROWED_FUTURE_SPELL.getSpellName(), () -> BORROWED_FUTURE_SPELL);
        SPELLS.register(CHRONO_DOUBLE_SPELL.getSpellName(), () -> CHRONO_DOUBLE_SPELL);
    }

    public static void register(IEventBus eventBus) {
        SPELLS.register(eventBus);
    }
}
