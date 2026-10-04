package com.chronomancy.item;

import com.chronomancy.ChronomancyMod;
import com.chronomancy.registry.ChronoAttributes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.function.Consumer;

/** Netherite-tier sword; its spindle and two clockwork gears animate independently. */
public final class ClockHandItem extends SwordItem implements GeoItem {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    public ClockHandItem(Properties properties) { super(Tiers.NETHERITE, properties); }
    public static ItemAttributeModifiers createClockHandAttributes() {
        var builder = ItemAttributeModifiers.builder();
        for (var entry : SwordItem.createAttributes(Tiers.NETHERITE, 4, -2.4f).modifiers()) {
            builder.add(entry.attribute(), entry.modifier(), entry.slot());
        }
        builder.add(ChronoAttributes.CHRONOMANCY_SPELL_POWER,
                new AttributeModifier(ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID,
                        "clock_hand_chronomancy_power"), .10, AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
                EquipmentSlotGroup.MAINHAND);
        return builder.build();
    }
    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "clockwork", 0, state -> state.setAndContinue(IDLE)));
    }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
    @Override public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private com.chronomancy.client.equipment.ClockHandRenderer renderer;
            @Override public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getGeoItemRenderer() {
                if (renderer == null) renderer = new com.chronomancy.client.equipment.ClockHandRenderer();
                return renderer;
            }
        });
    }
}
