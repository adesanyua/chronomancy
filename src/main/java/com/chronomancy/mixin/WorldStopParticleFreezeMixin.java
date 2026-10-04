package com.chronomancy.mixin;

import com.chronomancy.client.ClientWorldStopState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Фаза 6 — временное прекращение анимации частиц во время The World Stop.
 *
 * <p>Частицы живут в клиентском {@code ParticleEngine} и НЕ являются сущностями,
 * поэтому заморозка {@code EntityTickEvent.Pre} на них не действует — без этого
 * миксина дым/искры/брызги продолжали бы разлетаться в «остановленном» мире,
 * предал бы стопу. Здесь на HEAD приватного {@code tickParticle} пропускаем
 * {@code particle.tick()} (и, соответственно, рост {@code age}) для ВСЕХ
 * частиц, кроме «темпоральных».
 *
 * <p>Исключение ({@link net.minecraft.client.particle.DustParticleBase}) — семейство цветной пыли, которым
 *Temporal Stasis / World Stop рисуют золотистый обратный отсчёт «песочных
 * часов». Они обязаны продолжать жить и мерцать, иначе эффект выглядит мёртвым
 * (требование ТЗ: temporal-exempt feedback keeps animating).
 *
 * <p>Замороженная частица не тикает → {@code isAlive()} остаётся true → она не
 * удаляется и висит в кадре; на возобновлении времени тики продолжаются. Чтобы в долгой остановке
 * висящие частицы не копились, раз в 30 секунд они убираются
 * ({@link ClientWorldStopState#purgingParticles()}).
 */
@Mixin(ParticleEngine.class)
public abstract class WorldStopParticleFreezeMixin {

    @Inject(method = "tickParticle", at = @At("HEAD"), cancellable = true)
    private void chronomancy$freezeParticle(Particle particle, CallbackInfo ci) {
        // Цветная пыль и собственные темпоральные частицы Chronomancy (motes/streaks)
        // temporal-exempt: стазис-эффекты обязаны жить и под стопом — они и
        // есть визуализация «застывшего, но дышащего» времени.
        if (!ClientWorldStopState.holdsParticle(particle)) {
            return;
        }
        // Раз в 30 секунд остановки застывшие частицы гасятся: в долгом испытании разлома они
        // копились бы в воздухе и закрывали обзор.
        if (ClientWorldStopState.purgingParticles()) {
            particle.remove();
            return;
        }
        ci.cancel();
    }

    /**
     * Застывшая частица не тикает, но кадр по-прежнему рисует её с бегущей долей тика: позиция
     * каждый тик заново проходит путь от прошлой точки к текущей, а размер, заданный через
     * {@code age + partialTick} (криты, тёмные сердечки урона), вырастает с нуля. Со стороны это
     * дрожь и мигание. Пока частица заморожена, она рисуется с постоянной долей тика.
     */
    @WrapOperation(
            method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"),
            require = 0
    )
    private void chronomancy$holdParticleFrame(Particle particle, VertexConsumer buffer, Camera camera,
                                               float partialTick, Operation<Void> original) {
        original.call(particle, buffer, camera,
                ClientWorldStopState.holdsParticle(particle) ? 1.0F : partialTick);
    }
}
