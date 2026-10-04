package com.chronomancy.registry;

import com.chronomancy.ChronomancyMod;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.registries.ItemRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ChronoCreativeTabRegistry {
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(
            net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB,
            ChronomancyMod.MODID
    );

    public static final Supplier<CreativeModeTab> CHRONOMANCY_TAB = TABS.register(
            "chronomancy",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.chronomancy.chronomancy"))
                    .icon(() -> new ItemStack(ChronoItemRegistry.GRAIN_OF_TIME.get()))
                    .displayItems((enabledFeatures, entries) -> {
                        // Материалы школы: руна времени, зерно времени (фокус), сфера улучшения
                        entries.accept(ChronoItemRegistry.GRAIN_OF_TIME.get());
                        entries.accept(ChronoItemRegistry.TIME_RUNE.get());
                        entries.accept(ChronoItemRegistry.CHRONOMANCY_UPGRADE_ORB.get());
                        entries.accept(ChronoItemRegistry.CHRONOMALY_SHARD.get());
                        entries.accept(ChronoItemRegistry.CHRONOMALY_SPAWN_EGG.get());
                        entries.accept(ChronoItemRegistry.RIFT_MAKER_SPAWN_EGG.get());
                        entries.accept(ChronoItemRegistry.CLOCKSMITH_SPAWN_EGG.get());
                        entries.accept(ChronoItemRegistry.PHASING_ZOMBIE_SPAWN_EGG.get());
                        entries.accept(ChronoItemRegistry.PHASING_SKELETON_SPAWN_EGG.get());
                        entries.accept(ChronoItemRegistry.PHASING_CREEPER_SPAWN_EGG.get());
                        entries.accept(ChronoItemRegistry.RIFT_CREATOR.get());
                        entries.accept(ChronoItemRegistry.MUSIC_DISC_RIFT_MAKER.get());

                        // Кольцо удвоения (пустое; заклинание вкладывается на arcane наковальне)
                        entries.accept(ChronoItemRegistry.GEMINATED_RING.get());
                        entries.accept(ChronoItemRegistry.DEFERRED_PENDULUM.get());
                        entries.accept(ChronoItemRegistry.SECOND_CHANCE_WATCH.get());
                        entries.accept(ChronoItemRegistry.CRACKED_DIAL.get());
                        entries.accept(ChronoItemRegistry.HOURGLASS_OF_FOCUS.get());
                        entries.accept(ChronoItemRegistry.TEMPORAL_ANCHOR.get());
                        entries.accept(ChronoItemRegistry.CLOCKWORK_KEY.get());
                        entries.accept(ChronoItemRegistry.RING_OF_RIFTS.get());
                        entries.accept(ChronoItemRegistry.RIFT_HEART.get());
                        entries.accept(ChronoItemRegistry.TIMELESS_SEAL.get());
                        entries.accept(ChronoItemRegistry.CLOCKSMITH_HELMET.get());
                        entries.accept(ChronoItemRegistry.CLOCKSMITH_CHESTPLATE.get());
                        entries.accept(ChronoItemRegistry.CLOCKSMITH_LEGGINGS.get());
                        entries.accept(ChronoItemRegistry.CLOCKSMITH_BOOTS.get());
                        entries.accept(ChronoItemRegistry.CLOCK_HAND.get());
                        entries.accept(ChronoItemRegistry.BOOK_OF_TIME_MANAGMENT.get());

                        // Only Chronomancy spells, at every legitimate enabled level.
                        for (var spell : SpellRegistry.getEnabledSpells()) {
                            if (spell == SpellRegistry.none()
                                    || !ChronomancyMod.MODID.equals(spell.getSpellResource().getNamespace())) continue;
                            // Rift — уникальное заклинание оружия Rift Creator, свитков нет.
                            if (spell == ChronoSpellRegistry.RIFT_SPELL) continue;
                            for (int level = spell.getMinLevel(); level <= spell.getMaxLevel(); level++) {
                                ItemStack scroll = new ItemStack(ItemRegistry.SCROLL.get());
                                ISpellContainer.createScrollContainer(spell, level, scroll);
                                entries.accept(scroll);
                            }
                        }
                    })
                    .build()
    );

    public static void register(IEventBus eventBus) {
        TABS.register(eventBus);
    }
}