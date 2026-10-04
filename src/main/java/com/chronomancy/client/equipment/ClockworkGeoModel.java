package com.chronomancy.client.equipment;

import com.chronomancy.ChronomancyMod;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.model.GeoModel;

/** Original Blockbench-compatible geometry with its own texture and animation resources. */
public final class ClockworkGeoModel<T extends GeoAnimatable> extends GeoModel<T> {
    private final ResourceLocation geometry, texture, animation;
    public ClockworkGeoModel(String name) {
        geometry = id("geo/" + name + ".geo.json");
        texture = id("textures/equipment/" + name + ".png");
        animation = id("animations/" + name + ".animation.json");
    }
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, path);
    }
    @Override public ResourceLocation getModelResource(T animatable) { return geometry; }
    @Override public ResourceLocation getTextureResource(T animatable) { return texture; }
    @Override public ResourceLocation getAnimationResource(T animatable) { return animation; }
    /** Отсечение задних граней: соприкасающиеся кубы не мерцают (z-fighting). */
    @Override public net.minecraft.client.renderer.RenderType getRenderType(T animatable, ResourceLocation texture) {
        return net.minecraft.client.renderer.RenderType.entityCutout(texture);
    }
}
