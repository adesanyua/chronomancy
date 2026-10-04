package com.chronomancy.registry;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.loot.ReplaceWithChanceModifier;
import com.mojang.serialization.MapCodec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Глобальные модификаторы добычи мода (Зерно времени в археологии). */
public final class ChronoLootModifiers {
    public static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> SERIALIZERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, ChronomancyMod.MODID);

    public static final DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<ReplaceWithChanceModifier>> REPLACE_WITH_CHANCE =
            SERIALIZERS.register("replace_with_chance", () -> ReplaceWithChanceModifier.CODEC);

    private ChronoLootModifiers() {
    }

    public static void register(IEventBus eventBus) {
        SERIALIZERS.register(eventBus);
    }
}
