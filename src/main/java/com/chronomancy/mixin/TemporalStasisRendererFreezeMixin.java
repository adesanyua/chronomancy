package com.chronomancy.mixin;

import com.chronomancy.client.ClientWorldStopState;
import com.chronomancy.client.TemporalStasisClientState;
import com.chronomancy.client.TemporalStasisTint;
import com.chronomancy.client.BorrowedFutureTint;
import com.chronomancy.client.NeedleStackTint;
import com.chronomancy.client.BorrowedFutureVisualRenderer;
import com.chronomancy.temporal.TemporalDilationHandler;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Фиксация кадра анимации замороженного стазисом моба на клиенте.
 *
 * <p>Нас интересуют float-аргументы цели:
 * {@code render(T entity, float entityYaw, float partialTicks, PoseStack, MultiBufferSource, int)}.
 * Среди float-переменных ordinal 0 = entityYaw, ordinal 1 = partialTicks.
 *
 * <p>{@code partialTicks} подмешивается в {@code ageInTicks = tickCount + partialTicks},
 * из-за чего {@code AnimationUtils.bobArms/bobModelPart} «дышат». При стазисе
 * клиент всё ещё тикает моба ({@code tickCount} растёт), поэтому одного обнуления
 * partialTicks недостаточно — клиентский тик моба замораживает
 * {@link TemporalStasisClientLevelMixin}. Оба опираются на общий клиентский реестр
 * {@link TemporalStasisClientState}, который наполняется с сервера пакетом
 * {@code StasisSyncPayload}.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class TemporalStasisRendererFreezeMixin {

    @ModifyVariable(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 1
    )
    private float chronomancy$freezeAnimationTick(
            float partialTicks,
            LivingEntity entity
    ) {
        if (chronomancy$isFrozen(entity) || chronomancy$worldStopFrozen(entity)) {
            return 0.0F;
        }

        return partialTicks;
    }

    @Unique
    private static boolean chronomancy$isFrozen(LivingEntity entity) {
        return TemporalStasisClientState.isFrozen(entity.getId());
    }

    /**
     * The World Stop: под глобальной заморозкой фиксруем кадр АНИМАЦИИ любого
     * не-кастерского LivingEntity (включая игроков) — тем же трюком,
     * что и стазис: {@code ageInTicks = tickCount + partialTicks}, partialTicks
     * пилится 0..1 каждый кадр и без обнуления даёт «дыхание»/jitter на ЛЮБОЙ
     * анимации. Тинт при этом НЕ включается (see {@link #chronomancy$shouldBeGolden}) —
     * единственным цветом стопа остаётся полноэкранный сепия-оверлей.
     */
    @Unique
    private static boolean chronomancy$worldStopFrozen(Entity entity) {
        // Кастер и сущности вне времени (Chronomaly, Time Rift) анимируются дальше.
        return ClientWorldStopState.isFrozen(entity);
    }

    /**
     * Золотая подсветка (tint-окно) включает ДВА состояния: заморозка стазисом
     * И замедление полем Time Dilation. Окно tint'а намеренно шире, чем проверка
     * замороженности: {@link TemporalStasisModelTintMixin}, {@code HeldItemTint} и
     * {@code ArmorGlow} читают именно окно, поэтому мобы под полем желтеют точно так
     * же, как в стазисе. Анимация при этом НЕ фиксируется (ordinal-подмена
     * partialTicks остаётся привязана только к стазису) — моб плавно ходит в замедлении.
     */
    @Unique
    private static boolean chronomancy$shouldBeGolden(Entity entity) {
        if (entity == null) {
            return false;
        }
        return TemporalStasisClientState.isFrozen(entity.getId())
                || TemporalDilationHandler.isClientDilated(entity);
    }

    // =========================================================
    // COPPER-SAND TINT WINDOW
    // =========================================================

    /**
     * Открывает окно tint'а ровно на время отрисовки замороженной сущности.
     *
     * <p>Ключуемся только на общий стейт {@link TemporalStasisClientState} по id
     * сущности (без привязки к конкретному заклинанию и без ограничения типом Mob),
     * поэтому решение автоматически подхватывает любое будущее заклинание,
     * использующее тот же стазис-стейт.
     */
    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD")
    )
    private void chronomancy$beginTint(
            LivingEntity entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo ci
    ) {
        // Медные копии заклинания Rift и двойники игрока — всегда медные.
        if (com.chronomancy.client.RiftEchoClient.isCopper(entity)) {
            com.chronomancy.client.RiftEchoClient.beginTint();
        } else {
            com.chronomancy.client.RiftEchoClient.endTint();
        }
        if (chronomancy$shouldBeGolden(entity)) {
            TemporalStasisTint.begin();
            BorrowedFutureTint.end();
            NeedleStackTint.end();
        } else {
            TemporalStasisTint.end();
            BorrowedFutureTint.begin(entity);
            NeedleStackTint.begin(entity);
        }
    }

    // RenderPlayerEvent.Post fires after vanilla pops the model pose, which leaves a direct
    // model.renderToBuffer call upside down. Draw the shell while the vanilla pose is still active.
    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V")
    )
    private void chronomancy$renderBorrowedFutureShell(
            LivingEntity entity, float entityYaw, float partialTicks, PoseStack poseStack,
            MultiBufferSource buffer, int packedLight, CallbackInfo ci
    ) {
        if (entity instanceof AbstractClientPlayer player && (Object) this instanceof PlayerRenderer renderer) {
            BorrowedFutureVisualRenderer.render(player, renderer, partialTicks, poseStack, buffer, packedLight);
        }
    }

    /**
     * Закрывает окно tint'а на любом выходе из render (включая ранний return
     * отмены RenderLivingEvent), чтобы состояние не «протекало» на другие модели.
     */
    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN")
    )
    private void chronomancy$endTint(CallbackInfo ci) {
        com.chronomancy.client.RiftEchoClient.endTint();
        TemporalStasisTint.end();
        BorrowedFutureTint.end();
        NeedleStackTint.end();
    }
}
