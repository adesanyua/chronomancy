package com.chronomancy.temporal;

import com.chronomancy.ChronomancyMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Vanilla-synced movement attribute for players inside another caster's dilation field. */
public final class TemporalDilationPlayerMovement {
    public static final ResourceLocation MOVE_ID = ResourceLocation.fromNamespaceAndPath(
            ChronomancyMod.MODID, "time_dilation_player_movement");
    private static final ResourceLocation JUMP_ID = ResourceLocation.fromNamespaceAndPath(
            ChronomancyMod.MODID, "time_dilation_player_jump");
    private static final ResourceLocation ATTACK_ID = ResourceLocation.fromNamespaceAndPath(
            ChronomancyMod.MODID, "personal_time_attack_speed");
    private TemporalDilationPlayerMovement() {}

    public static void onServerTick(ServerTickEvent.Pre event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            double rate = TemporalPlayerRate.server(player);
            update(player.getAttribute(Attributes.MOVEMENT_SPEED), MOVE_ID, rate);
            // Прыжок слабеет вместе со временем: из купола нельзя «выпрыгнуть».
            update(player.getAttribute(Attributes.JUMP_STRENGTH), JUMP_ID, rate < 1.0 ? Math.sqrt(rate) : 1.0);
            // Vanilla uses ATTACK_SPEED to compute the charged-hit interval.
            // This also syncs to the client, unlike a server-only attack timer.
            update(player.getAttribute(Attributes.ATTACK_SPEED), ATTACK_ID, rate);
        }
    }

    /**
     * Насколько замедлено движение игрока (0..1), по синхронизированному модификатору скорости —
     * поэтому одинаково на сервере и на клиенте, который сам считает ход игрока. Ускорение
     * (Borrowed Future) здесь не учитывается: множитель не больше 1.
     */
    public static float slowFactor(net.minecraft.world.entity.player.Player player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeModifier mod = speed == null ? null : speed.getModifier(MOVE_ID);
        if (mod == null || mod.amount() >= 0) return 1.0F;
        return (float) Math.max(0.0, 1.0 + mod.amount());
    }

    private static void update(AttributeInstance attribute, ResourceLocation id, double rate) {
        if (attribute == null) return;
        AttributeModifier old = attribute.getModifier(id);
        if (Math.abs(rate - 1.0) < 1.0e-4) {
            if (old != null) attribute.removeModifier(id);
        } else if (old == null || Math.abs(old.amount() - (rate - 1.0)) > 1.0e-4) {
            if (old != null) attribute.removeModifier(id);
            attribute.addTransientModifier(new AttributeModifier(id, rate - 1.0,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }
}
