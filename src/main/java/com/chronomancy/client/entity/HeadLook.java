package com.chronomancy.client.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * Поворот кости {@code head} за взглядом сущности — только на время отрисовки кости.
 *
 * <p>Раньше взгляд прибавлялся к повороту кости в {@code GeoModel#setCustomAnimations}. GeckoLib
 * в начале каждого кадра копирует текущий поворот костей в снапшоты и от них строит переходы
 * между анимациями (idle ↔ walk) и возврат неанимированных костей в исходную позу — прибавка
 * попадала в снапшот и на следующем кадре добавлялась ещё раз, из-за чего голова бесконечно
 * крутилась. Теперь поворот ставится перед отрисовкой кости и сразу снимается после неё,
 * так что в анимационное состояние он не попадает.
 */
public final class HeadLook {
    private static final float MAX_YAW = 75.0F;
    private static final float MAX_PITCH = 60.0F;

    private HeadLook() {
    }

    /** @return {@code true}, если это кость головы и поворот применён — тогда обязательно вызвать {@link #restore}. */
    public static boolean apply(GeoBone bone, LivingEntity entity, float partialTick, float[] saved) {
        if (!"head".equals(bone.getName()) || entity.isDeadOrDying()) {
            return false;
        }
        float body = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        float head = Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot);
        float yaw = Mth.clamp(Mth.wrapDegrees(head - body), -MAX_YAW, MAX_YAW);
        float pitch = Mth.clamp(Mth.lerp(partialTick, entity.xRotO, entity.getXRot()), -MAX_PITCH, MAX_PITCH);
        saved[0] = bone.getRotX();
        saved[1] = bone.getRotY();
        // Как в DefaultedEntityGeoModel у GeckoLib, но поверх текущей (анимированной) позы.
        bone.setRotX(saved[0] + pitch * Mth.DEG_TO_RAD);
        bone.setRotY(saved[1] + yaw * Mth.DEG_TO_RAD);
        return true;
    }

    public static void restore(GeoBone bone, float[] saved) {
        bone.setRotX(saved[0]);
        bone.setRotY(saved[1]);
        // setRot* помечает кость «изменённой»; флаг не должен дожить до следующего тика анимаций,
        // иначе GeckoLib пропустит возврат головы в исходную позу.
        bone.resetStateChanges();
    }
}
