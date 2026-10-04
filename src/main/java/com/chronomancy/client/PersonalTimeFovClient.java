package com.chronomancy.client;

import com.chronomancy.temporal.TemporalDilationPlayerMovement;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;

/**
 * Личное время игрока не меняет поле зрения.
 *
 * <p>Замедление и ускорение личного времени (поле замедления, Accelerated Zone, парадокс, пески,
 * Borrowed Future, чужая остановка мира) приходят игроку модификатором скорости передвижения
 * ({@link TemporalDilationPlayerMovement}) — так клиент сам считает его ход. Но ваниль по той же
 * скорости считает и поле зрения: в сильном поле обзор сужался почти вдвое, а под парадоксом дёргался
 * дважды в секунду. Здесь вклад именно этого модификатора вычитается; бег, зелья скорости и натянутый
 * лук меняют обзор как обычно.
 */
public final class PersonalTimeFovClient {
    private PersonalTimeFovClient() {
    }

    public static void onComputeFov(ComputeFovModifierEvent event) {
        Player player = event.getPlayer();
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null || speed.getModifier(TemporalDilationPlayerMovement.MOVE_ID) == null) {
            return;
        }
        float walk = player.getAbilities().getWalkingSpeed();
        if (walk == 0.0F) {
            return; // ваниль в этом случае сама ставит множитель 1
        }
        float with = (float) (speed.getValue() / walk + 1.0D) / 2.0F;
        float without = (float) (valueWithoutPersonalTime(speed) / walk + 1.0D) / 2.0F;
        if (with <= 1.0E-4F || Float.isNaN(with) || Float.isInfinite(with) || Float.isNaN(without) || Float.isInfinite(without)) {
            return;
        }
        // множитель ванили = (полёт) × (скорость) × (лук): заменяем в нём только долю скорости
        float corrected = event.getFovModifier() / with * without;
        double scale = Minecraft.getInstance().options.fovEffectScale().get();
        event.setNewFovModifier((float) Mth.lerp(scale, 1.0D, corrected));
    }

    /** Значение атрибута так, как его считает ваниль, но без модификатора личного времени. */
    private static double valueWithoutPersonalTime(AttributeInstance speed) {
        double base = speed.getBaseValue();
        for (AttributeModifier modifier : speed.getModifiers()) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                base += modifier.amount();
            }
        }
        double value = base;
        for (AttributeModifier modifier : speed.getModifiers()) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) {
                value += base * modifier.amount();
            }
        }
        for (AttributeModifier modifier : speed.getModifiers()) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
                    && !modifier.id().equals(TemporalDilationPlayerMovement.MOVE_ID)) {
                value *= 1.0D + modifier.amount();
            }
        }
        return speed.getAttribute().value().sanitizeValue(value);
    }
}
