package com.chronomancy.client;

/**
 * Клиентский «переключатель» медно-песочного tint'а для замороженных сущностей.
 *
 * <p>Не хранит состояние на сущности и не подменяет текстуры. Это всего лишь
 * короткое по времени окно, открытое на время отрисовки одной замороженной
 * сущности: {@link com.chronomancy.mixin.TemporalStasisRendererFreezeMixin}
 * открывает его в начале {@code LivingEntityRenderer#render} и закрывает в конце,
 * а {@link com.chronomancy.mixin.TemporalStasisModelTintMixin} читает его в самом
 * низу конвейера — в {@code ModelPart#render}, через который проходят базовая
 * модель, броня и все дополнительные {@code RenderLayer}.
 *
 * <p>Так tint привязан только к общему состоянию стазиса
 * {@link TemporalStasisClientState#isFrozen(int)} и автоматически работает для
 * любого будущего заклинания, использующего тот же стейт.
 */
public final class TemporalStasisTint {

    /**
     * Очень яркий, почти жёлтый золотисто-медный оттенок. Храним только RGB-каналы;
     * альфа берётся из оригинального цвета, чтобы не ломать прозрачные/полупрозрачные
     * слои. Значение близкое к максимуму по R/G и низкое по B => моб отчётливо
     * «позолочен», но текстура и детали остаются видимыми (цвет перемножается с
     * текстурой, а не заменяет её).
     */
    public static final int TINT_RGB = 0x00FFE24A;

    private static boolean active = false;

    private TemporalStasisTint() {
    }

    public static void begin() {
        active = true;
    }

    public static void end() {
        active = false;
    }

    /** При рабочих шейдерах стазис/замедление рисуются шейдером (см. VanillaPhase), а не tint'ом. */
    public static boolean isActive() {
        return active && !com.chronomancy.client.entity.TimePhaseRendering.available();
    }

    /**
     * Подмешивает tint к оригинальному packed-цвету (0xAARRGGBB), сохраняя альфу.
     * Метод идемпотентен: повторное применение даёт тот же результат.
     */
    public static int apply(int originalColor) {
        return (originalColor & 0xFF000000) | TINT_RGB;
    }
}
