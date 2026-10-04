package com.chronomancy.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * Общая для всех temporal-перемещений (Rewind, Backtrack, будущий Time Anchor)
 * проверка «можно ли встать в точку». Нельзя слепо телепортировать сущность
 * внутрь блока: историческая позиция могла стать небезопасной (поставили блок,
 * закрыли дверь, изменили рельеф).
 *
 * <p>Порядок поиска: точная позиция → вверх (1..2) → горизонтальное кольцо
 * радиуса 1..2. {@code null} — безопасной точки нет: вызывающая сторона решает,
 * что делать (Rewind откатывает витальные параметры без tp; Backtrack
 * пропускает teleport, но отложенный урон всё равно наступает).
 */
public final class ChronoSafeTeleport {

    private ChronoSafeTeleport() {
    }

    /** Ближайшая безопасная точка к {@code target} (или null — «безопасного входа» нет). */
    @Nullable
    public static Vec3 findSafePosition(ServerLevel level, Vec3 target) {
        if (isPassable(level, target)) {
            return target;
        }
        for (int up = 1; up <= 2; up++) {
            Vec3 raised = target.add(0.0, up, 0.0);
            if (isPassable(level, raised)) {
                return raised;
            }
        }
        for (int r = 1; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    for (int up = 0; up <= 2; up++) {
                        Vec3 candidate = target.add(dx, up, dz);
                        if (isPassable(level, candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Проходимость позиции ног: чанк загружен, все три уровня тела свободны
     * от твердых блоков, пол не лава.
     */
    public static boolean isPassable(ServerLevel level, Vec3 feetPos) {
        BlockPos origin = BlockPos.containing(feetPos.x, feetPos.y, feetPos.z);
        if (!level.isLoaded(origin)) {
            return false;
        }
        // Рост 1.8: задеваем уровни origin, +1 и +2 (пол/потолок).
        for (int dy = 0; dy <= 2; dy++) {
            BlockPos pos = origin.above(dy);
            BlockState state = level.getBlockState(pos);
            if (state.blocksMotion()) {
                return false;
            }
            if (state.liquid() && state.getFluidState().is(FluidTags.LAVA)) {
                return false;
            }
        }
        return true;
    }
}
