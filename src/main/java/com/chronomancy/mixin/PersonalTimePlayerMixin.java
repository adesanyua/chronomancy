package com.chronomancy.mixin;

import com.chronomancy.temporal.TemporalPlayerClock;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Tick only the biological subsystems; never repeat Player.tick (inventory, packets, movement). */
@Mixin(Player.class)
public abstract class PersonalTimePlayerMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/food/FoodData;tick(Lnet/minecraft/world/entity/player/Player;)V"))
    private void chronomancy$food(FoodData food, Player player) {
        int steps = player instanceof ServerPlayer server ? TemporalPlayerClock.steps(server) : 1;
        for (int i = 0; i < steps; i++) food.tick(player);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemCooldowns;tick()V"))
    private void chronomancy$itemCooldowns(ItemCooldowns cooldowns) {
        Player player = (Player) (Object) this;
        int steps = player instanceof ServerPlayer server ? TemporalPlayerClock.steps(server) : 1;
        for (int i = 0; i < steps; i++) cooldowns.tick();
    }
}
