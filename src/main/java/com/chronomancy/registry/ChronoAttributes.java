package com.chronomancy.registry;

import com.chronomancy.ChronomancyMod;
import io.redspace.ironsspellbooks.api.attribute.MagicPercentAttribute;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ChronoAttributes {
    public static final DeferredRegister<Attribute> ATTRIBUTES = DeferredRegister.create(Registries.ATTRIBUTE, ChronomancyMod.MODID);

    public static final DeferredHolder<Attribute, Attribute> CHRONOMANCY_SPELL_POWER = ATTRIBUTES.register(
            "chronomancy_spell_power",
            () -> new MagicPercentAttribute("attribute.chronomancy.chronomancy_spell_power", 1.0D, -100.0D, 100.0D).setSyncable(true)
    );

    public static final DeferredHolder<Attribute, Attribute> CHRONOMANCY_MAGIC_RESIST = ATTRIBUTES.register(
            "chronomancy_magic_resist",
            () -> new MagicPercentAttribute("attribute.chronomancy.chronomancy_magic_resist", 1.0D, -100.0D, 100.0D).setSyncable(true)
    );

    public static void register(IEventBus eventBus) {
        ATTRIBUTES.register(eventBus);
        eventBus.addListener(ChronoAttributes::modifyEntityAttributes);
    }

    private static void modifyEntityAttributes(EntityAttributeModificationEvent event) {
        event.getTypes().forEach(entityType -> {
            ATTRIBUTES.getEntries().forEach(attributeHolder -> {
                event.add(entityType, attributeHolder);
            });
        });
    }
}

