package com.chronomancy.registry;

import com.chronomancy.ChronomancyMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ChronoSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(Registries.SOUND_EVENT, ChronomancyMod.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> CHRONOMANCY_CAST = SOUND_EVENTS.register(
            "chronomancy_cast",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "chronomancy_cast"))
    );
    public static final DeferredHolder<SoundEvent, SoundEvent> CLOCK_TICK = sound("clock_tick");
    public static final DeferredHolder<SoundEvent, SoundEvent> TEMPORAL_SAND = sound("temporal_sand");
    public static final DeferredHolder<SoundEvent, SoundEvent> TEMPORAL_HUM = sound("temporal_hum");
    public static final DeferredHolder<SoundEvent, SoundEvent> TEMPORAL_CRACK = sound("temporal_crack");
    public static final DeferredHolder<SoundEvent, SoundEvent> TEMPORAL_REWIND = sound("temporal_rewind");
    public static final DeferredHolder<SoundEvent, SoundEvent> TEMPORAL_RELEASE = sound("temporal_release");
    public static final DeferredHolder<SoundEvent, SoundEvent> NEEDLE_FLIGHT = sound("needle_flight");
    public static final DeferredHolder<SoundEvent, SoundEvent> NEEDLE_HIT = sound("needle_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> NEEDLE_COLLAPSE = sound("needle_collapse");
    public static final DeferredHolder<SoundEvent, SoundEvent> DOUBLE_SUMMON = sound("double_summon");
    public static final DeferredHolder<SoundEvent, SoundEvent> DOUBLE_ECHO = sound("double_echo");
    public static final DeferredHolder<SoundEvent, SoundEvent> PROJECTILE_LAUNCH = sound("projectile_launch");
    public static final DeferredHolder<SoundEvent, SoundEvent> BORROWED_FUTURE_BORROW = sound("borrowed_future_borrow");
    public static final DeferredHolder<SoundEvent, SoundEvent> BORROWED_FUTURE_DEBT = sound("borrowed_future_debt");
    public static final DeferredHolder<SoundEvent, SoundEvent> BORROWED_FUTURE_RELEASE = sound("borrowed_future_release");

    public static final DeferredHolder<SoundEvent, SoundEvent> CHRONOMALY_AMBIENT = sound("chronomaly_ambient");
    public static final DeferredHolder<SoundEvent, SoundEvent> CHRONOMALY_HURT = sound("chronomaly_hurt");
    public static final DeferredHolder<SoundEvent, SoundEvent> CHRONOMALY_DEATH = sound("chronomaly_death");
    public static final DeferredHolder<SoundEvent, SoundEvent> CHRONOMALY_ATTACK = sound("chronomaly_attack");
    public static final DeferredHolder<SoundEvent, SoundEvent> TIME_RIFT_OPEN = sound("time_rift_open");
    public static final DeferredHolder<SoundEvent, SoundEvent> TIME_RIFT_CLOSE = sound("time_rift_close");

    public static final DeferredHolder<SoundEvent, SoundEvent> RIFT_MAKER_AMBIENT = sound("rift_maker_ambient");
    public static final DeferredHolder<SoundEvent, SoundEvent> RIFT_MAKER_SUMMON = sound("rift_maker_summon");

    /** Тема боя с Rift Maker (см. {@code BossMusicClient}) и та же мелодия на пластинке. */
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSIC_RIFT_MAKER = sound("music.rift_maker");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSIC_DISC_RIFT_MAKER = sound("music_disc.rift_maker");

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, name)));
    }

    public static void register(IEventBus eventBus) {
        SOUND_EVENTS.register(eventBus);
    }
}
