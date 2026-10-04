package com.chronomancy.client;

import com.chronomancy.ChronomancyMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Настоящая «медь» для копий заклинания Rift и двойников игрока.
 *
 * <p>Раньше копии красились tint'ом вершин: цвет вершины УМНОЖАЕТСЯ на текстуру, поэтому зелёный
 * зомби или тёмный скелет оставались почти прежними. Теперь при отрисовке копии её
 * {@link MultiBufferSource} подменяется: каждый запрошенный entity-RenderType (тело, слои, броня,
 * предмет в руке — всё, что в формате {@code NEW_ENTITY}) получает «медную» копию с тем же
 * состоянием (текстура, прозрачность, culling…), но с шейдером {@code chronomancy:copper_echo},
 * который перекрашивает пиксели в медь по их яркости. Работает для ванильных, GeckoLib и
 * модовых мобов одинаково.
 *
 * <p>Если шейдер не загрузился (например, конфликт с шейдерпаком), остаётся старый tint.
 */
public final class CopperEchoRendering {
    private static ShaderInstance shader;
    private static final Map<RenderType, RenderType> COPPER_TYPES = new IdentityHashMap<>();

    private CopperEchoRendering() {
    }

    /** Регистрация шейдера (mod bus, только клиент). Перезагружается вместе с ресурсами. */
    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(ChronomancyMod.MODID, "copper_echo"),
                    DefaultVertexFormat.NEW_ENTITY), loaded -> {
                shader = loaded;
                synchronized (COPPER_TYPES) {
                    COPPER_TYPES.clear();
                    PARTIAL_TYPES.clear();
                }
            });
        } catch (IOException e) {
            shader = null;
            ChronomancyMod.LOGGER.warn("Chronomancy copper_echo shader failed to load, falling back to tint: {}", e.toString());
        }
    }

    public static boolean available() {
        return shader != null;
    }

    /** Буфер, в котором все entity-слои рисуются медью; {@code null}, если шейдера нет. */
    public static MultiBufferSource wrap(MultiBufferSource delegate) {
        if (shader == null || delegate instanceof CopperSource) {
            return delegate;
        }
        return new CopperSource(delegate);
    }

    /** Сколько ступеней силы меди у частично медных сущностей (слои песка Sands of Time). */
    private static final int AMOUNT_BUCKETS = 16;
    private static final Map<RenderType, RenderType[]> PARTIAL_TYPES = new IdentityHashMap<>();

    /**
     * То же, но медь лишь частичная: {@code amount} 0..1 — доля меди в цвете. Меньше одной ступени —
     * буфер не трогается, полная — обычная медь копий.
     */
    public static MultiBufferSource wrap(MultiBufferSource delegate, float amount) {
        if (shader == null || delegate instanceof CopperSource || delegate instanceof PartialCopperSource) {
            return delegate;
        }
        int bucket = Math.round(Math.max(0.0F, Math.min(1.0F, amount)) * AMOUNT_BUCKETS);
        if (bucket <= 0) {
            return delegate;
        }
        return bucket >= AMOUNT_BUCKETS ? new CopperSource(delegate) : new PartialCopperSource(delegate, bucket);
    }

    private static RenderType partialOf(RenderType original, int bucket) {
        if (original.format() != DefaultVertexFormat.NEW_ENTITY) {
            return original;
        }
        synchronized (COPPER_TYPES) {
            RenderType[] row = PARTIAL_TYPES.computeIfAbsent(original, rt -> new RenderType[AMOUNT_BUCKETS]);
            if (row[bucket] == null) {
                float amount = bucket / (float) AMOUNT_BUCKETS;
                row[bucket] = new RenderType(
                        "chronomancy_copper_" + bucket + "/" + original, original.format(), original.mode(),
                        original.bufferSize(), original.affectsCrumbling(), original.sortOnUpload(),
                        () -> {
                            original.setupRenderState();
                            ShaderInstance copper = shader;
                            if (copper != null) {
                                copper.safeGetUniform("CopperAmount").set(amount);
                                RenderSystem.setShader(() -> copper);
                            }
                        },
                        original::clearRenderState) {
                };
            }
            return row[bucket];
        }
    }

    private record PartialCopperSource(MultiBufferSource delegate, int bucket) implements MultiBufferSource {
        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return delegate.getBuffer(partialOf(type, bucket));
        }
    }

    private static RenderType copperOf(RenderType original) {
        if (original.format() != DefaultVertexFormat.NEW_ENTITY) {
            return original; // текст, свечение enchant glint, линии и т.п. — как есть
        }
        synchronized (COPPER_TYPES) {
            return COPPER_TYPES.computeIfAbsent(original, rt -> new RenderType(
                    "chronomancy_copper/" + rt, rt.format(), rt.mode(), rt.bufferSize(),
                    rt.affectsCrumbling(), rt.sortOnUpload(),
                    () -> {
                        rt.setupRenderState();
                        ShaderInstance copper = shader;
                        if (copper != null) {
                            copper.safeGetUniform("CopperAmount").set(1.0F);
                            RenderSystem.setShader(() -> copper);
                        }
                    },
                    rt::clearRenderState) {
            });
        }
    }

    private record CopperSource(MultiBufferSource delegate) implements MultiBufferSource {
        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return delegate.getBuffer(copperOf(type));
        }
    }
}
