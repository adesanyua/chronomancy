package com.chronomancy.client.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Что лежит под рисунком на земле: без этого области ударов босса пропадали на снегу.
 *
 * <p>Причин было две, и обе видны только по самим блокам. Первая — тонкие покрытия, сквозь которые
 * существо проваливается ногами: один слой снега выше опоры на 1/8 блока, и рисунок, положенный «у
 * ног», оказывался под ним. Отсюда {@link Sample#lift}: насколько рисунок надо приподнять, чтобы он
 * лёг поверх такого покрытия. Вторая — цвет: золото на белом не читается. Отсюда
 * {@link Sample#bright}: насколько земля светлая (снег, песок, кварц) — шейдеры по нему переходят на
 * тёмную палитру.
 */
public final class GroundDecals {
    /** Покрытие считается тонким, если его верх не выше этого над опорой (снег, ковёр, мох). */
    private static final double THIN_COVER = 0.3D;
    private static final float BASE_LIFT = 0.03F;

    /** @param lift на сколько блоков выше опоры класть рисунок; @param bright 0 — тёмная земля, 1 — светлая */
    public record Sample(float lift, float bright) {
    }

    private GroundDecals() {
    }

    /**
     * @param offsets пары (dx, dz) — точки области относительно {@code (x, z)}, по которым судим о земле
     */
    public static Sample sample(Level level, double x, double y, double z, double... offsets) {
        double lift = 0.0D;
        double luma = 0.0D;
        int count = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i + 1 < offsets.length; i += 2) {
            pos.set(Mth.floor(x + offsets[i]), Mth.floor(y + 0.01D), Mth.floor(z + offsets[i + 1]));
            BlockState state = level.getBlockState(pos);
            boolean covered = false;
            if (!state.isAir()) {
                VoxelShape shape = state.getShape(level, pos);
                if (!shape.isEmpty()) {
                    double top = pos.getY() + shape.max(Direction.Axis.Y) - y;
                    // верх на уровне ног — существо стоит на самом этом блоке (ковёр, плита): цвет берём его
                    if (top > -0.01D && top <= THIN_COVER) {
                        lift = Math.max(lift, top);
                        covered = true;
                    }
                }
            }
            if (!covered) {
                pos.move(Direction.DOWN);
                state = level.getBlockState(pos);
            }
            MapColor color = state.getMapColor(level, pos);
            if (color != MapColor.NONE) {
                int rgb = color.col;
                luma += (0.299D * ((rgb >> 16) & 0xFF) + 0.587D * ((rgb >> 8) & 0xFF) + 0.114D * (rgb & 0xFF)) / 255.0D;
                count++;
            }
        }
        double average = count == 0 ? 0.4D : luma / count;
        float bright = (float) Mth.clamp((average - 0.55D) / 0.23D, 0.0D, 1.0D);
        return new Sample((float) lift + BASE_LIFT, bright * bright * (3.0F - 2.0F * bright));
    }
}
