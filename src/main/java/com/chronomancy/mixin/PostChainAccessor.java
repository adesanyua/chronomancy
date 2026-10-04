package com.chronomancy.mixin;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Минимальный клиентский {@code @Accessor} к приватному списку проходов {@link PostChain}.
 *
 * <p>Нужен ОДНОМУ: получить {@link PostPass#getEffect()} -> {@code EffectInstance},
 * чтобы писать матричные ({@code mat4}) и пофield'овые униформы напрямую — ванильный
 * {@code PostChain#setUniform} поддерживает только скалярные float, а GL {@code vec4[]}-
 * массивы его Uniform не умеет. Никакой игровой логики не трогает, только чтение
 * клиентского рендер-объекта.
 */
@Mixin(PostChain.class)
public interface PostChainAccessor {

    @Accessor("passes")
    List<PostPass> chronomancy$getPasses();
}
