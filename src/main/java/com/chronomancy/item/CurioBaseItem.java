package com.chronomancy.item;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.chronomancy.ChronomancyMod;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import com.chronomancy.registry.ChronoSounds;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.function.Function;

/**
 * Базовый класс для хронок-pack предметов, носимых в слотах Curios (кольца, колье и т.д.).
 * Позволяет одним вызовом {@link #withAttributes} привязывать атрибуты школы к предмету.
 * Дженерик {@code T} используется для ковариантного fluent-API (возвращает подкласс).
 */
public class CurioBaseItem<T extends CurioBaseItem<T>> extends Item implements ICurioItem {
    final @Nullable String slotIdentifier;
    Function<Integer, Multimap<Holder<Attribute>, AttributeModifier>> attributes;

    public CurioBaseItem(Item.Properties properties) {
        this(properties, null);
    }

    public CurioBaseItem(Item.Properties properties, @Nullable String slotIdentifier) {
        super(properties);
        this.slotIdentifier = slotIdentifier;
        this.attributes = (index) -> ImmutableMultimap.of();
    }

    public boolean isEquippedBy(@Nullable LivingEntity entity) {
        return entity != null && CuriosApi.getCuriosInventory(entity).map(inventory -> inventory.findFirstCurio(this).isPresent()).orElse(false);
    }

    @NotNull
    @Override
    public ICurio.SoundInfo getEquipSound(SlotContext slotContext, ItemStack stack) {
        return new ICurio.SoundInfo(ChronoSounds.CLOCK_TICK.get(), 0.65f, 0.95f);
    }

    @Override
    public Multimap<Holder<Attribute>, AttributeModifier> getAttributeModifiers(SlotContext slotContext, ResourceLocation id, ItemStack stack) {
        if (slotIdentifier == null || !slotContext.identifier().equals(slotIdentifier)) {
            return ICurioItem.super.getAttributeModifiers(slotContext, id, stack);
        }
        return attributes.apply(slotContext.index());
    }

    /**
     * Связывает с предметом бонусы атрибутов, действующие в указанном слоте Curios.
     *
     * @param slot       идентификатор слота curios (например "ring")
     * @param modifiers  список модификаторов атрибутов
     */
    @SuppressWarnings("unchecked")
    public T withAttributes(String slot, ChronoAttribute... modifiers) {
        ImmutableMultimap.Builder<Holder<Attribute>, AttributeModifier> builder = ImmutableMultimap.builder();
        for (ChronoAttribute modifier : modifiers) {
            String modifierId = String.format("%s_%s", ChronomancyMod.MODID, modifier.name());
            builder.put(modifier.attribute(), new AttributeModifier(ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, modifierId), modifier.value(), modifier.operation()));
        }
        Multimap<Holder<Attribute>, AttributeModifier> staticModifiers = builder.build();
        this.attributes = (index) -> staticModifiers;
        return (T) this;
    }

    /** Простой контейнер для модификатора атрибута школы. */
    public record ChronoAttribute(Holder<Attribute> attribute, double value, AttributeModifier.Operation operation) {
        public String name() {
            return ResourceLocation.parse(attribute.getRegisteredName()).getPath();
        }
    }
}