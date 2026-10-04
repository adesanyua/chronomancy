package com.chronomancy.mixin;

import com.chronomancy.client.ClientWorldStopState;
import com.chronomancy.client.TemporalStasisClientState;
import com.chronomancy.client.TemporalStasisTint;
import com.chronomancy.temporal.TemporalDilationHandler;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.util.Color;

/**
 * Заморозка кадра анимации GeckoLib-сущности, накрытой Temporal Stasis.
 *
 * <p>Диагностика ({@code [GK-DIAG]}) эмпирически подтвердила: при стазисе клиентский
 * тик сущности уже заморожен ({@code tickCount} константа, см.
 * {@link TemporalStasisClientLevelMixin}), но {@code partialTick} продолжает пилиться
 * 0..1 и сбрасываться каждый игровой тик. GeckoLib в {@code GeoModel#handleAnimations}
 * считает {@code currentFrameTime = tickCount + partialTick} и из его дельты копит
 * {@code animTime}. Пила по {@code partialTick} => пила по {@code animTime} =>
 * модель рывками переключается между позами walk = jitter.
 *
 * <p>{@link GeoEntityRenderer} наследуется от {@code EntityRenderer}, а не от
 * {@code LivingEntityRenderer}, поэтому ванильный
 * {@link TemporalStasisRendererFreezeMixin} до него не достаёт. Обнуляем
 * {@code partialTick} только для замороженной сущности: тогда
 * {@code currentFrameTime} становится константой, срабатывает собственный
 * short-circuit GeckoLib ({@code isReRender}) и поза удерживается без сброса
 * контроллера — после снятия стазиса анимация продолжается с того же кадра.
 *
 * <p>Float-аргументы цели {@code render(Entity, float entityYaw, float partialTick,
 * PoseStack, MultiBufferSource, int)}: ordinal 0 = entityYaw, ordinal 1 = partialTick.
 *
 * <p>Класс-цель существует только при наличии GeckoLib, который является жёсткой
 * зависимостью Iron's Spells (от которого зависит и этот аддон), поэтому в данном
 * окружении миксин применяется безопасно.
 */
@Mixin(GeoEntityRenderer.class)
public abstract class TemporalStasisGeoRendererFreezeMixin {

    @ModifyVariable(
            method = "render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 1
    )
    private float chronomancy$freezeGeoAnimationTick(
            float partialTick,
            Entity entity
    ) {
        if (chronomancy$isGeoFrozen(entity) || chronomancy$worldStopFrozen(entity)) {
            return 0.0F;
        }

        return partialTick;
    }

    @Unique
    private static boolean chronomancy$isGeoFrozen(Entity entity) {
        return entity != null && TemporalStasisClientState.isFrozen(entity.getId());
    }

    /**
     * The World Stop: фиксируем кадр GeckoLib-анимации не-кастерской сущности
     * ({@code currentFrameTime = tickCount + partialTick}) тем же нулём
     * partialTick'а, что и стазис. Золотой tint НЕ включаем — при стопе
     * работает только полноэкранный сепия-оверлей.
     */
    @Unique
    private static boolean chronomancy$worldStopFrozen(Entity entity) {
        // Кастер и сущности вне времени (Chronomaly, Time Rift) анимируются дальше.
        return ClientWorldStopState.isFrozen(entity);
    }

    /** Тинтуем те же состояния, что и ванильный mob-конвейер: стазис ИЛИ поле дилейшена. */
    @Unique
    private static boolean chronomancy$shouldBeGolden(Entity entity) {
        return chronomancy$isGeoFrozen(entity) || TemporalDilationHandler.isClientDilated(entity);
    }

    // =========================================================
    // COPPER-SAND TINT (GeckoLib compatibility layer)
    // =========================================================

    /**
     * Медно-песочный tint для GeckoLib-модели замороженной сущности.
     *
     * <p>{@code GeoEntityRenderer} наследуется от {@code EntityRenderer}, а не от
     * {@code LivingEntityRenderer}, поэтому ванильное окно tint'а
     * ({@link TemporalStasisRendererFreezeMixin} + {@link TemporalStasisModelTintMixin})
     * до GeckoLib-конвейера не достаёт: GeckoLib красит модель своим packed-цветом,
     * который берётся из {@code getRenderColor(...).argbInt()} в {@code defaultRender}
     * и доходит до {@code buffer.addVertex} через {@code actuallyRender}.
     *
     * <p>Подменяем именно возвращаемый {@link Color}: он перемножается с текстурой на
     * вершинах, поэтому оригинальная текстура и детали остаются видимыми, меняются
     * только оттенки. Альфу сохраняем (учитывает невидимость/spectator). Ключ — общий
     * стейт {@link TemporalStasisClientState} по id сущности, без привязки к
     * заклинанию; после frozen=false возвращается {@code original} (Color.WHITE).
     *
     * <p>Цель указываем полным дескриптором, т.к. у дженерика есть мостовой метод
     * {@code getRenderColor(GeoAnimatable, ...)} — его не трогаем, красится реальный.
     */
    @Inject(
            method = "getRenderColor(Lnet/minecraft/world/entity/Entity;FI)Lsoftware/bernie/geckolib/util/Color;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void chronomancy$applyGeoTint(
            Entity animatable,
            float partialTick,
            int packedLight,
            CallbackInfoReturnable<Color> cir
    ) {
        if (chronomancy$shouldBeGolden(animatable)) {
            int rgb = TemporalStasisTint.TINT_RGB;
            int alpha = cir.getReturnValue().getAlpha();
            cir.setReturnValue(Color.ofRGBA((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha));
        }
    }
}
