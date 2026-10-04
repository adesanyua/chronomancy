package com.chronomancy.temporal.history;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Мгновенный снимок состояния игрока (только серверная сторона).
 *
 * <p>Сознательно НЕ содержит полный NBT: хранятся лишь скаляры, необходимые
 * для честного отката (Rewind) и будущих history-based заклинаний
 * (Temporal Echo, Time Anchor). Полная сериализация игрока каждый тик —
 * и слишком дорогая, и опасная (дублирование предметов при откате).
 *
 * <p>{@code tracked} — защитные копии ровно 6 слотов (основная рука, вторая
 * рука, 4 части брони). Копии используются ТОЛЬКО для сравнения идентичности
 * предмета и значения durability при откате — никогда для записи обратно
 * целиком (см. {@code RewindSpell#restoreDurability}).
 *
 * @param gameTime      {@code ChronoClock.now()} на момент снимка (внешние часы, идут и при World Stop)
 * @param dimension     измерение игрока (межизмеренческий откат запрещён в v1)
 * @param pos           позиция ног
 * @param yaw/pitch     повороты
 * @param velocity      дельта-движение (скорость)
 * @param health        текущее здоровье
 * @param absorption    щит поглощения
 * @param food          уровень голода
 * @param saturation    насыщение
 * @param air           запас воздуха
 * @param fireTicks     горящие тики
 * @param fallDistance  накопленная дистанция падения
 * @param selectedSlot  выбранный слот хотбара (0..8)
 * @param tracked       копии 6 отслеживаемых слотов экипировки
 */
public record TemporalSnapshot(
        long gameTime,
        ResourceKey<Level> dimension,
        Vec3 pos,
        float yaw,
        float pitch,
        Vec3 velocity,
        float health,
        float absorption,
        int food,
        float saturation,
        int air,
        int fireTicks,
        float fallDistance,
        int selectedSlot,
        ItemStack[] tracked
) {
    /** Порядок слотов в {@link #tracked}. */
    public static final EquipmentSlot[] TRACKED_SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    /**
     * Снимок с серверного игрока. Вызывается строго с серверного потока
     * (из {@link TemporalHistoryManager}), поэтому все чтения состояния безопасны.
     */
    public static TemporalSnapshot capture(ServerPlayer player) {
        Level level = player.level();
        ItemStack[] stacks = new ItemStack[TRACKED_SLOTS.length];
        for (int i = 0; i < TRACKED_SLOTS.length; i++) {
            stacks[i] = player.getItemBySlot(TRACKED_SLOTS[i]).copy();
        }
        return new TemporalSnapshot(
                com.chronomancy.temporal.ChronoClock.now(),
                level.dimension(),
                player.position(),
                player.getYRot(),
                player.getXRot(),
                player.getDeltaMovement(),
                player.getHealth(),
                player.getAbsorptionAmount(),
                player.getFoodData().getFoodLevel(),
                player.getFoodData().getSaturationLevel(),
                player.getAirSupply(),
                player.getRemainingFireTicks(),
                player.fallDistance,
                player.getInventory().selected,
                stacks
        );
    }
}
