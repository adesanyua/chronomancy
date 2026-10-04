package com.chronomancy.compat.jei;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.registry.ChronoItemRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.registries.ItemRegistry;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI: страницы-подсказки «как добыть Зерно времени» (без магии — заклинаний ещё нет, а Зерно и есть
 * фокус школы). Класс подхватывает только JEI; без JEI он никогда не загружается.
 */
@JeiPlugin
public class ChronoJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addIngredientInfo(ChronoItemRegistry.GRAIN_OF_TIME.get(),
                Component.translatable("jei.chronomancy.grain_of_time.intro"),
                Component.translatable("jei.chronomancy.grain_of_time.anvil"),
                Component.translatable("jei.chronomancy.grain_of_time.lightning"),
                Component.translatable("jei.chronomancy.grain_of_time.archaeology"),
                Component.translatable("jei.chronomancy.grain_of_time.chronomaly"),
                Component.translatable("jei.chronomancy.grain_of_time.clocksmith"));
        registration.addIngredientInfo(ChronoItemRegistry.RING_OF_RIFTS.get(),
                Component.translatable("jei.chronomancy.ring_of_rifts"));
        registration.addIngredientInfo(ChronoItemRegistry.RIFT_HEART.get(),
                Component.translatable("jei.chronomancy.rift_heart"));
        registration.addIngredientInfo(Items.CLOCK,
                Component.translatable("jei.chronomancy.clock"));
        registerSpellPages(registration);
    }

    /**
     * Полные описания заклинаний школы: в подсказке свитка (ISS) осталась короткая строка с цифрами,
     * а подробности — на странице JEI у свитков этого заклинания (всех уровней: JEI различает
     * свитки по заклинанию и уровню).
     */
    private static void registerSpellPages(IRecipeRegistration registration) {
        for (AbstractSpell spell : SpellRegistry.REGISTRY) {
            ResourceLocation id = spell.getSpellResource();
            if (!ChronomancyMod.MODID.equals(id.getNamespace()) || !spell.isEnabled()) {
                continue;
            }
            List<ItemStack> scrolls = new ArrayList<>();
            for (int level = spell.getMinLevel(); level <= spell.getMaxLevel(); level++) {
                ItemStack scroll = new ItemStack(ItemRegistry.SCROLL.get());
                ISpellContainer.createScrollContainer(spell, level, scroll);
                scrolls.add(scroll);
            }
            if (!scrolls.isEmpty()) {
                registration.addIngredientInfo(scrolls, VanillaTypes.ITEM_STACK,
                        Component.translatable("jei.chronomancy.spell." + id.getPath()));
            }
        }
    }
}
