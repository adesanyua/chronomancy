package com.chronomancy.client.entity;

import com.chronomancy.ChronomancyMod;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeckoLib-модель сущностей Chronomancy: {@code geo/<name>.geo.json},
 * {@code textures/entity/<name>.png}, {@code animations/<name>.animation.json}.
 *
 * <p>Поворот головы за взглядом здесь НЕ делается: прибавка к повороту кости попадала в
 * снапшоты GeckoLib и накапливалась на переходах анимаций (голова начинала крутиться).
 * Взгляд применяется только на время отрисовки — см. {@link HeadLook}.
 */
public final class ChronoEntityGeoModel<T extends GeoAnimatable> extends GeoModel<T> {
    private final ResourceLocation geometry, texture, animation;
    private final boolean trackHead;

    /** Вращать ли кость {@code head} за взглядом (используется рендерером через {@link HeadLook}). */
    public boolean tracksHead() {
        return trackHead;
    }

    /** С отсечением задних граней: убирает мерцание соприкасающихся кубов (z-fighting). */
    private boolean cull;

    public ChronoEntityGeoModel(String name, boolean trackHead, boolean cull) {
        this(name, trackHead);
        this.cull = cull;
    }

    @Override
    public net.minecraft.client.renderer.RenderType getRenderType(T animatable, ResourceLocation texture) {
        return cull ? net.minecraft.client.renderer.RenderType.entityCutout(texture) : super.getRenderType(animatable, texture);
    }

    public ChronoEntityGeoModel(String name, boolean trackHead) {
        this.geometry = id("geo/" + name + ".geo.json");
        this.texture = id("textures/entity/" + name + ".png");
        this.animation = id("animations/" + name + ".animation.json");
        this.trackHead = trackHead;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, path);
    }

    @Override public ResourceLocation getModelResource(T animatable) { return geometry; }
    @Override public ResourceLocation getTextureResource(T animatable) { return texture; }
    @Override public ResourceLocation getAnimationResource(T animatable) { return animation; }

}
