package com.chronomancy.registry;

import com.chronomancy.ChronomancyMod;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import io.redspace.ironsspellbooks.damage.ISSDamageTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ChronoSchools {
    // Создаем свой DeferredRegister для школ, используя тот же ResourceKey, что и Iron's Spells
    private static final DeferredRegister<SchoolType> CHRONO_SCHOOLS = DeferredRegister.create(
            SchoolRegistry.SCHOOL_REGISTRY_KEY, 
            ChronomancyMod.MODID
    );

    public static final ResourceLocation CHRONOMANCY_RESOURCE = ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "chronomancy");
    public static final TagKey<Item> CHRONOMANCY_FOCUS = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "chronomancy_focus"));

    // Песочно-желтый цвет (Sand Yellow / Desert Gold: #E4C660)
    public static final int CHRONOMANCY_COLOR = 0xE4C660;

    // Регистрируем школу в том же реестре, что и школы Iron's Spells
    public static final DeferredHolder<SchoolType, SchoolType> CHRONOMANCY = CHRONO_SCHOOLS.register(
            "chronomancy",
            () -> new SchoolType(
                    CHRONOMANCY_RESOURCE,
                    CHRONOMANCY_FOCUS,
                    Component.translatable("school.chronomancy.chronomancy").withStyle(Style.EMPTY.withColor(CHRONOMANCY_COLOR)),
                    ChronoAttributes.CHRONOMANCY_SPELL_POWER,
                    ChronoAttributes.CHRONOMANCY_MAGIC_RESIST,
                    ChronoSounds.CHRONOMANCY_CAST,
                    ISSDamageTypes.EVOCATION_MAGIC
            )
    );

    /** Same multiplicative power as ISS: generic Spell Power × Chronomancy Spell Power. */
    public static double totalSpellPower(LivingEntity caster) {
        if (caster == null) return 1.0;
        return caster.getAttributeValue(AttributeRegistry.SPELL_POWER)
                * CHRONOMANCY.get().getPowerFor(caster);
    }

    public static void register(IEventBus eventBus) {
        CHRONO_SCHOOLS.register(eventBus);
    }
}
